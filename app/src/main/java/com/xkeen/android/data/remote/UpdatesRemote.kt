package com.xkeen.android.data.remote

import com.xkeen.android.data.ssh.SshConnection
import com.xkeen.android.domain.model.ProxyCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class ComponentVersion(
    val title: String,
    val installed: String,
    val latest: String
) {
    val isInstalled: Boolean get() = installed.isNotBlank()
    val updateAvailable: Boolean
        get() = installed.isNotBlank() && latest.isNotBlank() && VersionCompare.compare(latest, installed) > 0
}

data class UpdatesInfo(
    val xkeen: ComponentVersion,
    val mihomo: ComponentVersion,
    val autoSupported: Boolean
)

object VersionCompare {
    private val number = Regex("""\d+(?:\.\d+)*""")

    /** Extracts the dotted version from strings like "v1.19.33", "XKeen 2.1 Stable". */
    fun normalize(value: String): String = number.find(value)?.value.orEmpty()

    fun compare(a: String, b: String): Int {
        val x = normalize(a).split('.').filter { it.isNotEmpty() }.map { it.toLongOrNull() ?: 0 }
        val y = normalize(b).split('.').filter { it.isNotEmpty() }.map { it.toLongOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }
}

/**
 * Checks and installs XKeen and Mihomo updates through xkeen's own non-interactive
 * `-uk auto` / `-um auto`, so download mirrors, architecture detection and backups stay
 * xkeen's responsibility. Release sources are read from xkeen's variable file, which keeps
 * the check correct for whatever fork is installed.
 */
class UpdatesRemote(private val ssh: SshConnection) {

    suspend fun check(): UpdatesInfo = withContext(Dispatchers.IO) {
        val out = ssh.exec(CHECK_SCRIPT, timeout = 45000).stdout
        val v = out.lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=').trim() }
        UpdatesInfo(
            xkeen = ComponentVersion("XKeen", VersionCompare.normalize(v["XKEEN_CUR"].orEmpty()), VersionCompare.normalize(v["XKEEN_NEW"].orEmpty())),
            mihomo = ComponentVersion("Mihomo", VersionCompare.normalize(v["MIHOMO_CUR"].orEmpty()), VersionCompare.normalize(v["MIHOMO_NEW"].orEmpty())),
            autoSupported = v["AUTO"] == "1"
        )
    }

    suspend fun updateXkeen(): Pair<Boolean, String> = update("XKeen", "-uk") { it.xkeen }

    suspend fun updateMihomo(): Pair<Boolean, String> = update("Mihomo", "-um") { it.mihomo }

    private suspend fun update(
        title: String,
        flag: String,
        pick: (UpdatesInfo) -> ComponentVersion
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val before = check()
            require(before.autoSupported) {
                "Установленная версия xkeen не умеет обновляться без вопросов. Обновите её один раз вручную: xkeen -uk"
            }
            val cmds = RouterCommands(ssh)
            val backup = cmds.backupCoreConfigs()
            val coreBefore = runCatching { cmds.getCoreState().activeCore }.getOrDefault(ProxyCore.UNKNOWN)
            // stdin from /dev/null: an unexpected prompt fails fast instead of hanging the router.
            val output = cleanOutput(ssh.exec("xkeen $flag auto </dev/null 2>&1", timeout = 600000).stdout)
            delay(2000)
            val after = check()
            val installed = pick(after).installed
            val state = cmds.getCoreState()
            var note = ""
            if (coreBefore != ProxyCore.UNKNOWN && !state.activeRunning) {
                ssh.exec("xkeen -start </dev/null >/dev/null 2>&1", timeout = 90000)
                delay(3000)
                note = if (cmds.getCoreState().activeRunning) " Прокси перезапущен." else
                    " Внимание: прокси не запустился, проверьте вкладку «Главная»."
            }
            val upgraded = VersionCompare.compare(installed, pick(before).installed) > 0
            val ok = upgraded || !pick(after).updateAvailable
            val message = when {
                upgraded -> "$title обновлён: ${pick(before).installed} → $installed.$note"
                ok -> "$title уже последней версии ($installed).$note"
                else -> "$title не обновился (сейчас $installed, доступна ${pick(after).latest}).$note"
            }
            ok to "$message\nBackup: $backup\n\n${output.takeLast(700)}".trim()
        } catch (e: Exception) {
            false to (e.message ?: "Не удалось обновить $title")
        }
    }

    private fun cleanOutput(out: String): String = out
        .replace(Regex("${27.toChar()}\\[[0-?]*[ -/]*[@-~]"), "")
        .replace(Regex("\\[(?:\\d{1,2})?m"), "")
        .lines().map { it.trimEnd() }.filter { it.isNotBlank() }.joinToString("\n")

    private companion object {
        val CHECK_SCRIPT = """
            V=/opt/sbin/.xkeen/01_info/01_info_variable.sh
            val() { sed -n "s/^$1=\"\([^\"]*\)\".*/\1/p" "${'$'}V" 2>/dev/null | head -n1; }
            echo "XKEEN_CUR=${'$'}(val xkeen_current_version)"
            ka=${'$'}(val xkeen_api_url); [ -n "${'$'}ka" ] || ka=https://api.github.com/repos/jameszeroX/xkeen/releases/latest
            kj=${'$'}(val xkeen_jsd_url)
            kn=${'$'}(curl -fsS -m 12 "${'$'}ka" 2>/dev/null | jq -r '.tag_name // .name // empty' 2>/dev/null)
            [ -z "${'$'}kn" ] && [ -n "${'$'}kj" ] && kn=${'$'}(curl -fsS -m 12 "${'$'}kj" 2>/dev/null | jq -r '.versions[]?' 2>/dev/null | grep -E '^v?[0-9]+(\.[0-9]+)+${'$'}' | head -n1)
            echo "XKEEN_NEW=${'$'}kn"
            command -v mihomo >/dev/null 2>&1 && echo "MIHOMO_CUR=${'$'}(mihomo -v 2>/dev/null | head -n1)"
            ma=${'$'}(val mihomo_api_url); [ -n "${'$'}ma" ] || ma=https://api.github.com/repos/MetaCubeX/mihomo/releases
            mj=${'$'}(val mihomo_jsd_url)
            mn=${'$'}(curl -fsS -m 12 "${'$'}ma/latest" 2>/dev/null | jq -r '.tag_name // empty' 2>/dev/null)
            [ -z "${'$'}mn" ] && [ -n "${'$'}mj" ] && mn=${'$'}(curl -fsS -m 12 "${'$'}mj" 2>/dev/null | jq -r '.versions[]?' 2>/dev/null | grep -E '^v?[0-9]+(\.[0-9]+)+${'$'}' | head -n1)
            echo "MIHOMO_NEW=${'$'}mn"
            grep -q 'get_auto_arg' /opt/sbin/xkeen 2>/dev/null && echo AUTO=1
            true
        """.trimIndent()
    }
}
