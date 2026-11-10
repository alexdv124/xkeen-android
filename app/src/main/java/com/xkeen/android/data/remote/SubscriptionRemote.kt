package com.xkeen.android.data.remote

import com.xkeen.android.data.ssh.SshConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class SubscriptionSettings(
    val topN: Int = 10,
    val dailyTime: String = "04:00"
) {
    init {
        require(topN in 1..30) { "Количество серверов: от 1 до 30" }
        require(parseTime(dailyTime) != null) { "Время обновления в формате ЧЧ:ММ, например 04:00" }
    }

    val hour: Int get() = parseTime(dailyTime)!!.first
    val minute: Int get() = parseTime(dailyTime)!!.second

    companion object {
        fun parseTime(value: String): Pair<Int, Int>? {
            val m = Regex("""^\s*(\d{1,2}):(\d{2})\s*$""").find(value) ?: return null
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            return if (h in 0..23 && min in 0..59) h to min else null
        }
    }
}

data class BenchServer(
    val name: String,
    val delayMs: Int,
    val downMbit: Double,
    val upMbit: Double,
    val chosen: Boolean
)

data class GeoServer(val name: String, val ok: Boolean, val reason: String = "")

data class SubscriptionStatus(
    val enabled: Boolean = false,
    val url: String = "",
    val settings: SubscriptionSettings = SubscriptionSettings(),
    val scriptInstalled: Boolean = false,
    val cronInstalled: Boolean = false,
    val running: Boolean = false,
    val lastUpdate: Long = 0,
    val lastBench: Long = 0,
    val lastError: String = "",
    val log: String = "",
    val servers: List<BenchServer> = emptyList(),
    /** False for configs made by 1.7.x: SUB-GEO appears after re-saving the settings. */
    val geoGroup: Boolean = false,
    val geoServers: List<GeoServer> = emptyList()
) {
    val chosen: List<BenchServer> get() = servers.filter { it.chosen }
    val geoOk: List<GeoServer> get() = geoServers.filter { it.ok }
}

/**
 * Subscription mode for Mihomo. Mihomo downloads the subscription itself (proxy-provider);
 * the router-side `xk-sub.sh` refreshes it daily from cron, measures real speed of the
 * fastest-responding servers and limits SUB-BEST to the top N. Nothing depends on the phone
 * after setup.
 */
class SubscriptionRemote(private val ssh: SshConnection) {
    private val json = Json { ignoreUnknownKeys = true }

    private companion object {
        const val LOCK = "/tmp/xk-sub.lock"
    }

    suspend fun status(): SubscriptionStatus = withContext(Dispatchers.IO) {
        val path = RouterCommands(ssh).findMihomoConfigPath()
        val raw = if (path.isBlank()) "" else ssh.readFile(path)
        val editor = MihomoConfigEditor()
        val url = runCatching { editor.subscriptionUrl(raw) }.getOrNull().orEmpty()
        val geoGroup = runCatching { editor.hasGroup(raw, SubscriptionNames.GEO) }.getOrDefault(false)
        val out = ssh.exec(
            "S=${SubscriptionNames.SCRIPT}; " +
                "[ -x \"\$S\" ] && echo SCRIPT=1; " +
                "grep -q 'xk-sub.sh daily' ${SubscriptionNames.CRONTAB} 2>/dev/null && echo CRON=1; " +
                "[ -f ${SubscriptionNames.CONF} ] && sed -n -e 's/^TOP_N=/CONF_TOP_N=/p' -e 's/^DAILY_TIME=/CONF_DAILY_TIME=/p' ${SubscriptionNames.CONF}; " +
                "echo '===JSON==='; [ -x \"\$S\" ] && \"\$S\" status 2>/dev/null",
            timeout = 20000
        ).stdout
        val head = out.substringBefore("===JSON===")
        val body = out.substringAfter("===JSON===", "").trim()
        val conf = head.lines().filter { it.startsWith("CONF_") }.associate {
            it.removePrefix("CONF_").substringBefore('=') to it.substringAfter('=').trim().trim('\'', '"')
        }
        val settings = runCatching {
            SubscriptionSettings(
                topN = conf["TOP_N"]?.toIntOrNull() ?: 10,
                dailyTime = conf["DAILY_TIME"] ?: "04:00"
            )
        }.getOrDefault(SubscriptionSettings())
        val base = SubscriptionStatus(
            enabled = url.isNotBlank(),
            url = url,
            settings = settings,
            scriptInstalled = "SCRIPT=1" in head,
            cronInstalled = "CRON=1" in head,
            geoGroup = geoGroup
        )
        parseStatusJson(body, base)
    }

