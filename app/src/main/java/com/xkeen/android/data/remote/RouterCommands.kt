package com.xkeen.android.data.remote

import com.xkeen.android.data.ssh.SshConnection
import com.xkeen.android.domain.model.ConfigTestResult
import com.xkeen.android.domain.model.CoreState
import com.xkeen.android.domain.model.CoreSwitchResult
import kotlinx.coroutines.delay
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.xkeen.android.domain.model.ObservatoryState
import com.xkeen.android.domain.model.ProxyCore
import com.xkeen.android.domain.model.RouterStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Paths {
    const val CONFIGS_DIR = "/opt/etc/xray/configs"
    const val MIHOMO_DIR = "/opt/etc/mihomo"
    const val MIHOMO_CONFIG = "$MIHOMO_DIR/config.yaml"
    const val LOG_DIR = "/opt/var/log/xray"
    const val OUTBOUNDS = "$CONFIGS_DIR/04_outbounds.json"
    const val ROUTING = "$CONFIGS_DIR/05_routing.json"
    const val OBSERVATORY = "$CONFIGS_DIR/07_observatory.json"
}

class RouterCommands(private val ssh: SshConnection) {

    private data class ProcessInfo(
        val running: Boolean = false,
        val pid: String = "",
        val mem: String = ""
    )

    private fun parseProcessInfo(out: String): ProcessInfo {
        val line = out.lineSequence().firstOrNull { it.isNotBlank() } ?: return ProcessInfo()
        val parts = line.trim().split(Regex("\\s+"))
        return ProcessInfo(
            running = true,
            pid = parts.getOrElse(0) { "" },
            mem = parts.getOrElse(2) { "" }
        )
    }

    private suspend fun getProcessInfo(processName: String): ProcessInfo {
        val out = ssh.exec(
            "pid=\"\$(pidof $processName 2>/dev/null | cut -d' ' -f1)\"; " +
                "if [ -n \"\$pid\" ]; then " +
                "line=\"\$(ps | grep \"^ *\$pid \")\"; " +
                "[ -n \"\$line\" ] && echo \"\$line\" || echo \"\$pid\"; " +
                "fi"
        ).stdout
        return parseProcessInfo(out)
    }

    private fun cleanTerminalOutput(out: String): String {
        return out
            .replace(Regex("${27.toChar()}\\[[0-?]*[ -/]*[@-~]"), "")
            .replace(Regex("\\[(?:\\d{1,2})?m"), "")
            .replace("[H[J", "")
            .trim()
    }

    private suspend fun waitForCore(target: ProxyCore, attempts: Int = 10): CoreState {
        var latest = getCoreState(withConfigTests = false)
        repeat(attempts) {
            val running = when (target) {
                ProxyCore.XRAY -> latest.xrayRunning
                ProxyCore.MIHOMO -> latest.mihomoRunning
                ProxyCore.UNKNOWN -> latest.activeRunning
            }
            if (latest.activeCore == target && running) return latest
            delay(2500)
            latest = getCoreState(withConfigTests = false)
        }
        return latest
    }

    private suspend fun startXkeenQuietly(): String {
        return ssh.exec("fd_out=true /opt/etc/init.d/S99xkeen start on 2>&1", timeout = 30000).stdout
    }

    private suspend fun stopXkeenQuietly(): String {
        return ssh.exec("/opt/etc/init.d/S99xkeen stop 2>&1", timeout = 30000).stdout
    }

