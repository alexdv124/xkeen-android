package com.xkeen.android.data.remote

import com.xkeen.android.data.ssh.SshConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

class MihomoConfigRemote(private val ssh: SshConnection) {
    suspend fun writeFromXrayConfig(): Pair<Boolean, String> {
        val raw = MihomoConfigBuilder().fromXray(ssh.readFile(Paths.OUTBOUNDS), ssh.readFile(Paths.ROUTING))
        ssh.exec("mkdir -p ${Paths.MIHOMO_DIR} /opt/var/log/mihomo")
        ssh.writeFileB64(Paths.MIHOMO_CONFIG, raw)
        return true to "Конфиг Mihomo создан: ${Paths.MIHOMO_CONFIG}"
    }

    suspend fun getProxyGroups(): List<String> = withContext(Dispatchers.IO) {
        val path = RouterCommands(ssh).findMihomoConfigPath()
        require(path.isNotBlank()) { "Конфиг Mihomo не найден" }
        MihomoConfigEditor().groups(ssh.readFile(path))
    }

    suspend fun addVless(links: List<String>, customName: String, groups: List<String>): Pair<Boolean, String> =
        updateConfig("Добавлено серверов: ${links.size}") { raw ->
            MihomoConfigEditor().addVless(raw, links, customName, groups)
        }

    suspend fun getProxyNames(): Set<String> = withContext(Dispatchers.IO) {
        val path = RouterCommands(ssh).findMihomoConfigPath()
        require(path.isNotBlank()) { "Конфиг Mihomo не найден" }
        MihomoConfigEditor().proxyNames(ssh.readFile(path))
    }

    suspend fun removeProxies(names: Set<String>): Pair<Boolean, String> =
        updateConfig("Удалено серверов: ${names.size}", removedNames = names) { raw ->
            MihomoConfigEditor().removeProxies(raw, names)
        }

    suspend fun applyRoutingFromXray(): Pair<Boolean, String> =
        updateConfig("Маршрутизация Mihomo применена") { raw ->
            val rules = MihomoConfigBuilder().buildRules(ssh.readFile(Paths.ROUTING))
            MihomoConfigEditor().replaceRules(raw, rules)
        }

    private suspend fun updateConfig(
        successMessage: String,
        removedNames: Set<String> = emptySet(),
        transform: suspend (String) -> String
    ): Pair<Boolean, String> = updateMutex.withLock {
        withContext(Dispatchers.IO) {
            var stagedPath: String? = null
            try {
                val path = RouterCommands(ssh).findMihomoConfigPath()
                require(path.isNotBlank()) { "Конфиг Mihomo не найден" }
                val original = ssh.readFile(path)
                val updated = transform(original)
                val api = MihomoApi(ssh)
                require(api.isAvailable()) { "На роутере нужен curl: opkg install curl" }
                require(api.version().isNotBlank()) { "API Mihomo недоступен. Проверьте, что ядро запущено" }
                val selections = api.getProxies().values.filter { it.type == "Selector" && it.now.isNotBlank() }
                val id = UUID.randomUUID().toString()
                val staged = "$path.android-$id.tmp"
                val backup = "$path.android-$id.bak"
                stagedPath = staged
                ssh.writeFileB64(staged, updated)
                val test = ssh.exec(
                    "mihomo -t -d ${quote(path.substringBeforeLast('/'))} -f ${quote(staged)} 2>&1",
                    timeout = 30000
                )
                if (test.exitCode != 0) {
                    return@withContext false to "Конфиг Mihomo не прошёл проверку: ${(test.stdout + test.stderr).takeLast(800)}"
                }
                check(ssh.readFile(path) == original) { "Конфиг изменился во время операции. Повторите действие" }
                checked("cp -p ${quote(path)} ${quote(backup)}")
                // Once installation starts, complete either activation or rollback even if the
                // screen is closed. A timed-out move/reload may already have taken effect.
                withContext(NonCancellable) {
                    try {
                        checked("mv ${quote(staged)} ${quote(path)}")
                        check(api.reloadConfig(path)) { "Mihomo не применил новый конфиг" }
                        val newGroups = if (removedNames.isEmpty()) emptyMap() else api.getProxies()
                        val restoredSelection = selections.map { selection ->
                            val next = if (selection.now in removedNames) {
                                newGroups[selection.name]?.all?.firstOrNull { it !in removedNames }
                            } else selection.now
                            next != null && runCatching { api.select(selection.name, next) }.getOrDefault(false)
                        }.all { it }
                        true to ("$successMessage. Применено в Mihomo." +
                            if (restoredSelection) "" else " Проверьте выбранный сервер: не удалось восстановить выбор.")
                    } catch (e: Exception) {
                        val restored = runCatching {
                            checked("cp -p ${quote(backup)} ${quote(path)}")
                            check(api.reloadConfig(path))
                            selections.forEach { api.select(it.name, it.now) }
                        }.isSuccess
                        false to if (restored) {
                            "Не удалось применить конфиг: ${e.message}. Прежний конфиг восстановлен."
                        } else {
                            "Не удалось применить конфиг и подтвердить откат. Резервная копия: $backup"
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false to (e.message ?: "Не удалось обновить конфиг Mihomo")
            } finally {
                stagedPath?.let { path ->
                    withContext(NonCancellable) { runCatching { ssh.exec("rm -f ${quote(path)}") } }
                }
            }
        }
    }

    private suspend fun checked(command: String) {
        val result = ssh.exec(command)
        check(result.exitCode == 0) { "Ошибка записи конфига: ${result.stderr.takeLast(300)}" }
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private companion object {
        // Serializes read/modify/write across screen instances and batch imports.
        val updateMutex = Mutex()
    }
}