    internal fun parseStatusJson(body: String, base: SubscriptionStatus): SubscriptionStatus {
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return base
        fun s(key: String) = obj[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }.orEmpty()
        val result = obj["result"]?.let { runCatching { it.jsonObject }.getOrNull() }
        val servers = result?.get("servers")?.let { runCatching { it.jsonArray }.getOrNull() }?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            BenchServer(
                name = name,
                delayMs = o["delay"]?.jsonPrimitive?.intOrNull ?: 0,
                downMbit = o["down"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                upMbit = o["up"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                chosen = o["chosen"]?.jsonPrimitive?.booleanOrNull ?: false
            )
        }.orEmpty().sortedWith(compareByDescending<BenchServer> { it.chosen }.thenByDescending { it.downMbit + 2 * it.upMbit })
        val geo = obj["geo"]?.let { runCatching { it.jsonObject["servers"]?.jsonArray }.getOrNull() }?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            GeoServer(
                name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                ok = o["ok"]?.jsonPrimitive?.booleanOrNull ?: false,
                reason = o["reason"]?.jsonPrimitive?.contentOrNull.orEmpty()
            )
        }.orEmpty()
        return base.copy(
            geoServers = geo,
            running = obj["running"]?.jsonPrimitive?.booleanOrNull ?: false,
            lastUpdate = s("last_update").toLongOrNull() ?: 0,
            lastBench = s("last_bench").toLongOrNull() ?: result?.get("ts")?.jsonPrimitive?.longOrNull ?: 0,
            lastError = s("last_error"),
            log = s("log"),
            servers = servers
        )
    }

    /** Installs the script, config changes and cron, then starts the first refresh in background. */
    suspend fun enable(url: String, settings: SubscriptionSettings): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            require(isValidSubscriptionUrl(url)) { "Ссылка подписки должна начинаться с http:// или https://" }
            val cmds = RouterCommands(ssh)
            val path = cmds.findMihomoConfigPath()
            require(path.isNotBlank()) { "Конфиг Mihomo не найден" }
            val tools = ssh.exec("for t in curl jq yq crond; do command -v \$t >/dev/null 2>&1 || echo \$t; done").stdout.trim()
            require(tools.isEmpty()) { "На роутере не хватает: ${tools.lines().joinToString(", ")}. Установите через opkg" }