    suspend fun getStatus(): RouterStatus {
        var status = RouterStatus()

        val coreState = getCoreState()
        val activeInfo = when (coreState.activeCore) {
            ProxyCore.XRAY -> ProcessInfo(coreState.xrayRunning, coreState.xrayPid, coreState.xrayMem)
            ProxyCore.MIHOMO -> ProcessInfo(coreState.mihomoRunning, coreState.mihomoPid, coreState.mihomoMem)
            ProxyCore.UNKNOWN -> {
                if (coreState.xrayRunning) ProcessInfo(true, coreState.xrayPid, coreState.xrayMem)
                else ProcessInfo(coreState.mihomoRunning, coreState.mihomoPid, coreState.mihomoMem)
            }
        }
        status = status.copy(
            xrayRunning = coreState.xrayRunning,
            xrayPid = coreState.xrayPid,
            xrayMem = coreState.xrayMem,
            activeCore = coreState.activeCore,
            coreRunning = activeInfo.running,
            corePid = activeInfo.pid,
            coreMem = activeInfo.mem
        )

        // Memory
        val memOut = ssh.exec("free").stdout
        for (line in memOut.lines()) {
            if (line.startsWith("Mem:")) {
                val cols = line.split(Regex("\\s+"))
                status = status.copy(
                    memTotal = cols.getOrElse(1) { "0" }.toLongOrNull() ?: 0,
                    memUsed = cols.getOrElse(2) { "0" }.toLongOrNull() ?: 0,
                    memFree = cols.getOrElse(3) { "0" }.toLongOrNull() ?: 0
                )
                break
            }
        }

        // CPU
        val topOut = ssh.exec("top -bn1 | head -3").stdout
        for (line in topOut.lines()) {
            if ("CPU:" in line) {
                val idleMatch = Regex("""(\d+\.?\d*)%\s*idle""").find(line)
                val idle = idleMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
                status = status.copy(cpuUsed = Math.round((100 - idle) * 10.0) / 10.0)
            }
            if ("Load average:" in line) {
                val laMatch = Regex("""Load average:\s*([\d.]+)""").find(line)
                status = status.copy(loadAvg = laMatch?.groupValues?.get(1) ?: "")
            }
        }

        // Uptime
        val uptimeOut = ssh.exec("uptime").stdout.trim()
        status = status.copy(uptime = uptimeOut)

        // xkeen version
        val verOut = ssh.exec("xkeen -v 2>/dev/null || echo unknown").stdout.trim()
        status = status.copy(xkeenVersion = verOut)

        return status
    }

    suspend fun getCoreState(withConfigTests: Boolean = false): CoreState {
        val statusText = ssh.exec("xkeen -status 2>&1 || true").stdout.trim()
        val configuredCoreText = ssh.exec(
            "grep -m1 '^name_client=' /opt/etc/init.d/S99xkeen 2>/dev/null | cut -d'\"' -f2 || true"
        ).stdout.trim()
        val configuredCore = ProxyCore.fromCliName(configuredCoreText)
        val activeCore = when {
            statusText.contains("mihomo", ignoreCase = true) -> ProxyCore.MIHOMO
            statusText.contains("xray", ignoreCase = true) -> ProxyCore.XRAY
            configuredCore != ProxyCore.UNKNOWN -> configuredCore
            else -> ProxyCore.UNKNOWN
        }

        val xrayInstalled = ssh.exec("command -v xray 2>/dev/null || true").stdout.trim().isNotEmpty()
        val mihomoInstalled = ssh.exec("command -v mihomo 2>/dev/null || true").stdout.trim().isNotEmpty()

        val xrayInfo = getProcessInfo("xray")
        val mihomoInfo = getProcessInfo("mihomo")

        val xrayVersion = if (xrayInstalled) {
            ssh.exec("xray version 2>&1 | head -1 || true").stdout.trim()
        } else ""
        val mihomoVersion = if (mihomoInstalled) {
            ssh.exec("(mihomo -v 2>&1 || mihomo version 2>&1) | head -1 || true").stdout.trim()
        } else ""

        val xrayTest = if (withConfigTests && xrayInstalled) testCoreConfig(ProxyCore.XRAY) else null
        val mihomoTest = if (withConfigTests && mihomoInstalled) testCoreConfig(ProxyCore.MIHOMO) else null

        return CoreState(
            activeCore = activeCore,
            xrayInstalled = xrayInstalled,
            mihomoInstalled = mihomoInstalled,
            xrayRunning = xrayInfo.running,
            mihomoRunning = mihomoInfo.running,
            xrayVersion = xrayVersion,
            mihomoVersion = mihomoVersion,
            xrayPid = xrayInfo.pid,
            mihomoPid = mihomoInfo.pid,
            xrayMem = xrayInfo.mem,
            mihomoMem = mihomoInfo.mem,
            xrayConfigOk = xrayTest?.ok,
            mihomoConfigOk = mihomoTest?.ok,
            xrayConfigDetail = xrayTest?.output?.lines()?.lastOrNull { it.isNotBlank() } ?: "",
            mihomoConfigDetail = mihomoTest?.output?.lines()?.lastOrNull { it.isNotBlank() } ?: "",
            mihomoConfigPath = findMihomoConfigPath(),
            statusText = statusText
        )
    }

