package com.xkeen.android.data.remote

import com.xkeen.android.data.ssh.SshClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class MihomoConfigRemote(private val ssh: SshClient) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun writeFromXrayConfig(): Pair<Boolean, String> {
        val outboundsRaw = ssh.readFile(Paths.OUTBOUNDS)
        val routingRaw = try { ssh.readFile(Paths.ROUTING) } catch (_: Exception) { "{}" }

        val outboundsObj = json.parseToJsonElement(outboundsRaw).jsonObject
        val proxies = outboundsObj["outbounds"]
            ?.jsonArray
            ?.mapNotNull { convertOutbound(it.jsonObject) }
            ?: emptyList()

        if (proxies.isEmpty()) {
            return Pair(false, "В Xray-конфиге не найдено VLESS-серверов для Mihomo")
        }

        val yaml = buildConfig(proxies, routingRaw)
        ssh.exec("mkdir -p ${Paths.MIHOMO_DIR} /opt/var/log/mihomo")
        ssh.writeFileB64(Paths.MIHOMO_CONFIG, yaml)
        return Pair(true, "Конфиг Mihomo создан: ${Paths.MIHOMO_CONFIG}")
    }

    private fun convertOutbound(outbound: JsonObject): MihomoProxy? {
        val tag = outbound.string("tag") ?: return null
        if (!tag.startsWith("proxy-")) return null
        if (outbound.string("protocol") != "vless") return null

        val vnext = outbound.obj("settings")
            ?.array("vnext")
            ?.firstOrNull()
            ?.jsonObject
            ?: return null
        val user = vnext.array("users")?.firstOrNull()?.jsonObject ?: return null
        val stream = outbound.obj("streamSettings") ?: JsonObject(emptyMap())
        val network = stream.string("network") ?: "tcp"
        val security = stream.string("security") ?: "none"
        val tlsLike = security == "tls" || security == "reality"

        val lines = mutableListOf<String>()
        lines += "  - name: ${yamlString(tag)}"
        lines += "    type: vless"
        lines += "    server: ${yamlString(vnext.string("address") ?: return null)}"
        lines += "    port: ${vnext.int("port") ?: 443}"
        lines += "    uuid: ${yamlString(user.string("id") ?: return null)}"
        lines += "    udp: true"

        user.string("flow")?.takeIf { it.isNotBlank() }?.let {
            lines += "    flow: ${yamlString(it)}"
            lines += "    packet-encoding: xudp"
        }

        if (tlsLike) {
            lines += "    tls: true"
            val tls = if (security == "tls") stream.obj("tlsSettings") else stream.obj("realitySettings")
            tls?.string("serverName")?.takeIf { it.isNotBlank() }?.let {
                lines += "    servername: ${yamlString(it)}"
            }
            tls?.string("fingerprint")?.takeIf { it.isNotBlank() }?.let {
                lines += "    client-fingerprint: ${yamlString(it)}"
            }
            tls?.array("alpn")?.mapNotNull { it.jsonPrimitive.contentOrNull }?.takeIf { it.isNotEmpty() }?.let { alpn ->
                lines += "    alpn:"
                alpn.forEach { lines += "      - ${yamlString(it)}" }
            }
            tls?.bool("allowInsecure")?.takeIf { it }?.let {
                lines += "    skip-cert-verify: true"
            }
            if (security == "reality") {
                lines += "    reality-opts:"
                lines += "      public-key: ${yamlString(tls?.string("publicKey").orEmpty())}"
                tls?.string("shortId")?.takeIf { it.isNotBlank() }?.let {
                    lines += "      short-id: ${yamlString(it)}"
                }
            }
        }

        lines += "    network: ${yamlString(network)}"
        if (network == "xhttp") {
            appendXhttpOptions(lines, stream.obj("xhttpSettings"))
        }

        return MihomoProxy(tag, lines)
    }

    private fun appendXhttpOptions(lines: MutableList<String>, xhttp: JsonObject?) {
        if (xhttp == null) return
        lines += "    xhttp-opts:"
        xhttp.string("path")?.let { lines += "      path: ${yamlString(it)}" }
        xhttp.string("host")?.let { lines += "      host: ${yamlString(it)}" }
        xhttp.string("mode")?.let { lines += "      mode: ${yamlString(it)}" }

        val extra = xhttp.obj("extra")
        extra?.bool("noGRPCHeader")?.let { lines += "      no-grpc-header: $it" }
        extra?.string("xPaddingBytes")?.let { lines += "      x-padding-bytes: ${yamlScalar(it)}" }
        extra?.element("scMaxEachPostBytes")?.let { lines += "      sc-max-each-post-bytes: ${yamlScalar(it)}" }
        extra?.element("scMinPostsIntervalMs")?.let { lines += "      sc-min-posts-interval-ms: ${yamlScalar(it)}" }

        val xmux = extra?.obj("xmux")
        if (xmux != null && xmux.isNotEmpty()) {
            lines += "      reuse-settings:"
            appendMapped(lines, xmux, 8, "maxConcurrency", "max-concurrency")
            appendMapped(lines, xmux, 8, "maxConnections", "max-connections")
            appendMapped(lines, xmux, 8, "cMaxReuseTimes", "c-max-reuse-times")
            appendMapped(lines, xmux, 8, "hMaxRequestTimes", "h-max-request-times")
            appendMapped(lines, xmux, 8, "hMaxReusableSecs", "h-max-reusable-secs")
            appendMapped(lines, xmux, 8, "hKeepAlivePeriod", "h-keep-alive-period")
        }

        val downloadSettings = extra?.obj("downloadSettings")
        if (downloadSettings != null && downloadSettings.isNotEmpty()) {
            lines += "      download-settings:"
            appendJsonObject(lines, downloadSettings, 8)
        }
    }

    private fun buildConfig(proxies: List<MihomoProxy>, routingRaw: String): String {
        val rules = buildRules(routingRaw)
        return buildString {
            appendLine("# Generated by XKeen Android app from Xray config.")
            // TCP goes through redir-port (REDIRECT/NAT); UDP through tproxy-port. xkeen's
            // S99xkeen reads redir-port and sets up NAT REDIRECT for TCP — same as Xray mode.
            // Without redir-port it falls back to TPROXY for TCP+UDP, whose established-connection
            // delivery is broken on Keenetic (TLS ClientHello never forwarded to the core).
            appendLine("redir-port: 4000")
            appendLine("tproxy-port: 4001")
            appendLine("allow-lan: true")
            appendLine("bind-address: \"*\"")
            appendLine("mode: rule")
            appendLine("log-level: warning")
            appendLine("ipv6: false")
            appendLine("tcp-concurrent: true")
            appendLine("unified-delay: true")
            appendLine("external-controller: 127.0.0.1:9090")
            appendLine("profile:")
            appendLine("  store-selected: true")
            appendLine("sniffer:")
            appendLine("  enable: true")
            appendLine("  parse-pure-ip: true")
            appendLine("  force-dns-mapping: true")
            appendLine("  sniff:")
            appendLine("    HTTP:")
            appendLine("      ports: [80, 8080-8880]")
            appendLine("    TLS:")
            appendLine("      ports: [443, 8443]")
            appendLine("    QUIC:")
            appendLine("      ports: [443, 8443]")
            appendLine("dns:")
            appendLine("  enable: true")
            appendLine("  ipv6: false")
            appendLine("  listen: 127.0.0.1:1053")
            appendLine("  enhanced-mode: redir-host")
            appendLine("  use-hosts: true")
            appendLine("  default-nameserver:")
            appendLine("    - 1.1.1.1")
            appendLine("    - 8.8.8.8")
            appendLine("  nameserver:")
            appendLine("    - https://1.1.1.1/dns-query")
            appendLine("    - https://8.8.8.8/dns-query")
            appendLine("proxies:")
            proxies.forEach { proxy ->
                proxy.lines.forEach { appendLine(it) }
            }
            appendLine("proxy-groups:")
            // PROXY: the selector the user (or the app via the REST API) picks from.
            // First member AUTO is the url-test group, so "auto by latency" is the default.
            appendLine("  - name: PROXY")
            appendLine("    type: select")
            appendLine("    proxies:")
            appendLine("      - AUTO")
            proxies.forEach { appendLine("      - ${yamlString(it.name)}") }
            // AUTO: lowest-latency pick across all servers.
            appendLine("  - name: AUTO")
            appendLine("    type: url-test")
            appendLine("    proxies:")
            proxies.forEach { appendLine("      - ${yamlString(it.name)}") }
            appendLine("    url: http://cp.cloudflare.com/generate_204")
            appendLine("    interval: 60")
            appendLine("    tolerance: 80")
            appendLine("rules:")
            rules.forEach { appendLine("  - $it") }
            if (rules.none { it.startsWith("MATCH,") }) {
                appendLine("  - MATCH,PROXY")
            }
        }
    }

    private fun buildRules(routingRaw: String): List<String> {
        val result = linkedSetOf<String>()
        val rules = runCatching {
            json.parseToJsonElement(routingRaw)
                .jsonObject["routing"]
                ?.jsonObject
                ?.get("rules")
                ?.jsonArray
        }.getOrNull() ?: return listOf("MATCH,PROXY")

        for (ruleElement in rules) {
            val rule = ruleElement.jsonObject
            val target = mihomoTarget(rule)
            val network = rule.string("network").orEmpty()
            val port = rule.string("port").orEmpty()

            if (target == "REJECT" && network.contains("udp", ignoreCase = true) && portContains(port, "443")) {
                result += "AND,((NETWORK,UDP),(DST-PORT,443)),REJECT"
                continue
            }

            if (!rule.hasArray("source") && !rule.hasArray("domain") && !rule.hasArray("ip") &&
                port.isBlank() && network.isNotBlank()) {
                result += "MATCH,$target"
                continue
            }

            rule.array("source")?.mapNotNull { it.jsonPrimitive.contentOrNull }?.forEach { source ->
                result += "SRC-IP-CIDR,${cidrOrHost(source)},$target"
            }
            rule.array("domain")?.mapNotNull { it.jsonPrimitive.contentOrNull }?.forEach { domain ->
                domainRule(domain, target)?.let { result += it }
            }
            rule.array("ip")?.mapNotNull { it.jsonPrimitive.contentOrNull }?.forEach { ip ->
                ipRules(ip, target).forEach { result += it }
            }
        }

        return result.toList().ifEmpty { listOf("MATCH,PROXY") }
    }

    private fun mihomoTarget(rule: JsonObject): String {
        // All proxied traffic flows through the PROXY selector, so a live server switch
        // (PUT /proxies/PROXY) takes effect for every rule at once instead of pinning
        // individual rules to a specific node.
        val outbound = rule.string("outboundTag")
        return when {
            outbound == "direct" -> "DIRECT"
            outbound == "block" -> "REJECT"
            else -> "PROXY"
        }
    }

    private fun domainRule(value: String, target: String): String? {
        return when {
            value.startsWith("ext:geosite_v2fly.dat:") ->
                "GEOSITE,${value.substringAfterLast(":")},$target"
            value.startsWith("geosite:") ->
                "GEOSITE,${value.removePrefix("geosite:")},$target"
            value.startsWith("domain:") ->
                "DOMAIN-SUFFIX,${value.removePrefix("domain:")},$target"
            value.startsWith("full:") ->
                "DOMAIN,${value.removePrefix("full:")},$target"
            value.startsWith("regexp:") ->
                "DOMAIN-REGEX,${value.removePrefix("regexp:")},$target"
            value.isNotBlank() ->
                "DOMAIN-SUFFIX,$value,$target"
            else -> null
        }
    }

    private fun ipRules(value: String, target: String): List<String> {
        return when {
            value == "ext:geoip_v2fly.dat:private" || value == "geoip:private" ->
                listOf(
                    "IP-CIDR,10.0.0.0/8,$target,no-resolve",
                    "IP-CIDR,172.16.0.0/12,$target,no-resolve",
                    "IP-CIDR,192.168.0.0/16,$target,no-resolve",
                    "IP-CIDR,127.0.0.0/8,$target,no-resolve",
                    "IP-CIDR,169.254.0.0/16,$target,no-resolve",
                    "IP-CIDR6,fc00::/7,$target,no-resolve"
                )
            value.startsWith("ext:geoip_v2fly.dat:") ->
                listOf("GEOIP,${value.substringAfterLast(":").uppercase()},$target,no-resolve")
            value.startsWith("geoip:") ->
                listOf("GEOIP,${value.removePrefix("geoip:").uppercase()},$target,no-resolve")
            value.contains(":") ->
                listOf("IP-CIDR6,$value,$target,no-resolve")
            value.isNotBlank() ->
                listOf("IP-CIDR,$value,$target,no-resolve")
            else -> emptyList()
        }
    }

    private fun appendMapped(lines: MutableList<String>, obj: JsonObject, indent: Int, xrayKey: String, mihomoKey: String) {
        obj.element(xrayKey)?.let { lines += "${" ".repeat(indent)}$mihomoKey: ${yamlScalar(it)}" }
    }

    private fun appendJsonObject(lines: MutableList<String>, obj: JsonObject, indent: Int) {
        for ((key, value) in obj) {
            val yamlKey = camelToKebab(key)
            when (value) {
                is JsonObject -> {
                    lines += "${" ".repeat(indent)}$yamlKey:"
                    appendJsonObject(lines, value, indent + 2)
                }
                is JsonArray -> {
                    lines += "${" ".repeat(indent)}$yamlKey:"
                    value.forEach { item ->
                        lines += "${" ".repeat(indent + 2)}- ${yamlScalar(item)}"
                    }
                }
                else -> lines += "${" ".repeat(indent)}$yamlKey: ${yamlScalar(value)}"
            }
        }
    }

    private fun camelToKebab(value: String): String {
        return value.replace(Regex("([a-z0-9])([A-Z])"), "\$1-\$2").lowercase()
    }

    private fun portContains(port: String, wanted: String): Boolean {
        return port.split(",").any { part ->
            val trimmed = part.trim()
            trimmed == wanted || trimmed.split("-").let {
                if (it.size != 2) false
                else {
                    val start = it[0].toIntOrNull()
                    val end = it[1].toIntOrNull()
                    val target = wanted.toIntOrNull()
                    start != null && end != null && target != null && target in start..end
                }
            }
        }
    }

    private fun cidrOrHost(value: String): String {
        if ("/" in value) return value
        return if (":" in value) "$value/128" else "$value/32"
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key]?.let { it as? JsonObject }
    private fun JsonObject.array(key: String): JsonArray? = this[key]?.let { it as? JsonArray }
    private fun JsonObject.hasArray(key: String): Boolean = array(key)?.isNotEmpty() == true
    private fun JsonObject.element(key: String): JsonElement? = this[key]
    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
    private fun JsonObject.bool(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull

    private fun yamlScalar(element: JsonElement): String {
        return when (element) {
            JsonNull -> "null"
            is JsonPrimitive -> when {
                element.isString -> yamlScalar(element.content)
                element.booleanOrNull != null || element.intOrNull != null -> element.content
                else -> yamlScalar(element.content)
            }
            else -> yamlString(element.toString())
        }
    }

    private fun yamlScalar(value: String): String = yamlString(value)

    private fun yamlString(value: String): String {
        val escaped = buildString {
            value.forEach { ch ->
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(ch)
                }
            }
        }
        return "\"$escaped\""
    }
}

private data class MihomoProxy(
    val name: String,
    val lines: List<String>
)
