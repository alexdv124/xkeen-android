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
        val generated = MihomoConfigBuilder().fromXray(ssh.readFile(Paths.OUTBOUNDS), ssh.readFile(Paths.ROUTING))
        val state = RouterCommands(ssh).getCoreState()
        ssh.exec("mkdir -p ${Paths.MIHOMO_DIR} /opt/var/log/mihomo")
        return updateConfig("Конфиг Mihomo собран", apply = state.mihomoRunning,
            validate = state.mihomoInstalled, allowMissing = true) { existing ->
            if (existing.isBlank()) generated else MihomoConfigEditor().carrySubscription(existing, generated)
        }
    }

    suspend fun restoreConfig(raw: String, apply: Boolean): Pair<Boolean, String> =
        updateConfig("Mihomo восстановлен", apply = apply, allowMissing = true) { raw }

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

    suspend fun enableSubscription(url: String): Pair<Boolean, String> =
        updateConfig("Подписка подключена") { raw -> MihomoConfigEditor().enableSubscription(raw, url) }

    suspend fun disableSubscription(): Pair<Boolean, String> =
        updateConfig(
            "Подписка отключена",
            removedNames = SubscriptionNames.GROUPS
        ) { raw -> MihomoConfigEditor().disableSubscription(raw) }

    suspend fun subscriptionUrl(): String? = withContext(Dispatchers.IO) {
        val path = RouterCommands(ssh).findMihomoConfigPath()
        if (path.isBlank()) null else MihomoConfigEditor().subscriptionUrl(ssh.readFile(path))
    }

    suspend fun applyRoutingFromXray(): Pair<Boolean, String> =
        updateConfig("Маршрутизация Mihomo применена") { raw ->
            val rules = MihomoConfigBuilder().buildRules(ssh.readFile(Paths.ROUTING))
            MihomoConfigEditor().replaceRules(raw, rules)
        }

    private suspend fun updateConfig(
        successMessage: String,
        removedNames: Set<String> = emptySet(),
        apply: Boolean = true,
        validate: Boolean = true,
        allowMissing: Boolean = false,
        transform: suspend (String) -> String
    ): Pair<Boolean, String> = updateMutex.withLock {
        withContext(Dispatchers.IO) {
            var stagedPath: String? = null
            try {
                val foundPath = RouterCommands(ssh).findMihomoConfigPath()
                require(allowMissing || foundPath.isNotBlank()) { "Конфиг Mihomo не найден" }
                val path = foundPath.ifBlank { Paths.MIHOMO_CONFIG }
                val original = if (foundPath.isBlank()) null else ssh.readFile(path)
                val updated = transform(original.orEmpty())
                val api = MihomoApi(ssh)
                val controller = if (apply) {
                    require(api.isAvailable()) { "На роутере нужен curl: opkg install curl" }
                    require(api.version().isNotBlank()) { "API Mihomo недоступен. Проверьте, что ядро запущено" }
                    api.resolveController()
                } else null
                // SUB-BENCH is owned by a running benchmark; never reset its current candidate.
                val selections = if (apply) api.getProxies().values.filter {
                    it.type == "Selector" && it.now.isNotBlank() && it.name != SubscriptionNames.BENCH
                } else emptyList()
                val id = UUID.randomUUID().toString()
                val staged = "$path.android-$id.tmp"
                val backup = "$path.android-$id.bak"
                stagedPath = staged
                ssh.writeFileB64(staged, updated)
                if (validate) {
                    val test = ssh.exec(
                        "mihomo -t -d ${quote(path.substringBeforeLast('/'))} -f ${quote(staged)} 2>&1",
                        timeout = 30000
                    )
                    if (test.exitCode != 0) {
                        return@withContext false to "Конфиг Mihomo не прошёл проверку: ${(test.stdout + test.stderr).takeLast(800)}"
                    }
                }
                withContext(NonCancellable) {
                    val (installed, detail) = MihomoConfigTransaction.install(ssh, path, original, staged, backup, controller)
                    if (!installed) {
                        if (detail.contains("Прежний конфиг восстановлен")) {
                            selections.forEach { runCatching { api.select(it.name, it.now) } }
                        }
                        return@withContext false to detail
                    }
                    // Failure to restore a selector must not turn a completed installation into
                    // an unsafe second rollback after the remote transaction released its lock.
                    val restoredSelection = runCatching {
                        val newGroups = if (removedNames.isEmpty()) emptyMap() else api.getProxies()
                        selections.filter { removedNames.isEmpty() || it.name in newGroups }.map { selection ->
                            val next = if (selection.now in removedNames) {
                                newGroups[selection.name]?.all?.firstOrNull { it !in removedNames }
                            } else selection.now
                            next != null && api.select(selection.name, next)
                        }.all { it }
                    }.getOrDefault(false)
                    true to ("$successMessage. " +
                        (if (apply) "Применено в Mihomo." else if (validate) "Конфиг проверен и сохранён." else "Сохранено без проверки: Mihomo не установлен.") +
                        if (restoredSelection) "" else " Проверьте выбранный сервер: не удалось восстановить выбор.")
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

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private companion object {
        // Serializes read/modify/write across screen instances and batch imports.
        val updateMutex = Mutex()
    }
}