    suspend fun findMihomoConfigPath(): String {
        return ssh.exec(
            "for f in " +
                "/opt/etc/mihomo/config.yaml /opt/etc/mihomo/config.yml " +
                "/opt/etc/mihomo/*.yaml /opt/etc/mihomo/*.yml " +
                "/opt/etc/xkeen/mihomo.yaml /opt/etc/xkeen/mihomo.yml; do " +
                "[ -f \"\$f\" ] && echo \"\$f\" && break; " +
            "done 2>/dev/null"
        ).stdout.trim().lineSequence().firstOrNull().orEmpty()
    }

    suspend fun testCoreConfig(core: ProxyCore): ConfigTestResult {
        return when (core) {
            ProxyCore.XRAY -> testConfig()
            ProxyCore.MIHOMO -> {
                val configPath = findMihomoConfigPath()
                if (configPath.isBlank()) {
                    ConfigTestResult(false, "Конфиг Mihomo не найден в /opt/etc/mihomo")
                } else {
                    val result = ssh.exec(
                        "mihomo -t -f \"$configPath\" 2>&1",
                        timeout = 30000
                    )
                    val out = result.stdout
                    // mihomo -t exits 0 on success. Trust the exit code; only fall back to
                    // string-matching when the code is unknown (-1, e.g. the call timed out).
                    val ok = when {
                        result.exitCode == 0 -> true
                        result.exitCode > 0 -> false
                        else -> "test is successful" in out.lowercase()
                    }
                    ConfigTestResult(ok, out)
                }
            }
            ProxyCore.UNKNOWN -> ConfigTestResult(false, "Активное ядро не определено")
        }
    }

