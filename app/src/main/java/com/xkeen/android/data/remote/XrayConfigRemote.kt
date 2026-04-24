package com.xkeen.android.data.remote

import com.xkeen.android.data.ssh.SshClient
import com.xkeen.android.domain.model.ConfigState
import com.xkeen.android.domain.model.CustomRoute
import com.xkeen.android.domain.model.ProxyInfo
import com.xkeen.android.domain.model.RoutingConfig
import com.xkeen.android.domain.model.RoutingMode
import com.xkeen.android.domain.model.RoutingPreset
import com.xkeen.android.domain.model.SetupState
import kotlinx.serialization.json.*

class XrayConfigRemote(private val ssh: SshClient) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    suspend fun getProxyList(): List<ProxyInfo> {
        val raw = ssh.readFile(Paths.OUTBOUNDS)
        val config = Json.parseToJsonElement(raw).jsonObject
        val outbounds = config["outbounds"]?.jsonArray ?: return emptyList()

        return outbounds.mapNotNull { ob ->
            val obj = ob.jsonObject
            val tag = obj["tag"]?.jsonPrimitive?.content ?: return@mapNotNull null
            if (!tag.startsWith("proxy-")) return@mapNotNull null

            val vnext = obj["settings"]?.jsonObject
                ?.get("vnext")?.jsonArray?.firstOrNull()?.jsonObject
            val user = vnext?.get("users")?.jsonArray?.firstOrNull()?.jsonObject
            val stream = obj["streamSettings"]?.jsonObject
            val transport = stream?.get("network")?.jsonPrimitive?.content ?: "tcp"
            val flow = user?.get("flow")?.jsonPrimitive?.content ?: ""

            ProxyInfo(
                tag = tag,
                address = vnext?.get("address")?.jsonPrimitive?.content ?: "",
                port = vnext?.get("port")?.jsonPrimitive?.int ?: 443,
                transport = when {
                    transport == "xhttp" -> "xhttp"
                    flow.isNotEmpty() -> "vision"
                    else -> "tcp"
                },
                sni = stream?.get("realitySettings")?.jsonObject
                    ?.get("serverName")?.jsonPrimitive?.content ?: ""
            )
        }
    }

    suspend fun addOutbound(outbound: JsonObject): Pair<Boolean, String> {
        val raw = ssh.readFile(Paths.OUTBOUNDS)
        val config = Json.parseToJsonElement(raw).jsonObject.toMutableMap()
        val outbounds = config["outbounds"]?.jsonArray?.toMutableList()
            ?: return Pair(false, "No outbounds array")

        val tag = outbound["tag"]?.jsonPrimitive?.content ?: return Pair(false, "No tag")
        if (outbounds.any { it.jsonObject["tag"]?.jsonPrimitive?.content == tag }) {
            return Pair(false, "Tag $tag already exists")
        }

        // Insert before direct/block
        var insertIdx = outbounds.size
        for ((i, o) in outbounds.withIndex()) {
            val t = o.jsonObject["tag"]?.jsonPrimitive?.content
            if (t == "direct" || t == "block") {
                insertIdx = i
                break
            }
        }
        outbounds.add(insertIdx, outbound)

        config["outbounds"] = JsonArray(outbounds)
        val content = Json { prettyPrint = true }.encodeToString(
            JsonObject.serializer(), JsonObject(config)
        )
        ssh.writeFileB64(Paths.OUTBOUNDS, content)
        return Pair(true, "OK")
    }

    suspend fun removeOutbound(tag: String): Pair<Boolean, String> {
        if (!tag.startsWith("proxy-")) return Pair(false, "Can only remove proxy outbounds")

        val raw = ssh.readFile(Paths.OUTBOUNDS)
        val config = Json.parseToJsonElement(raw).jsonObject.toMutableMap()
        val outbounds = config["outbounds"]?.jsonArray?.toMutableList()
            ?: return Pair(false, "No outbounds")

        val originalSize = outbounds.size
        outbounds.removeAll { it.jsonObject["tag"]?.jsonPrimitive?.content == tag }
        if (outbounds.size == originalSize) return Pair(false, "Tag $tag not found")

        config["outbounds"] = JsonArray(outbounds)
        val content = Json { prettyPrint = true }.encodeToString(
            JsonObject.serializer(), JsonObject(config)
        )
        ssh.writeFileB64(Paths.OUTBOUNDS, content)
        return Pair(true, "OK")
    }

    suspend fun getRoutingMode(): RoutingMode {
        val raw = ssh.readFile(Paths.ROUTING)
        val config = Json.parseToJsonElement(raw).jsonObject
        val rules = config["routing"]?.jsonObject?.get("rules")?.jsonArray
            ?: return RoutingMode.Auto

        if (rules.isEmpty()) return RoutingMode.Auto

        val lastRule = rules.last().jsonObject
        return when {
            lastRule.containsKey("balancerTag") -> RoutingMode.Auto
            lastRule["outboundTag"]?.jsonPrimitive?.content?.startsWith("proxy-") == true ->
                RoutingMode.Manual(lastRule["outboundTag"]?.jsonPrimitive?.content ?: "proxy-unknown")
            else -> RoutingMode.Auto
        }
    }

    suspend fun setRoutingMode(mode: RoutingMode): Pair<Boolean, String> {
        val raw = ssh.readFile(Paths.ROUTING)
        val config = Json.parseToJsonElement(raw).jsonObject.toMutableMap()
        val routing = config["routing"]?.jsonObject?.toMutableMap()
            ?: return Pair(false, "No routing")
        val rules = routing["rules"]?.jsonArray?.toMutableList()
            ?: return Pair(false, "No rules")

        if (rules.isEmpty()) return Pair(false, "No routing rules found")

        val lastRule = rules.last().jsonObject.toMutableMap()

        when (mode) {
            is RoutingMode.Auto -> {
                lastRule.remove("outboundTag")
                lastRule["balancerTag"] = JsonPrimitive("proxy-balancer")
            }
            is RoutingMode.Manual -> {
                if (!mode.tag.startsWith("proxy-")) return Pair(false, "Invalid proxy tag")
                lastRule.remove("balancerTag")
                lastRule["outboundTag"] = JsonPrimitive(mode.tag)
            }
        }

        rules[rules.lastIndex] = JsonObject(lastRule)
        routing["rules"] = JsonArray(rules)
        config["routing"] = JsonObject(routing)

        val content = Json { prettyPrint = true }.encodeToString(
            JsonObject.serializer(), JsonObject(config)
        )
        ssh.writeFileB64(Paths.ROUTING, content)
        return Pair(true, "OK")
    }

    suspend fun getBalancerTags(): List<String> {
        val raw = ssh.readFile(Paths.ROUTING)
        val config = Json.parseToJsonElement(raw).jsonObject
        val balancers = config["routing"]?.jsonObject?.get("balancers")?.jsonArray
            ?: return emptyList()

        for (b in balancers) {
            if (b.jsonObject["tag"]?.jsonPrimitive?.content == "proxy-balancer") {
                return b.jsonObject["selector"]?.jsonArray?.map {
                    it.jsonPrimitive.content
                } ?: emptyList()
            }
        }
        return emptyList()
    }

    /**
     * Detect current xkeen config state.
     * Returns: "single" (1 proxy, no balancer), "balancer" (has balancer), "empty" (no proxies)
     */
    suspend fun detectConfigState(): ConfigState {
        val proxies = getProxyList()
        if (proxies.isEmpty()) return ConfigState.Empty

        val raw = ssh.readFile(Paths.ROUTING)
        val config = Json.parseToJsonElement(raw).jsonObject
        val balancers = config["routing"]?.jsonObject?.get("balancers")?.jsonArray
        val hasBalancer = balancers?.any {
            it.jsonObject["tag"]?.jsonPrimitive?.content == "proxy-balancer"
        } ?: false

        // Check observatory
        val obsRaw = try { ssh.readFile(Paths.OBSERVATORY) } catch (_: Exception) { "{}" }
        val obsConfig = Json.parseToJsonElement(obsRaw).jsonObject
        val hasObservatory = obsConfig.containsKey("burstObservatory")

        return ConfigState(
            proxyCount = proxies.size,
            hasBalancer = hasBalancer,
            hasObservatory = hasObservatory,
            proxyTags = proxies.map { it.tag }
        )
    }

    /**
     * Create balancer + observatory + update routing for failover.
     * Called when user adds 2nd proxy to a single-server setup.
     */
    suspend fun enableFailover(proxyTags: List<String>): Pair<Boolean, String> {
        if (proxyTags.size < 2) return Pair(false, "Need at least 2 proxies")

        // 1. Create/update observatory (07_observatory.json)
        val observatory = buildJsonObject {
            putJsonObject("burstObservatory") {
                putJsonArray("subjectSelector") {
                    add(JsonPrimitive("proxy-"))
                }
                putJsonObject("pingConfig") {
                    put("destination", "http://cp.cloudflare.com/")
                    put("interval", "60s")
                    put("connectivity", "")
                    put("timeout", "10s")
                    put("sampling", 2)
                }
            }
        }
        val obsContent = Json { prettyPrint = true }.encodeToString(
            JsonObject.serializer(), observatory
        )
        ssh.writeFileB64(Paths.OBSERVATORY, obsContent)

        // 2. Add balancer to routing config
        val raw = ssh.readFile(Paths.ROUTING)
        val config = Json.parseToJsonElement(raw).jsonObject.toMutableMap()
        val routing = config["routing"]?.jsonObject?.toMutableMap()
            ?: return Pair(false, "No routing section")

        // Create balancer
        val balancer = buildJsonObject {
            put("tag", "proxy-balancer")
            putJsonArray("selector") {
                proxyTags.forEach { add(JsonPrimitive(it)) }
            }
            putJsonObject("strategy") {
                put("type", "leastping")
            }
            put("fallbackTag", "direct")
        }

        // Add or replace balancer
        val balancers = routing["balancers"]?.jsonArray?.toMutableList() ?: mutableListOf()
        val existingIdx = balancers.indexOfFirst {
            it.jsonObject["tag"]?.jsonPrimitive?.content == "proxy-balancer"
        }
        if (existingIdx >= 0) {
            balancers[existingIdx] = balancer
        } else {
            balancers.add(balancer)
        }
        routing["balancers"] = JsonArray(balancers)

        // 3. Update last routing rule to use balancerTag instead of outboundTag
        val rules = routing["rules"]?.jsonArray?.toMutableList()
            ?: return Pair(false, "No routing rules")

        if (rules.isNotEmpty()) {
            val lastRule = rules.last().jsonObject.toMutableMap()
            // Only update if it currently points to a single proxy
            if (lastRule.containsKey("outboundTag")) {
                val currentTag = lastRule["outboundTag"]?.jsonPrimitive?.content ?: ""
                if (currentTag.startsWith("proxy-") || currentTag == "direct") {
                    lastRule.remove("outboundTag")
                    lastRule["balancerTag"] = JsonPrimitive("proxy-balancer")
                }
            }
            rules[rules.lastIndex] = JsonObject(lastRule)
            routing["rules"] = JsonArray(rules)
        }

        config["routing"] = JsonObject(routing)

        val content = Json { prettyPrint = true }.encodeToString(
            JsonObject.serializer(), JsonObject(config)
        )
        ssh.writeFileB64(Paths.ROUTING, content)
        return Pair(true, "Failover enabled")
    }

    /**
     * Add proxy tag to existing balancer selector.
     */
    suspend fun addToBalancer(tag: String): Pair<Boolean, String> {
        val currentTags = getBalancerTags()
        if (tag in currentTags) return Pair(true, "Already in balancer")
        return setBalancerTags(currentTags + tag)
    }

    suspend fun setBalancerTags(tags: List<String>): Pair<Boolean, String> {
        if (tags.isEmpty()) return Pair(false, "At least one proxy must be enabled")

        val raw = ssh.readFile(Paths.ROUTING)
        val config = Json.parseToJsonElement(raw).jsonObject.toMutableMap()
        val routing = config["routing"]?.jsonObject?.toMutableMap()
            ?: return Pair(false, "No routing")
        val balancers = routing["balancers"]?.jsonArray?.toMutableList()
            ?: return Pair(false, "No balancers")

        var found = false
        for ((i, b) in balancers.withIndex()) {
            if (b.jsonObject["tag"]?.jsonPrimitive?.content == "proxy-balancer") {
                val updated = b.jsonObject.toMutableMap()
                updated["selector"] = JsonArray(tags.map { JsonPrimitive(it) })
                balancers[i] = JsonObject(updated)
                found = true
                break
            }
        }

        if (!found) return Pair(false, "Balancer not found")

        routing["balancers"] = JsonArray(balancers)
        config["routing"] = JsonObject(routing)

        val content = Json { prettyPrint = true }.encodeToString(
            JsonObject.serializer(), JsonObject(config)
        )
        ssh.writeFileB64(Paths.ROUTING, content)
        return Pair(true, "OK")
    }

    // ========== Routing Presets & Custom Routes ==========

    companion object {
        // Legacy — kept for backward compatibility. New installs should use aqaraEnabled flag.
        val AQARA_PRESET = listOf(
            CustomRoute("107.155.52.0/23", "proxy", "Aqara Cloud (CDN)"),
            CustomRoute("169.197.117.0/24", "proxy", "Aqara Cloud (MQTT)")
        )

        // Hub resolves these Kingsoft Cloud IPs via its internal DNS.
        val AQARA_IPS = listOf(
            "107.155.52.0/23",
            "169.197.117.0/24"
        )

        // Phone app resolves rpc-ru.aqara.com etc. to completely different IPs
        // outside AQARA_IPS — domain matching via TLS SNI handles both cases.
        val AQARA_DOMAINS = listOf(
            "domain:aqara.com",
            "domain:aqara.cn"
        )

        val YOUTUBE_DOMAINS = listOf(
            "domain:googlevideo.com",
            "domain:youtube.com",
            "domain:ytimg.com",
            "domain:youtu.be",
            "domain:ggpht.com"
        )

        private const val CUSTOM_RULE_TAG_PREFIX = "xkeen-custom-"
    }

    /**
     * Detect current routing preset from rules structure.
     */
    suspend fun getRoutingConfig(): RoutingConfig {
        val raw = ssh.readFile(Paths.ROUTING)
        val config = Json.parseToJsonElement(raw).jsonObject
        val routing = config["routing"]?.jsonObject ?: return RoutingConfig()
        val rules = routing["rules"]?.jsonArray ?: return RoutingConfig()

        // Detect preset
        val hasRuDomainRule = rules.any { rule ->
            val domains = rule.jsonObject["domain"]?.jsonArray
            domains?.any { it.jsonPrimitive.content.contains("category-ru") } == true &&
                rule.jsonObject["outboundTag"]?.jsonPrimitive?.content == "direct"
        }
        val hasRuIpRule = rules.any { rule ->
            val ips = rule.jsonObject["ip"]?.jsonArray
            ips?.any { it.jsonPrimitive.content.contains("geoip") && it.jsonPrimitive.content.contains("ru") } == true &&
                rule.jsonObject["outboundTag"]?.jsonPrimitive?.content == "direct"
        }

        val lastRule = rules.lastOrNull()?.jsonObject
        val lastTarget = lastRule?.get("outboundTag")?.jsonPrimitive?.content
            ?: lastRule?.get("balancerTag")?.jsonPrimitive?.content ?: ""
        val allDirect = lastTarget == "direct" && !lastTarget.startsWith("proxy-")

        val preset = when {
            allDirect && !hasRuDomainRule -> RoutingPreset.ALL_DIRECT
            hasRuDomainRule && hasRuIpRule -> RoutingPreset.RU_DIRECT
            !hasRuDomainRule && !hasRuIpRule && !allDirect -> RoutingPreset.ALL_VPN
            else -> RoutingPreset.RU_DIRECT
        }

        // Detect custom routes (rules with our tag in comment or specific IP patterns)
        val customRoutes = mutableListOf<CustomRoute>()
        for (rule in rules) {
            val obj = rule.jsonObject
            val target = obj["outboundTag"]?.jsonPrimitive?.content
                ?: if (obj.containsKey("balancerTag")) "proxy" else continue
            val routeTarget = if (target == "direct") "direct" else "proxy"

            // Source-based routes (LAN device IPs)
            val sources = obj["source"]?.jsonArray
            sources?.forEach { srcEl ->
                val src = srcEl.jsonPrimitive.content
                customRoutes.add(CustomRoute(src, routeTarget, "Устройство", routeType = "source"))
            }

            // Destination IP routes (not geoip, not standard private ranges, not Aqara preset)
            val ips = obj["ip"]?.jsonArray
            ips?.forEach { ipEl ->
                val ip = ipEl.jsonPrimitive.content
                if (!ip.startsWith("ext:") && !ip.startsWith("geoip") &&
                    ip != "0.0.0.0/8" && !ip.startsWith("10.") && !ip.startsWith("127.") &&
                    !ip.startsWith("172.16.") && !ip.startsWith("192.168.") &&
                    !ip.startsWith("169.254.") && !ip.startsWith("224.") && ip != "255.255.255.255/32" &&
                    ip !in AQARA_IPS) {
                    customRoutes.add(CustomRoute(ip, routeTarget, ""))
                }
            }

            // Domain-based routes (not geosite presets, not standard TLDs, not YouTube/Aqara preset)
            val domains = obj["domain"]?.jsonArray
            domains?.forEach { domEl ->
                val dom = domEl.jsonPrimitive.content
                if (!dom.startsWith("ext:") && !dom.startsWith("geosite") &&
                    dom != "domain:ru" && dom != "domain:su" && dom != "domain:рф" &&
                    dom !in YOUTUBE_DOMAINS && dom !in AQARA_DOMAINS) {
                    val cleanDomain = dom.removePrefix("domain:").removePrefix("full:")
                    customRoutes.add(CustomRoute(cleanDomain, routeTarget, "", routeType = "domain"))
                }
            }
        }

        val quicXrayBlocked = rules.any { rule ->
            val obj = rule.jsonObject
            obj["network"]?.jsonPrimitive?.content == "udp" &&
                obj["port"]?.jsonPrimitive?.content?.contains("443") == true &&
                obj["outboundTag"]?.jsonPrimitive?.content == "block"
        }
        val quicIptablesBlocked = try {
            ssh.exec(
                "iptables -C FORWARD -p udp --dport 443 -j REJECT --reject-with icmp-port-unreachable 2>/dev/null && echo yes"
            ).stdout.trim() == "yes"
        } catch (_: Exception) { false }
        val quicBlocked = quicXrayBlocked || quicIptablesBlocked

        // Detect YouTube preset: rule with googlevideo.com going through balancer/proxy
        val youtubeUnblock = rules.any { rule ->
            val obj = rule.jsonObject
            val domains = obj["domain"]?.jsonArray
            val hasProxyTarget = obj.containsKey("balancerTag") ||
                obj["outboundTag"]?.jsonPrimitive?.content?.startsWith("proxy-") == true
            hasProxyTarget && domains?.any { it.jsonPrimitive.content == "domain:googlevideo.com" } == true
        }

        // Detect Aqara preset: either Aqara IPs or aqara.com domain routed to proxy
        val aqaraEnabled = rules.any { rule ->
            val obj = rule.jsonObject
            val hasProxyTarget = obj.containsKey("balancerTag") ||
                obj["outboundTag"]?.jsonPrimitive?.content?.startsWith("proxy-") == true
            if (!hasProxyTarget) return@any false
            val ips = obj["ip"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            val domains = obj["domain"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            ips.any { it in AQARA_IPS } || domains.any { it in AQARA_DOMAINS }
        }

        val mode = getRoutingMode()
        val balancerTags = try { getBalancerTags() } catch (_: Exception) { emptyList() }

        return RoutingConfig(
            preset = preset,
            mode = mode,
            balancerTags = balancerTags,
            customRoutes = customRoutes,
            quicBlocked = quicBlocked,
            youtubeUnblock = youtubeUnblock,
            aqaraEnabled = aqaraEnabled
        )
    }

    /**
     * Apply a routing preset. Rebuilds 05_routing.json.
     */
    // ========== Initial Setup (after fresh xkeen install) ==========

    /**
     * Check if router needs initial configuration.
     */
    suspend fun checkSetupState(): SetupState {
        val xkeenExists = ssh.exec("which xkeen 2>/dev/null").stdout.trim().isNotEmpty()
        val configDir = ssh.exec("ls ${Paths.CONFIGS_DIR}/*.json 2>/dev/null").stdout.trim()
        val configsExist = configDir.isNotEmpty()

        val hasProxies = try {
            getProxyList().isNotEmpty()
        } catch (_: Exception) { false }

        return SetupState(
            xkeenInstalled = xkeenExists,
            configsExist = configsExist,
            hasProxies = hasProxies,
            needsSetup = xkeenExists && (!hasProxies || !configsExist)
        )
    }

    /**
     * Perform initial setup: write 01_log, 03_inbounds, 04_outbounds, 05_routing.
     * @param vlessLinks list of VLESS links to add as proxies
     */
    suspend fun initialSetup(vlessLinks: List<String>): Pair<Boolean, String> {
        val parser = com.xkeen.android.data.remote.VlessParser()

        // Parse all VLESS links first
        val outbounds = mutableListOf<JsonObject>()
        val tags = mutableListOf<String>()
        for (link in vlessLinks) {
            try {
                val parsed = parser.parse(link.trim())
                val outbound = mapToJsonObject(parsed.outbound)
                outbounds.add(outbound)
                tags.add(parsed.tag)
            } catch (e: Exception) {
                return Pair(false, "Ошибка парсинга: ${e.message}")
            }
        }

        if (outbounds.isEmpty()) return Pair(false, "Нет серверов для добавления")

        // 1. Write 01_log.json
        val logConfig = """
{
  "log": {
    "access": "/opt/var/log/xray/access.log",
    "error": "/opt/var/log/xray/error.log",
    "loglevel": "warning"
  }
}""".trimIndent()
        ssh.writeFileB64("${Paths.CONFIGS_DIR}/01_log.json", logConfig)

        // 2. Write 03_inbounds.json
        val inboundsConfig = """
{
  "inbounds": [
    {
      "tag": "redirect",
      "port": 61219,
      "protocol": "dokodemo-door",
      "settings": {
        "network": "tcp",
        "followRedirect": true
      },
      "sniffing": {
        "enabled": true,
        "routeOnly": true,
        "destOverride": [
          "http",
          "tls"
        ]
      }
    },
    {
      "tag": "tproxy",
      "port": 61219,
      "protocol": "dokodemo-door",
      "settings": {
        "network": "udp",
        "followRedirect": true
      },
      "streamSettings": {
        "sockopt": {
          "tproxy": "tproxy"
        }
      },
      "sniffing": {
        "enabled": true,
        "routeOnly": true,
        "destOverride": [
          "http",
          "tls"
        ]
      }
    }
  ]
}""".trimIndent()
        ssh.writeFileB64("${Paths.CONFIGS_DIR}/03_inbounds.json", inboundsConfig)

        // 3. Write 04_outbounds.json
        val allOutbounds = outbounds + listOf(
            buildJsonObject {
                put("tag", "direct")
                put("protocol", "freedom")
            },
            buildJsonObject {
                put("tag", "block")
                put("protocol", "blackhole")
                putJsonObject("settings") {
                    putJsonObject("response") { put("type", "http") }
                }
            }
        )
        val outboundsObj = buildJsonObject {
            putJsonArray("outbounds") {
                allOutbounds.forEach { add(it) }
            }
        }
        val outContent = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), outboundsObj)
        ssh.writeFileB64(Paths.OUTBOUNDS, outContent)

        // 4. Write 05_routing.json (standard preset)
        val inboundTags = buildJsonArray {
            add(JsonPrimitive("redirect"))
            add(JsonPrimitive("tproxy"))
        }
        val routingObj = buildJsonObject {
            putJsonObject("routing") {
                put("domainStrategy", "IPIfNonMatch")
                if (tags.size >= 2) {
                    putJsonArray("balancers") {
                        addJsonObject {
                            put("tag", "proxy-balancer")
                            putJsonArray("selector") { tags.forEach { add(JsonPrimitive(it)) } }
                            putJsonObject("strategy") { put("type", "leastping") }
                            put("fallbackTag", "direct")
                        }
                    }
                }
                putJsonArray("rules") {
                    // QUIC block
                    addJsonObject {
                        put("type", "field")
                        put("inboundTag", inboundTags)
                        put("outboundTag", "block")
                        put("network", "udp")
                        put("port", "135,137,138,139,443")
                    }
                    // RU domains direct
                    addJsonObject {
                        put("type", "field")
                        put("inboundTag", inboundTags)
                        put("outboundTag", "direct")
                        putJsonArray("domain") {
                            add(JsonPrimitive("ext:geosite_v2fly.dat:category-ru"))
                            add(JsonPrimitive("domain:ru"))
                            add(JsonPrimitive("domain:su"))
                            add(JsonPrimitive("domain:рф"))
                        }
                    }
                    // RU IPs direct
                    addJsonObject {
                        put("type", "field")
                        put("inboundTag", inboundTags)
                        put("outboundTag", "direct")
                        putJsonArray("ip") {
                            add(JsonPrimitive("ext:geoip_v2fly.dat:ru"))
                            add(JsonPrimitive("ext:geoip_v2fly.dat:private"))
                        }
                    }
                    // BitTorrent direct
                    addJsonObject {
                        put("type", "field")
                        put("inboundTag", inboundTags)
                        put("outboundTag", "direct")
                        putJsonArray("protocol") { add(JsonPrimitive("bittorrent")) }
                    }
                    // Catch-all
                    if (tags.size >= 2) {
                        addJsonObject {
                            put("type", "field")
                            put("inboundTag", inboundTags)
                            put("network", "tcp,udp")
                            put("balancerTag", "proxy-balancer")
                        }
                    } else {
                        addJsonObject {
                            put("type", "field")
                            put("inboundTag", inboundTags)
                            put("network", "tcp,udp")
                            put("outboundTag", tags.first())
                        }
                    }
                }
            }
        }
        val routContent = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), routingObj)
        ssh.writeFileB64(Paths.ROUTING, routContent)

        // 5. Write 07_observatory.json (if 2+ servers)
        if (tags.size >= 2) {
            enableFailover(tags)
        }

        // 6. Create log directory
        ssh.exec("mkdir -p /opt/var/log/xray")

        return Pair(true, "Настройка завершена: ${tags.size} сервер(ов)")
    }

    private fun mapToJsonObject(map: Map<String, Any?>): JsonObject {
        return JsonObject(map.mapValues { anyToJsonElement(it.value) })
    }

    private fun anyToJsonElement(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is String -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is Map<*, *> -> {
            @Suppress("UNCHECKED_CAST")
            JsonObject((v as Map<String, Any?>).mapValues { anyToJsonElement(it.value) })
        }
        is List<*> -> JsonArray(v.map { anyToJsonElement(it) })
        is JsonElement -> v
        else -> JsonPrimitive(v.toString())
    }

    suspend fun applyPreset(
        preset: RoutingPreset,
        customRoutes: List<CustomRoute> = emptyList(),
        quicBlocked: Boolean = true,
        youtubeUnblock: Boolean = false,
        aqaraEnabled: Boolean = false
    ): Pair<Boolean, String> {
        val raw = ssh.readFile(Paths.ROUTING)
        val config = Json.parseToJsonElement(raw).jsonObject.toMutableMap()
        val routing = config["routing"]?.jsonObject?.toMutableMap()
            ?: return Pair(false, "No routing section")

        val inboundTags = buildJsonArray {
            add(JsonPrimitive("redirect"))
            add(JsonPrimitive("tproxy"))
        }

        val rules = mutableListOf<JsonObject>()

        // 1. Block NetBIOS/SMB broadcast on UDP. QUIC (UDP 443) is handled
        // via iptables ICMP reject (see RouterCommands.applyQuicReject) — faster
        // than xray blackhole which silently drops and causes 7s browser retry.
        val udpBlockPorts = if (quicBlocked) "135,137,138,139" else "135,137,138,139"
        rules.add(buildJsonObject {
            put("type", "field")
            put("inboundTag", inboundTags)
            put("outboundTag", "block")
            put("network", "udp")
            put("port", udpBlockPorts)
        })

        // 2. Source-based routes (LAN devices → proxy/direct)
        val sourceProxyRoutes = customRoutes.filter { it.routeType == "source" && it.target == "proxy" }
        if (sourceProxyRoutes.isNotEmpty()) {
            val hasBalancer = routing["balancers"]?.jsonArray?.any {
                it.jsonObject["tag"]?.jsonPrimitive?.content == "proxy-balancer"
            } ?: false
            rules.add(buildJsonObject {
                put("type", "field")
                put("inboundTag", inboundTags)
                putJsonArray("source") {
                    sourceProxyRoutes.forEach { add(JsonPrimitive(it.value)) }
                }
                if (hasBalancer) put("balancerTag", "proxy-balancer")
                else {
                    val firstProxy = getProxyList().firstOrNull()?.tag ?: "direct"
                    put("outboundTag", firstProxy)
                }
            })
        }
        val sourceDirectRoutes = customRoutes.filter { it.routeType == "source" && it.target == "direct" }
        if (sourceDirectRoutes.isNotEmpty()) {
            rules.add(buildJsonObject {
                put("type", "field")
                put("inboundTag", inboundTags)
                putJsonArray("source") {
                    sourceDirectRoutes.forEach { add(JsonPrimitive(it.value)) }
                }
                put("outboundTag", "direct")
            })
        }

        // 3. Custom destination routes that go through PROXY (before RU rules)
        val hasBalancerForCustom = routing["balancers"]?.jsonArray?.any {
            it.jsonObject["tag"]?.jsonPrimitive?.content == "proxy-balancer"
        } ?: false

        val proxyCustomIps = customRoutes.filter { it.routeType == "ip" && it.target == "proxy" }
        if (proxyCustomIps.isNotEmpty()) {
            rules.add(buildJsonObject {
                put("type", "field")
                put("inboundTag", inboundTags)
                putJsonArray("ip") {
                    proxyCustomIps.forEach { add(JsonPrimitive(it.value)) }
                }
                if (hasBalancerForCustom) put("balancerTag", "proxy-balancer")
                else {
                    val firstProxy = getProxyList().firstOrNull()?.tag ?: "direct"
                    put("outboundTag", firstProxy)
                }
            })
        }

        // 3b. Custom domain routes that go through PROXY (before RU rules)
        val proxyCustomDomains = customRoutes.filter { it.routeType == "domain" && it.target == "proxy" }
        if (proxyCustomDomains.isNotEmpty()) {
            rules.add(buildJsonObject {
                put("type", "field")
                put("inboundTag", inboundTags)
                putJsonArray("domain") {
                    proxyCustomDomains.forEach { add(JsonPrimitive("domain:${it.value}")) }
                }
                if (hasBalancerForCustom) put("balancerTag", "proxy-balancer")
                else {
                    val firstProxy = getProxyList().firstOrNull()?.tag ?: "direct"
                    put("outboundTag", firstProxy)
                }
            })
        }

        // 3c. YouTube preset (anti-throttling): proxy ALL *.googlevideo / youtube / ytimg / etc
        // Must be placed BEFORE geoip:ru to override Google Global Cache in RU ISPs.
        if (youtubeUnblock) {
            rules.add(buildJsonObject {
                put("type", "field")
                put("inboundTag", inboundTags)
                putJsonArray("domain") {
                    YOUTUBE_DOMAINS.forEach { add(JsonPrimitive(it)) }
                }
                if (hasBalancerForCustom) put("balancerTag", "proxy-balancer")
                else {
                    val firstProxy = getProxyList().firstOrNull()?.tag ?: "direct"
                    put("outboundTag", firstProxy)
                }
            })
        }

        // 3d. Aqara preset: proxy Kingsoft Cloud IPs (hub) + aqara.com domain (phone app).
        // Kingsoft IPs live inside geoip:ru, so this must be BEFORE geoip:ru rule.
        if (aqaraEnabled) {
            rules.add(buildJsonObject {
                put("type", "field")
                put("inboundTag", inboundTags)
                putJsonArray("ip") {
                    AQARA_IPS.forEach { add(JsonPrimitive(it)) }
                }
                if (hasBalancerForCustom) put("balancerTag", "proxy-balancer")
                else {
                    val firstProxy = getProxyList().firstOrNull()?.tag ?: "direct"
                    put("outboundTag", firstProxy)
                }
            })
            rules.add(buildJsonObject {
                put("type", "field")
                put("inboundTag", inboundTags)
                putJsonArray("domain") {
                    AQARA_DOMAINS.forEach { add(JsonPrimitive(it)) }
                }
                if (hasBalancerForCustom) put("balancerTag", "proxy-balancer")
                else {
                    val firstProxy = getProxyList().firstOrNull()?.tag ?: "direct"
                    put("outboundTag", firstProxy)
                }
            })
        }

        // 4. Preset-specific rules
        when (preset) {
            RoutingPreset.RU_DIRECT -> {
                // RU domains → direct
                rules.add(buildJsonObject {
                    put("type", "field")
                    put("inboundTag", inboundTags)
                    put("outboundTag", "direct")
                    putJsonArray("domain") {
                        add(JsonPrimitive("ext:geosite_v2fly.dat:category-ru"))
                        add(JsonPrimitive("domain:ru"))
                        add(JsonPrimitive("domain:su"))
                        add(JsonPrimitive("domain:рф"))
                    }
                })
                // RU IPs → direct
                rules.add(buildJsonObject {
                    put("type", "field")
                    put("inboundTag", inboundTags)
                    put("outboundTag", "direct")
                    putJsonArray("ip") {
                        add(JsonPrimitive("ext:geoip_v2fly.dat:ru"))
                        add(JsonPrimitive("ext:geoip_v2fly.dat:private"))
                    }
                })
                // BitTorrent → direct
                rules.add(buildJsonObject {
                    put("type", "field")
                    put("inboundTag", inboundTags)
                    put("outboundTag", "direct")
                    putJsonArray("protocol") { add(JsonPrimitive("bittorrent")) }
                })
            }
            RoutingPreset.ALL_VPN -> {
                // Private networks → direct (so LAN works)
                rules.add(buildJsonObject {
                    put("type", "field")
                    put("inboundTag", inboundTags)
                    put("outboundTag", "direct")
                    putJsonArray("ip") {
                        add(JsonPrimitive("ext:geoip_v2fly.dat:private"))
                    }
                })
            }
            RoutingPreset.ALL_DIRECT -> {
                // No special rules needed
            }
        }

        // 5. Custom destination routes that go DIRECT
        val directCustomIps = customRoutes.filter { it.routeType == "ip" && it.target == "direct" }
        if (directCustomIps.isNotEmpty() && preset != RoutingPreset.ALL_DIRECT) {
            rules.add(buildJsonObject {
                put("type", "field")
                put("inboundTag", inboundTags)
                put("outboundTag", "direct")
                putJsonArray("ip") {
                    directCustomIps.forEach { add(JsonPrimitive(it.value)) }
                }
            })
        }

        // 5b. Custom domain routes that go DIRECT
        val directCustomDomains = customRoutes.filter { it.routeType == "domain" && it.target == "direct" }
        if (directCustomDomains.isNotEmpty() && preset != RoutingPreset.ALL_DIRECT) {
            rules.add(buildJsonObject {
                put("type", "field")
                put("inboundTag", inboundTags)
                put("outboundTag", "direct")
                putJsonArray("domain") {
                    directCustomDomains.forEach { add(JsonPrimitive("domain:${it.value}")) }
                }
            })
        }

        // 6. Catch-all rule
        val catchAll = when (preset) {
            RoutingPreset.ALL_DIRECT -> buildJsonObject {
                put("type", "field")
                put("inboundTag", inboundTags)
                put("network", "tcp,udp")
                put("outboundTag", "direct")
            }
            else -> {
                // Check if balancer exists
                val hasBalancer = routing["balancers"]?.jsonArray?.any {
                    it.jsonObject["tag"]?.jsonPrimitive?.content == "proxy-balancer"
                } ?: false
                if (hasBalancer) {
                    buildJsonObject {
                        put("type", "field")
                        put("inboundTag", inboundTags)
                        put("network", "tcp,udp")
                        put("balancerTag", "proxy-balancer")
                    }
                } else {
                    // Single server — use first proxy tag
                    val proxies = getProxyList()
                    val firstProxy = proxies.firstOrNull()?.tag ?: "direct"
                    buildJsonObject {
                        put("type", "field")
                        put("inboundTag", inboundTags)
                        put("network", "tcp,udp")
                        put("outboundTag", firstProxy)
                    }
                }
            }
        }
        rules.add(catchAll)

        routing["rules"] = JsonArray(rules)
        config["routing"] = JsonObject(routing)

        val content = Json { prettyPrint = true }.encodeToString(
            JsonObject.serializer(), JsonObject(config)
        )
        ssh.writeFileB64(Paths.ROUTING, content)
        return Pair(true, "Preset applied: ${preset.title}")
    }
}
