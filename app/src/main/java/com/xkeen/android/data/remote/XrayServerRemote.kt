package com.xkeen.android.data.remote

import com.xkeen.android.data.ssh.SshConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/** One validation and one restart for a complete batch of server deletions. */
class XrayServerRemote(private val ssh: SshConnection) {
    suspend fun removeProxies(tags: Set<String>): Pair<Boolean, String> = mutex.withLock {
        withContext(Dispatchers.IO) {
            var staging: String? = null
            try {
                val originals = linkedMapOf(
                    Paths.OUTBOUNDS to ssh.readFile(Paths.OUTBOUNDS),
                    Paths.ROUTING to ssh.readFile(Paths.ROUTING)
                )
                val observer = ssh.exec("test -f ${Paths.OBSERVATORY}")
                check(observer.exitCode in 0..1) { "Не удалось прочитать конфиг observatory" }
                if (observer.exitCode == 0) originals[Paths.OBSERVATORY] = ssh.readFile(Paths.OBSERVATORY)
                val updated = XrayServerEditor().remove(originals, tags)
                val id = UUID.randomUUID().toString()
                val staged = "/opt/etc/xray/.android-delete-$id"
                val backup = "${Paths.CONFIGS_DIR}/backups/delete-$id"
                staging = staged
                checked("mkdir -p '$staged' '$backup' && cp ${Paths.CONFIGS_DIR}/*.json '$staged/' && cp ${Paths.CONFIGS_DIR}/*.json '$backup/'")
                for ((path, content) in updated) ssh.writeFileB64("$staged/${path.substringAfterLast('/')}", content)
                val test = ssh.exec(
                    "XRAY_LOCATION_ASSET=/opt/etc/xray/dat XRAY_LOCATION_CONFDIR='$staged' " +
                        "xray run -test -confdir '$staged/' 2>&1", timeout = 30000
                )
                if (test.exitCode != 0) return@withContext false to
                    "Конфиг Xray не прошёл проверку: ${(test.stdout + test.stderr).takeLast(800)}"
                check(originals.all { (path, content) -> ssh.readFile(path) == content }) {
                    "Конфиг изменился во время операции. Повторите удаление"
                }
                // Do not accidentally restart another core if it was changed outside this screen.
                checked("grep -q '^name_client=\"xray\"' /opt/etc/init.d/S99xkeen")
                withContext(NonCancellable) {
                    try {
                        for (path in updated.keys) {
                            val file = path.substringAfterLast('/')
                            checked("cp '$staged/$file' '$path.tmp' && mv '$path.tmp' '$path'")
                        }
                        check(restart()) { "Xray не запустился после удаления" }
                        true to "Удалено серверов: ${tags.size}. Xray перезапущен."
                    } catch (e: Exception) {
                        val restored = runCatching {
                            // Restore every affected file, including a write whose reply timed out.
                            for (path in originals.keys) {
                                val file = path.substringAfterLast('/')
                                checked("cp '$backup/$file' '$path.tmp' && mv '$path.tmp' '$path'")
                            }
                            check(restart())
                        }.isSuccess
                        false to if (restored) {
                            "Удаление не применено: ${e.message}. Прежний конфиг восстановлен."
                        } else {
                            "Не удалось подтвердить откат Xray. Резервная копия: $backup"
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false to (e.message ?: "Не удалось удалить серверы Xray")
            } finally {
                staging?.let { dir ->
                    withContext(NonCancellable) { runCatching { ssh.exec("rm -rf '$dir'") } }
                }
            }
        }
    }

    private suspend fun restart(): Boolean {
        val result = ssh.exec("/opt/etc/init.d/S99xkeen restart 2>&1", timeout = 30000)
        if (result.exitCode != 0) return false
        repeat(5) {
            if (ssh.exec("pidof xray").stdout.trim().isNotEmpty()) return true
            delay(1000)
        }
        return false
    }

    private suspend fun checked(command: String) {
        val result = ssh.exec(command)
        check(result.exitCode == 0) { "Операция Xray не выполнена: ${result.stderr.takeLast(300)}" }
    }

    private companion object { val mutex = Mutex() }
}