    suspend fun backupCoreConfigs(): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val backupDir = "/opt/etc/xkeen/android-backups/$timestamp"
        ssh.exec(
            "mkdir -p \"$backupDir\"; " +
                "if [ -d ${Paths.CONFIGS_DIR} ]; then mkdir -p \"$backupDir/xray\"; cp -a ${Paths.CONFIGS_DIR}/*.json \"$backupDir/xray/\" 2>/dev/null; fi; " +
                "if [ -d /opt/etc/mihomo ]; then mkdir -p \"$backupDir/mihomo\"; cp -a /opt/etc/mihomo/. \"$backupDir/mihomo/\" 2>/dev/null; fi; " +
                "if [ -f /opt/etc/ndm/netfilter.d/xkeen-quic-reject.sh ]; then mkdir -p \"$backupDir/netfilter.d\"; cp -a /opt/etc/ndm/netfilter.d/xkeen-quic-reject.sh \"$backupDir/netfilter.d/\"; fi; " +
                "echo \"$backupDir\""
        )
        return backupDir
    }

    suspend fun installOrUpdateMihomo(): CoreSwitchResult {
        val backupId = backupCoreConfigs()
        val out = cleanTerminalOutput(
            ssh.exec("printf '1\\n' | xkeen -um 2>&1", timeout = 240000).stdout
        )
        delay(1000)
        val state = getCoreState(withConfigTests = false)
        return if (state.mihomoInstalled) {
            val configPath = findMihomoConfigPath()
            if (configPath.isBlank()) {
                val generated = generateMihomoConfigFromXray()
                CoreSwitchResult(
                    ok = generated.ok,
                    message = "Mihomo установлен. ${generated.message}",
                    backupId = generated.backupId.ifBlank { backupId },
                    rolledBack = generated.rolledBack,
                    detail = (out.takeLast(600) + "\n\n" + cleanTerminalOutput(generated.detail).takeLast(800)).trim()
                )
            } else {
                CoreSwitchResult(true, "Mihomo установлен", backupId, detail = out.takeLast(1000))
            }
        } else {
            CoreSwitchResult(false, "Mihomo не найден после установки", backupId, detail = out.takeLast(1000))
        }
    }

    suspend fun generateMihomoConfigFromXray(): CoreSwitchResult {
        val backupId = backupCoreConfigs()
        val generated = try {
            MihomoConfigRemote(ssh).writeFromXrayConfig()
        } catch (e: Exception) {
            return CoreSwitchResult(
                ok = false,
                message = "Не удалось собрать конфиг Mihomo",
                backupId = backupId,
                detail = e.message.orEmpty()
            )
        }

        if (!generated.first) {
            return CoreSwitchResult(false, generated.second, backupId)
        }

        val state = getCoreState(withConfigTests = false)
        if (!state.mihomoInstalled) {
            return CoreSwitchResult(
                ok = true,
                message = "Конфиг Mihomo собран; установите Mihomo, чтобы проверить",
                backupId = backupId,
                detail = generated.second
            )
        }

        val test = testCoreConfig(ProxyCore.MIHOMO)
        if (test.ok) {
            var msg = "Конфиг Mihomo собран и проверен"
            if (state.mihomoRunning) {
                val path = findMihomoConfigPath()
                val reloaded = path.isNotBlank() &&
                    try { MihomoApi(ssh).reloadConfig(path) } catch (_: Exception) { false }
                msg += if (reloaded) "; применён на лету" else "; перезапустите прокси, чтобы применить"
            }
            return CoreSwitchResult(true, msg, backupId, detail = test.output.takeLast(800))
        }

        restoreMihomoConfigFromBackup(backupId)
        return CoreSwitchResult(
            ok = false,
            message = "Сгенерированный конфиг Mihomo не прошел проверку",
            backupId = backupId,
            rolledBack = true,
            detail = test.output.takeLast(1200)
        )
    }

    private suspend fun restoreMihomoConfigFromBackup(backupDir: String) {
        ssh.exec(
            "if [ -d \"$backupDir/mihomo\" ]; then " +
                "mkdir -p ${Paths.MIHOMO_DIR}; cp -a \"$backupDir/mihomo/.\" ${Paths.MIHOMO_DIR}/; " +
            "else rm -f ${Paths.MIHOMO_CONFIG}; fi"
        )
    }

    suspend fun switchCore(target: ProxyCore): CoreSwitchResult {
        if (target == ProxyCore.UNKNOWN) {
            return CoreSwitchResult(false, "Целевое ядро не определено")
        }

        val before = getCoreState(withConfigTests = false)
        val backupId = backupCoreConfigs()

        val targetInstalled = when (target) {
            ProxyCore.XRAY -> before.xrayInstalled
            ProxyCore.MIHOMO -> before.mihomoInstalled
            ProxyCore.UNKNOWN -> false
        }
        if (!targetInstalled) {
            return CoreSwitchResult(false, "${target.title} не установлен", backupId)
        }
        if (target == ProxyCore.MIHOMO) {
            val yqInstalled = ssh.exec("command -v yq 2>/dev/null || true").stdout.trim().isNotEmpty()
            if (!yqInstalled) {
                return CoreSwitchResult(false, "Для Mihomo не найден yq. Повторите установку Mihomo", backupId)
            }
        }

        val configTest = testCoreConfig(target)
        if (!configTest.ok) {
            return CoreSwitchResult(
                ok = false,
                message = "Конфиг ${target.title} не прошел проверку",
                backupId = backupId,
                detail = configTest.output.takeLast(1200)
            )
        }

        val switchCommand = when (target) {
            ProxyCore.XRAY -> "xkeen -xray"
            ProxyCore.MIHOMO -> "xkeen -mihomo"
            ProxyCore.UNKNOWN -> ""
        }
        val switchOut = ssh.exec("$switchCommand 2>&1", timeout = 90000).stdout
        val startOut = startXkeenQuietly()
        delay(1500)

        val after = waitForCore(target)
        val targetRunning = when (target) {
            ProxyCore.XRAY -> after.xrayRunning
            ProxyCore.MIHOMO -> after.mihomoRunning
            ProxyCore.UNKNOWN -> false
        }
        if (after.activeCore == target && targetRunning) {
            return CoreSwitchResult(
                ok = true,
                message = "Переключено на ${target.title}",
                backupId = backupId,
                detail = (switchOut + "\n--- start ---\n" + startOut).takeLast(1600)
            )
        }

        val rollbackCore = if (before.activeCore != ProxyCore.UNKNOWN) before.activeCore else ProxyCore.XRAY
        val stopOut = stopXkeenQuietly()
        delay(1500)
        val rollbackCommand = when (rollbackCore) {
            ProxyCore.XRAY -> "xkeen -xray"
            ProxyCore.MIHOMO -> "xkeen -mihomo"
            ProxyCore.UNKNOWN -> "xkeen -xray"
        }
        val rollbackOut = ssh.exec("$rollbackCommand 2>&1", timeout = 90000).stdout
        delay(1500)
        return CoreSwitchResult(
            ok = false,
            message = "Переключение не удалось; прокси остановлен, ядро возвращено на ${rollbackCore.title}",
            backupId = backupId,
            rolledBack = true,
            detail = (
                switchOut +
                    "\n--- start ---\n" + startOut +
                    "\n--- stop for direct internet ---\n" + stopOut +
                    "\n--- rollback core ---\n" + rollbackOut
            ).takeLast(2200)
        )
    }

    suspend fun getObservatoryState(): ObservatoryState {
        val out = ssh.exec(
            "echo '===ERROR==='; tail -50 ${Paths.LOG_DIR}/error.log 2>/dev/null; " +
            "echo '===ACCESS==='; tail -1000 ${Paths.LOG_DIR}/access.log 2>/dev/null"
        ).stdout

        var errorPart = ""
        var accessPart = ""
        if ("===ACCESS===" in out) {
            val parts = out.split("===ACCESS===", limit = 2)
            errorPart = parts[0].replace("===ERROR===", "")
            accessPart = parts[1]
        } else if ("===ERROR===" in out) {
            errorPart = out.replace("===ERROR===", "")
        }

        // Router time
        val nowOut = ssh.exec("date +%Y/%m/%d\\ %H:%M:%S").stdout.trim()
        val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US)
        val now = try { sdf.parse(nowOut) } catch (_: Exception) { null } ?: Date()
        val cutoff = Date(now.time - 5 * 60 * 1000)

        // Failed proxies
        val failed = mutableSetOf<String>()
        val pingRegex = Regex("""with (proxy-\S+):""")
        val tsRegex = Regex("""^(\d{4}/\d{2}/\d{2} \d{2}:\d{2}:\d{2})""")
        for (line in errorPart.lines()) {
            if ("error ping" in line) {
                val match = pingRegex.find(line)
                val tsMatch = tsRegex.find(line)
                if (match != null && tsMatch != null) {
                    val ts = try { sdf.parse(tsMatch.groupValues[1]) } catch (_: Exception) { null }
                    if (ts != null && ts.after(cutoff)) {
                        failed.add(match.groupValues[1])
                    }
                }
            }
        }

        // Usage
        val usage = mutableMapOf<String, Int>()
        val proxyRegex = Regex("""(proxy-[a-z0-9]+)""")
        for (line in accessPart.lines()) {
            val m = proxyRegex.find(line)
            if (m != null) {
                val tag = m.groupValues[1]
                usage[tag] = (usage[tag] ?: 0) + 1
            }
        }

        val selected = usage.maxByOrNull { it.value }?.key

        return ObservatoryState(
            failedProxies = failed.toList(),
            usage = usage,
            selected = selected
        )
    }

    suspend fun getLog(logType: String, lines: Int = 200): String {
        val path = "${Paths.LOG_DIR}/$logType.log"
        return ssh.exec("tail -$lines $path 2>/dev/null").stdout
    }

    suspend fun testConfig(): ConfigTestResult {
        val out = ssh.exec(
            "XRAY_LOCATION_ASSET=/opt/etc/xray/dat " +
            "XRAY_LOCATION_CONFDIR=/opt/etc/xray/configs " +
            "xray run -test -confdir /opt/etc/xray/configs/ 2>&1",
            timeout = 20000
        ).stdout
        return ConfigTestResult(
            ok = "Configuration OK" in out,
            output = out
        )
    }

    suspend fun restartXkeen(): Pair<Boolean, String> {
        // Routing UI edits Xray rules. Update only Mihomo rules so independently added
        // servers, groups, DNS and controller settings survive subsequent routing changes.
        val core = try { getCoreState().activeCore } catch (_: Exception) { ProxyCore.UNKNOWN }
        if (core == ProxyCore.MIHOMO) {
            return MihomoConfigRemote(ssh).applyRoutingFromXray()
        }

        try {
            ssh.exec("/opt/etc/init.d/S99xkeen restart 2>&1", timeout = 30000)
        } catch (_: Exception) { }

        delay(4000)
        val state = getCoreState()
        val running = state.activeRunning
        val detail = when (state.activeCore) {
            ProxyCore.XRAY -> "xray ${state.xrayPid} ${state.xrayMem}".trim()
            ProxyCore.MIHOMO -> "mihomo ${state.mihomoPid} ${state.mihomoMem}".trim()
            ProxyCore.UNKNOWN -> state.statusText
        }
        return Pair(running, detail)
    }

    // ========== QUIC fast-reject via iptables ==========
    // Replaces xray blackhole (silent drop causing ~7s browser timeout)
    // with ICMP port-unreachable (instant TCP fallback).

    private val quicHookPath = "/opt/etc/ndm/netfilter.d/xkeen-quic-reject.sh"
    private val quicHookScript = """#!/bin/sh
# Fast-reject QUIC (UDP 443) with ICMP port-unreachable.
# Managed by XKeen Android app.
#
# Strategy: ACCEPT in mangle PREROUTING at position 1, BEFORE xkeen sub-chain.
# Packet bypasses xkeen TPROXY, continues to filter FORWARD where REJECT
# sends ICMP port-unreachable -> browser instantly falls back to TCP.
# Both rules live in parent chains, so xkeen -restart does NOT erase them.

if ! iptables -t mangle -C PREROUTING -p udp --dport 443 -j ACCEPT 2>/dev/null; then
    iptables -t mangle -I PREROUTING 1 -p udp --dport 443 -j ACCEPT
fi

if ! iptables -C FORWARD -p udp --dport 443 -j REJECT --reject-with icmp-port-unreachable 2>/dev/null; then
    iptables -I FORWARD 1 -p udp --dport 443 -j REJECT --reject-with icmp-port-unreachable
fi
"""

    suspend fun isQuicRejectEnabled(): Boolean {
        val ruleActive = ssh.exec(
            "iptables -C FORWARD -p udp --dport 443 -j REJECT --reject-with icmp-port-unreachable 2>/dev/null && echo yes"
        ).stdout.trim() == "yes"
        return ruleActive
    }

    suspend fun applyQuicReject(enable: Boolean): Pair<Boolean, String> {
        return try {
            if (enable) {
                ssh.writeFileB64(quicHookPath, quicHookScript)
                ssh.exec("chmod +x $quicHookPath && $quicHookPath")
                Pair(true, "QUIC fast-reject enabled")
            } else {
                ssh.exec(
                    "rm -f $quicHookPath; " +
                    // Clean up current and any legacy rule placements
                    "iptables -t mangle -D PREROUTING -p udp --dport 443 -j ACCEPT 2>/dev/null; " +
                    "iptables -t mangle -D xkeen -p udp --dport 443 -j RETURN 2>/dev/null; " +
                    "iptables -D FORWARD -p udp --dport 443 -j REJECT --reject-with icmp-port-unreachable 2>/dev/null; " +
                    "echo done"
                )
                Pair(true, "QUIC fast-reject disabled")
            }
        } catch (e: Exception) {
            Pair(false, e.message ?: "iptables error")
        }
    }

    // ========== My IP ==========

    suspend fun getExternalIp(): ExternalIpInfo {
        // Direct IP (through provider)
        val directIp = try {
            ssh.exec("curl -s --max-time 5 http://ifconfig.me 2>/dev/null").stdout.trim()
        } catch (_: Exception) { "?" }

        return ExternalIpInfo(directIp = directIp)
    }

    // ========== Diagnostics ==========

    suspend fun runDiagnostics(): DiagnosticReport {
        val checks = mutableListOf<DiagnosticCheck>()
        val coreState = getCoreState(withConfigTests = false)

        // 1. Active core process
        val activePid = when (coreState.activeCore) {
            ProxyCore.XRAY -> coreState.xrayPid
            ProxyCore.MIHOMO -> coreState.mihomoPid
            ProxyCore.UNKNOWN -> ""
        }
        val ps = if (coreState.activeRunning) activePid else ""
        checks.add(DiagnosticCheck(
            "${coreState.activeTitle} процесс",
            if (ps.isNotEmpty()) DiagStatus.OK else DiagStatus.FAIL,
            if (ps.isNotEmpty()) "PID: ${ps.split(Regex("\\s+")).firstOrNull()}" else "Не запущен"
        ))

        // 2. Config test
        val test = testCoreConfig(coreState.activeCore)
        checks.add(DiagnosticCheck(
            "Конфигурация",
            if (test.ok) DiagStatus.OK else DiagStatus.FAIL,
            if (test.ok) "Configuration OK" else test.output.lines().lastOrNull { it.isNotBlank() } ?: "Error"
        ))

        // 3. Proxy health — count from config, not logs
        val proxyTags = try {
            val raw = ssh.readFile(Paths.OUTBOUNDS)
            val arr = kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonObject["outbounds"]?.jsonArray
            arr?.mapNotNull { it.jsonObject["tag"]?.jsonPrimitive?.content }
                ?.filter { it.startsWith("proxy-") } ?: emptyList()
        } catch (_: Exception) { emptyList() }

        val obs = try { getObservatoryState() } catch (_: Exception) { ObservatoryState() }
        if (coreState.activeCore == ProxyCore.XRAY && proxyTags.isNotEmpty()) {
            val totalProxies = proxyTags.size
            val failedCount = obs.failedProxies.size
            checks.add(DiagnosticCheck(
                "Прокси-серверы",
                when {
                    failedCount == 0 -> DiagStatus.OK
                    failedCount < totalProxies -> DiagStatus.WARN
                    else -> DiagStatus.FAIL
                },
                when {
                    failedCount == 0 -> "Все $totalProxies работают: ${proxyTags.joinToString(", ")}"
                    else -> "$failedCount из $totalProxies с ошибками: ${obs.failedProxies.joinToString(", ")}"
                }
            ))
        } else if (coreState.activeCore == ProxyCore.MIHOMO) {
            checks.add(DiagnosticCheck(
                "Прокси-серверы",
                DiagStatus.WARN,
                "Проверка серверов выполняется самим Mihomo"
            ))
        }

        // 4. DNS check
        val dns = ssh.exec("nslookup google.com 2>&1 | head -5").stdout
        val dnsOk = dns.contains("Address") && !dns.contains("SERVFAIL") && !dns.contains("NXDOMAIN")
        checks.add(DiagnosticCheck(
            "DNS резолвинг",
            if (dnsOk) DiagStatus.OK else DiagStatus.FAIL,
            if (dnsOk) "google.com → OK" else "DNS не работает"
        ))

        // 5. DNS hijacking check
        val igDns = ssh.exec("nslookup scontent-arn2-1.cdninstagram.com 2>&1").stdout
        val igIpMatch = Regex("""Address \d+: (\d+\.\d+\.\d+\.\d+)""").findAll(igDns)
        val igIps = igIpMatch.map { it.groupValues[1] }.filter { !it.startsWith("127.") }.toList()
        val hijacked = igIps.any { ip ->
            // Known Russian ISP hijack ranges
            ip.startsWith("188.186.") || ip.startsWith("95.167.") || ip.startsWith("212.188.")
        }
        if (igIps.isNotEmpty()) {
            checks.add(DiagnosticCheck(
                "DNS подмена (DPI)",
                if (hijacked) DiagStatus.WARN else DiagStatus.OK,
                if (hijacked) "Instagram CDN → ${igIps.first()} (похоже на подмену провайдером). Рекомендуется DNS-over-TLS"
                else "Instagram CDN → ${igIps.first()} (OK)"
            ))
        }

        // 6. Internet connectivity
        val ping = ssh.exec("ping -c 1 -W 3 8.8.8.8 2>&1").stdout
        val pingOk = ping.contains("1 packets received") || ping.contains("bytes from")
        checks.add(DiagnosticCheck(
            "Интернет",
            if (pingOk) DiagStatus.OK else DiagStatus.FAIL,
            if (pingOk) "8.8.8.8 доступен" else "Нет связи"
        ))

        // 7. Geosite files
        val datFiles = ssh.exec("ls /opt/etc/xray/dat/*.dat 2>/dev/null | wc -l").stdout.trim()
        val datCount = datFiles.toIntOrNull() ?: 0
        checks.add(DiagnosticCheck(
            "Geosite/GeoIP базы",
            if (datCount >= 4) DiagStatus.OK else DiagStatus.WARN,
            "$datCount файлов в /opt/etc/xray/dat/"
        ))

        return DiagnosticReport(checks)
    }

    // ========== Network devices (for IoT) ==========

    suspend fun getNetworkDevices(): List<NetworkDevice> {
        val arp = ssh.exec("cat /proc/net/arp").stdout
        val devices = mutableListOf<NetworkDevice>()
        for (line in arp.lines().drop(1)) { // skip header
            val cols = line.split(Regex("\\s+"))
            if (cols.size >= 6) {
                val ip = cols[0]
                val mac = cols[3]
                val iface = cols[5]
                if (mac != "00:00:00:00:00:00" && ip != "0.0.0.0") {
                    devices.add(NetworkDevice(ip = ip, mac = mac, iface = iface))
                }
            }
        }
        return devices.sortedBy { it.ip }
    }

    // ========== Geosite update ==========

    suspend fun updateGeoFiles(): Pair<Boolean, String> {
        val out = ssh.exec("xkeen -ug 2>&1", timeout = 120000).stdout
        val ok = !out.contains("error", ignoreCase = true) || out.contains("updated", ignoreCase = true)
        return Pair(ok, out)
    }

    // ========== Backup / Restore ==========

    suspend fun backupConfigs(): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val backupDir = "${Paths.CONFIGS_DIR}/backups/$timestamp"
        ssh.exec("mkdir -p $backupDir")
        ssh.exec("cp ${Paths.CONFIGS_DIR}/*.json $backupDir/")
        return timestamp
    }

    suspend fun listBackups(): List<String> {
        val out = ssh.exec("ls -1 ${Paths.CONFIGS_DIR}/backups/ 2>/dev/null").stdout.trim()
        if (out.isEmpty()) return emptyList()
        return out.lines().filter { it.isNotBlank() }.sortedDescending()
    }

    suspend fun restoreBackup(timestamp: String): Pair<Boolean, String> {
        val backupDir = "${Paths.CONFIGS_DIR}/backups/$timestamp"
        val check = ssh.exec("ls $backupDir/*.json 2>/dev/null").stdout.trim()
        if (check.isEmpty()) return Pair(false, "Backup not found")

        ssh.exec("cp $backupDir/*.json ${Paths.CONFIGS_DIR}/")
        val test = testConfig()
        return if (test.ok) {
            Pair(true, "Restored from $timestamp")
        } else {
            Pair(false, "Restored but config test failed: ${test.output.takeLast(200)}")
        }
    }

    suspend fun readAllConfigs(): Map<String, String> {
        val files = listOf("01_log.json", "02_dns.json", "03_inbounds.json",
            "04_outbounds.json", "05_routing.json", "06_policy.json", "07_observatory.json")
        return files.associateWith { f ->
            try { ssh.readFile("${Paths.CONFIGS_DIR}/$f") } catch (_: Exception) { "" }
        }
    }

    suspend fun writeAllConfigs(configs: Map<String, String>) {
        for ((file, content) in configs) {
            if (content.isNotBlank()) {
                ssh.writeFileB64("${Paths.CONFIGS_DIR}/$file", content)
            }
        }
    }
}

// ========== Data classes ==========

data class ExternalIpInfo(val directIp: String)

data class DiagnosticCheck(
    val name: String,
    val status: DiagStatus,
    val detail: String
)

enum class DiagStatus { OK, WARN, FAIL }

data class DiagnosticReport(val checks: List<DiagnosticCheck>) {
    val allOk get() = checks.all { it.status == DiagStatus.OK }
    val hasFailures get() = checks.any { it.status == DiagStatus.FAIL }
}

data class NetworkDevice(
    val ip: String,
    val mac: String,
    val iface: String
)
