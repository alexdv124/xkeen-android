package com.xkeen.android.data.remote

import com.xkeen.android.data.ssh.SshClient
import com.xkeen.android.domain.model.ConfigTestResult
import kotlinx.coroutines.delay
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.xkeen.android.domain.model.ObservatoryState
import com.xkeen.android.domain.model.RouterStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Paths {
    const val CONFIGS_DIR = "/opt/etc/xray/configs"
    const val LOG_DIR = "/opt/var/log/xray"
    const val OUTBOUNDS = "$CONFIGS_DIR/04_outbounds.json"
    const val ROUTING = "$CONFIGS_DIR/05_routing.json"
    const val OBSERVATORY = "$CONFIGS_DIR/07_observatory.json"
}

class RouterCommands(private val ssh: SshClient) {

    suspend fun getStatus(): RouterStatus {
        var status = RouterStatus()

        // Xray process
        val psOut = ssh.exec("ps | grep xray | grep -v grep").stdout.trim()
        if (psOut.isNotEmpty()) {
            val parts = psOut.split(Regex("\\s+"))
            status = status.copy(
                xrayRunning = true,
                xrayPid = parts.getOrElse(0) { "" },
                xrayMem = parts.getOrElse(2) { "" }
            )
        }

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
        try {
            ssh.exec("/opt/etc/init.d/S99xkeen restart 2>&1", timeout = 30000)
        } catch (_: Exception) { }

        delay(4000)
        val out = ssh.exec("ps | grep xray | grep -v grep").stdout.trim()
        return Pair(out.isNotEmpty(), out)
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

        // 1. Xray running?
        val ps = ssh.exec("ps | grep xray | grep -v grep").stdout.trim()
        checks.add(DiagnosticCheck(
            "Xray процесс",
            if (ps.isNotEmpty()) DiagStatus.OK else DiagStatus.FAIL,
            if (ps.isNotEmpty()) "PID: ${ps.split(Regex("\\s+")).firstOrNull()}" else "Не запущен"
        ))

        // 2. Config test
        val test = testConfig()
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
        if (proxyTags.isNotEmpty()) {
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
