package com.xkeen.android.data.remote

import com.xkeen.android.data.ssh.SshConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.util.UUID

/** Installs and controls an independent router job; SSH only starts it and reads status. */
class XraySubscriptionRemote(private val ssh: SshConnection) {
    companion object {
        const val DIR = "/opt/etc/xkeen-android/xray-subscription"
        const val SCRIPT = "$DIR/subscription.py"
        const val CONFIG = "/opt/etc/xray/configs/08_xkeen_subscription.json"
        const val PREFIX = "proxy-sub-"
        const val GEO_BALANCER = "xk-sub-geo"
    }

    suspend fun status(): SubscriptionStatus = withContext(Dispatchers.IO) {
        val exists = ssh.exec("test -f '$SCRIPT'")
        check(exists.exitCode in 0..1) { "Не удалось проверить подписку Xray" }
        if (exists.exitCode == 1) return@withContext SubscriptionStatus()
        val result = ssh.exec("/opt/bin/python3 '$SCRIPT' status", timeout = 20000)
        check(result.exitCode == 0) { "Не удалось прочитать статус подписки Xray" }
        parseStatus(result.stdout)
    }

    internal fun parseStatus(raw: String): SubscriptionStatus {
        val obj = Json.parseToJsonElement(raw).jsonObject
        val settings = obj["settings"]?.jsonObject
        val base = SubscriptionStatus(
            enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull == true,
            url = obj["url"]?.jsonPrimitive?.content.orEmpty(),
            settings = SubscriptionSettings(settings?.get("topN")?.jsonPrimitive?.intOrNull ?: 10,
                settings?.get("dailyTime")?.jsonPrimitive?.content ?: "04:00"),
            scriptInstalled = obj["scriptInstalled"]?.jsonPrimitive?.booleanOrNull == true,
            cronInstalled = obj["cronInstalled"]?.jsonPrimitive?.booleanOrNull == true,
            geoGroup = obj["geoGroup"]?.jsonPrimitive?.booleanOrNull == true
        )
        return SubscriptionRemote(ssh).parseStatusJson(raw, base)
    }

    suspend fun enable(url: String, settings: SubscriptionSettings): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        runCatching {
            require(isValidSubscriptionUrl(url)) { "Нужна HTTP(S)-ссылка подписки" }
            check(RouterCommands(ssh).getCoreState().activeCore == com.xkeen.android.domain.model.ProxyCore.XRAY) {
                "Для подключения подписки Xray должен быть активен"
            }
            install()
            val request = "$DIR/request-${UUID.randomUUID()}.json"
            val content = buildJsonObject {
                put("url", url.trim()); put("topN", settings.topN); put("dailyTime", settings.dailyTime.trim())
            }.toString()
            ssh.writeFileB64(request, content)
            checked("chmod 600 '$request'")
            launch("configure", request)
        }.getOrElse { false to (it.message ?: "Не удалось подключить подписку Xray") }
    }

    suspend fun updateSettings(settings: SubscriptionSettings): Pair<Boolean, String> {
        val current = status()
        require(current.url.isNotBlank()) { "Сначала подключите подписку" }
        return enable(current.url, settings)
    }

    suspend fun start(mode: String): Pair<Boolean, String> = withContext(Dispatchers.IO) { launch(mode) }

    private suspend fun launch(mode: String, request: String? = null): Pair<Boolean, String> {
        require(mode in setOf("configure", "disable", "daily", "bench", "geo"))
        val result = ssh.exec("/opt/bin/python3 '$SCRIPT' launch $mode" + (request?.let { " '$it'" } ?: ""), timeout = 20000)
        return if (result.exitCode == 0 && result.stdout.trim() == "STARTED") {
            true to if (mode == "disable") "Отключаю автообновление. Текущие серверы и маршруты сохранятся."
                else "Задание запущено на роутере. Можно закрыть приложение; результат появится в карточке подписки."
        } else {
            request?.let { ssh.exec("rm -f '$it'") }
            false to result.stdout.trim().ifBlank { "Не удалось запустить обновление Xray" }
        }
    }

    private suspend fun install() {
        // Installed only after the user connects a subscription, never while viewing a screen.
        checked("command -v curl >/dev/null && command -v crond >/dev/null && test -x /opt/etc/init.d/S05crond",
            "Для подписки нужны curl и служба crond (S05crond)")
        val python = ssh.exec("/opt/bin/python3 -c 'import json,base64,hashlib,fcntl,subprocess,concurrent.futures,urllib.parse' 2>/dev/null")
        if (python.exitCode != 0) {
            checked("opkg update >/dev/null 2>&1 && opkg install python3 >/dev/null 2>&1", "Не удалось установить Python 3 через Entware: проверьте сеть и место в /opt", 600000)
        }
        checked("umask 077; mkdir -p '$DIR' && chmod 700 '$DIR'")
        val source = requireNotNull(javaClass.getResourceAsStream("/xk-xray-sub.py")).bufferedReader().use { it.readText() }
        ssh.writeFileB64(SCRIPT, source)
        checked("chmod 600 '$SCRIPT'")
    }

    suspend fun writeConfig(path: String, content: String, expected: String?) {
        val installed = ssh.exec("test -f '$SCRIPT' && echo XK_SUB").stdout.trim() == "XK_SUB"
        if (!installed) { ssh.writeFileB64(path, content); return }
        require(path in setOf(Paths.OUTBOUNDS, Paths.ROUTING, Paths.OBSERVATORY, "${Paths.CONFIGS_DIR}/03_inbounds.json", "${Paths.CONFIGS_DIR}/01_log.json")) { "Неизвестный файл Xray" }
        val original = expected ?: runCatching { ssh.readFile(path) }.getOrElse {
            if (it is com.xkeen.android.data.ssh.RemoteFileReadException && it.reason == com.xkeen.android.data.ssh.RemoteFileReadFailure.MISSING) null else throw it
        }
        val digest = original?.let { java.security.MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } } ?: "missing"
        val staged = "$DIR/edit-${UUID.randomUUID()}.json"
        try {
            ssh.writeFileB64(staged, content)
            val result = ssh.exec("/opt/bin/python3 '$SCRIPT' write '$path' '$staged' '$digest'", timeout = 20000)
            check(result.exitCode == 0 && result.stdout.trim() == "OK") {
                result.stdout.trim().ifBlank { "Не удалось сохранить конфиг Xray" }
            }
        } finally { ssh.exec("rm -f '$staged'") }
    }

    private suspend fun checked(command: String, message: String = "Не удалось подготовить подписку Xray", timeout: Int = 20000) {
        check(ssh.exec(command, timeout).exitCode == 0) { message }
    }
}