            installScript(path, settings)
            val (ok, msg) = MihomoConfigRemote(ssh).enableSubscription(url)
            if (!ok) return@withContext false to msg
            runCatching { MihomoApi(ssh).select("PROXY", SubscriptionNames.BEST) }
            installCron(settings)
            val started = startInBackground("daily", delaySeconds = 3)
            true to "${msg.trimEnd('.')}. " + (if (started) "Роутер загружает подписку и замеряет скорость серверов, это займёт несколько минут. "
                else "Замер начнётся после текущего. ") + "Обновление каждый день в ${settings.dailyTime}."
        } catch (e: Exception) {
            false to (e.message ?: "Не удалось подключить подписку")
        }
    }

    /**
     * Changes the schedule/top-N. A config made by an older app version is upgraded in place
     * (re-applying the subscription is idempotent and keeps the benchmark filters).
     */
    suspend fun updateSettings(settings: SubscriptionSettings): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val path = RouterCommands(ssh).findMihomoConfigPath()
            require(path.isNotBlank()) { "Конфиг Mihomo не найден" }
            installScript(path, settings)
            installCron(settings)
            val raw = ssh.readFile(path)
            val editor = MihomoConfigEditor()
            val url = editor.subscriptionUrl(raw)
            var extra = ""
            if (url != null && !editor.hasGroup(raw, SubscriptionNames.GEO)) {
                val (ok, msg) = MihomoConfigRemote(ssh).enableSubscription(url)
                if (!ok) return@withContext false to msg
                extra = if (startInBackground("geo", delaySeconds = 2))
                    " Включён обход геоблока для ${SubscriptionNames.GEO_SERVICES.joinToString()}: роутер подбирает для них серверы."
                else ""
            }
            true to "Настройки сохранены. Обновление каждый день в ${settings.dailyTime}, серверов: ${settings.topN}.$extra"
        } catch (e: Exception) {
            false to (e.message ?: "Не удалось сохранить настройки")
        }
    }

    suspend fun disable(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val (ok, msg) = MihomoConfigRemote(ssh).disableSubscription()
        if (!ok) return@withContext false to msg
        try {
            removeCron()
        } catch (e: Exception) {
            return@withContext false to "Подписка отключена, но не удалось удалить расписание: ${e.message}"
        }
        true to msg
    }

    /** Refresh the subscription and pick the best servers again (same as the daily job). */
    suspend fun refreshNow(): Pair<Boolean, String> = start("daily", "Обновляю подписку и замеряю скорость серверов…")

    /** Re-check which servers geo-blocking services accept (no speed test). */
    suspend fun recheckGeo(): Pair<Boolean, String> = start("geo", "Проверяю, какие серверы пускают ElevenLabs, ChatGPT и Claude…")

    /** Re-measure without downloading the subscription again. */
    suspend fun rebenchNow(): Pair<Boolean, String> = start("bench", "Замеряю скорость серверов…")

    private suspend fun start(mode: String, message: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (startInBackground(mode, 0)) true to message else false to "Замер уже выполняется"
    }

    /**
     * Reserves the script's lock with the PID of a detached job, so the app sees the run as
     * started immediately, and the SSH channel closes at once while the job survives the app.
     * Returns false when another run holds the lock.
     */
    private suspend fun startInBackground(mode: String, delaySeconds: Int): Boolean {
        val out = ssh.exec(
            "L=$LOCK; " +
                "if [ -d \$L ] && kill -0 \"\$(cat \$L/pid 2>/dev/null)\" 2>/dev/null; then echo BUSY; else " +
                "rm -rf \$L; mkdir \$L && " +
                "{ (sleep $delaySeconds; exec ${SubscriptionNames.SCRIPT} $mode) </dev/null >/dev/null 2>&1 & echo \$! > \$L/pid; echo STARTED; }; fi",
            timeout = 10000
        ).stdout
        return "STARTED" in out
    }

    private suspend fun installScript(configPath: String, settings: SubscriptionSettings) {
        val script = requireNotNull(javaClass.getResourceAsStream("/xk-sub.sh")) { "Скрипт подписки не найден в приложении" }
            .bufferedReader().use { it.readText() }
        ssh.exec("mkdir -p ${SubscriptionNames.DIR}")
        val lockScript = requireNotNull(javaClass.getResourceAsStream("/xk-config-lock.sh"))
            .bufferedReader().use { it.readText() }
        ssh.writeFileB64("${SubscriptionNames.DIR}/xk-config-lock.sh", lockScript)
        ssh.writeFileB64(SubscriptionNames.SCRIPT, script)
        ssh.writeFileB64(SubscriptionNames.CONF, buildConf(configPath, settings))
        val chmod = ssh.exec("chmod 755 ${SubscriptionNames.SCRIPT} && echo OK")
        check("OK" in chmod.stdout) { "Не удалось установить скрипт подписки: ${chmod.stderr.take(200)}" }
    }

    internal fun buildConf(configPath: String, settings: SubscriptionSettings): String = buildString {
        appendLine("# Written by XKeen Android. Changed from the app's subscription settings.")
        appendLine("TOP_N=${settings.topN}")
        appendLine("CANDIDATES=${maxOf(settings.topN * 2, 15)}")
        appendLine("DAILY_TIME='${settings.dailyTime.trim()}'")
        appendLine("MIHOMO_CONFIG=${shellQuote(configPath)}")
    }

    internal fun cronLines(settings: SubscriptionSettings): List<String> = listOf(
        "${settings.minute} ${settings.hour} * * * ${SubscriptionNames.SCRIPT} daily >/dev/null 2>&1",
        "*/30 * * * * ${SubscriptionNames.SCRIPT} check >/dev/null 2>&1"
    )

    private suspend fun installCron(settings: SubscriptionSettings) {
        val lines = cronLines(settings).joinToString("\\n")
        val out = ssh.exec(
            "f=${SubscriptionNames.CRONTAB}; mkdir -p \"\$(dirname \"\$f\")\" && touch \"\$f\" && " +
                "grep -v 'xkeen-android/xk-sub.sh' \"\$f\" > \"\$f.xk\"; printf '$lines\\n' >> \"\$f.xk\" && " +
                "mv \"\$f.xk\" \"\$f\" && " +
                "(/opt/etc/init.d/S05crond restart >/dev/null 2>&1 || (killall crond; /opt/sbin/crond -L /dev/null)) ; " +
                "pidof crond >/dev/null && echo OK",
            timeout = 20000
        )
        check("OK" in out.stdout) { "Не удалось настроить cron на роутере" }
    }

    internal fun removeCronCommand(path: String = SubscriptionNames.CRONTAB): String =
        "f=${shellQuote(path)}; if [ -f \"\$f\" ]; then " +
            "awk 'index(\$0, \"xkeen-android/xk-sub.sh\") == 0' \"\$f\" > \"\$f.xk\" && " +
            "mv \"\$f.xk\" \"\$f\" || exit 1; fi"

    private suspend fun removeCron() {
        val result = ssh.exec(
            removeCronCommand() + "; /opt/etc/init.d/S05crond restart >/dev/null 2>&1",
            timeout = 20000
        )
        check(result.exitCode == 0) { "Не удалось удалить задачи cron или перезапустить crond" }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
