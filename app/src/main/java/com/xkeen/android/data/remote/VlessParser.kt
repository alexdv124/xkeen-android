package com.xkeen.android.data.remote

import com.xkeen.android.domain.model.ParsedVless
import kotlinx.serialization.json.*
import java.net.URLDecoder

class VlessParser {

    fun parse(link: String): ParsedVless {
        val trimmed = link.trim()
        if (!trimmed.startsWith("vless://")) throw IllegalArgumentException("Not a VLESS link")

        var body = trimmed.removePrefix("vless://")

        // Fragment (display name)
        var name = ""
        if ("#" in body) {
            val idx = body.lastIndexOf('#')
            name = URLDecoder.decode(body.substring(idx + 1), "UTF-8")
            body = body.substring(0, idx)
        }

        val atParts = body.split("@", limit = 2)
        if (atParts.size < 2) throw IllegalArgumentException("Invalid VLESS link: missing @")
        val uuid = atParts[0]
        val remainder = atParts[1]

        val qIdx = remainder.indexOf('?')
        val hostPort = if (qIdx >= 0) remainder.substring(0, qIdx) else remainder
        val queryString = if (qIdx >= 0) remainder.substring(qIdx + 1) else ""

        val lastColon = hostPort.lastIndexOf(':')
        if (lastColon <= 0) throw IllegalArgumentException("Invalid VLESS link: missing port")
        val address = hostPort.substring(0, lastColon)
        val port = hostPort.substring(lastColon + 1).toIntOrNull()
            ?: throw IllegalArgumentException("Invalid port")

        val params = parseQueryString(queryString)
        val transport = params["type"] ?: "tcp"
        val flow = params["flow"] ?: ""
        val tag = generateTag(name, address)

        // fm= carries TLS-fragmentation settings, e.g. {"fragment":{"length":"50-100","packets":"tlshello","interval":"10-20"}}
        // When present, build a freedom outbound "fragment" and route the proxy outbound through it via sockopt.dialerProxy.
        val fragmentSettings: JsonObject? = params["fm"]?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching {
                val obj = Json.parseToJsonElement(raw).jsonObject
                obj["fragment"]?.let { intsFromWholeFloats(it).jsonObject }
            }.getOrNull()
        }
        val useFragment = fragmentSettings != null

        val outbound = if (transport == "xhttp") {
            buildXhttpOutbound(tag, address, port, uuid, params, useFragment)
        } else {
            buildVisionOutbound(tag, address, port, uuid, params, flow.ifEmpty { "xtls-rprx-vision" }, useFragment)
        }

        return ParsedVless(
            tag = tag,
            name = name,
            address = address,
            port = port,
            transport = if (transport == "xhttp") "xhttp" else if (flow.isNotEmpty()) "vision" else "tcp",
            outbound = jsonToMap(outbound),
            fragmentSettings = fragmentSettings?.let { jsonToMap(it) }
        )
    }

    private fun generateTag(name: String, address: String): String {
        val hostMatch = Regex("""\.?([a-z]{2}\d+)\.""").find(address)
        if (hostMatch != null) return "proxy-${hostMatch.groupValues[1]}"

        val nameMatch = Regex("""([A-Z]{2})\s*#?\s*(\d+)""").find(name)
        if (nameMatch != null) return "proxy-${nameMatch.groupValues[1].lowercase()}${nameMatch.groupValues[2]}"

        val short = address.split(".")[0].replace("h2", "").trim('.')
        return "proxy-${short.ifEmpty { "new" }}"
    }

    private fun buildXhttpOutbound(tag: String, address: String, port: Int, uuid: String, params: Map<String, String>, useFragment: Boolean = false): JsonObject {
        val sni = params["sni"] ?: address
        val fp = params["fp"] ?: "chrome"
        val pbk = params["pbk"] ?: ""
        val sid = params["sid"] ?: ""
        val spx = params["spx"] ?: ""
        val host = params["host"] ?: address
        val path = params["path"] ?: "/"

        val extraRaw = params["extra"] ?: "{}"
        val extraDecoded = URLDecoder.decode(extraRaw, "UTF-8")
        var extra = Json.parseToJsonElement(extraDecoded).jsonObject
        extra = intsFromWholeFloats(extra).jsonObject

        val downloadSettings = extra["downloadSettings"] ?: JsonObject(emptyMap())
        val xmuxUpload = extra["xmux"] ?: JsonObject(emptyMap())

        val defaultXmux = buildJsonObject {
            put("cMaxReuseTimes", "0-0")
            put("hMaxRequestTimes", "0-0")
            put("hMaxReusableSecs", "3180-3300")
            put("maxConcurrency", "0-0")
            put("maxConnections", "1-1")
        }

        return buildJsonObject {
            put("tag", tag)
            put("protocol", "vless")
            putJsonObject("settings") {
                putJsonArray("vnext") {
                    addJsonObject {
                        put("address", address)
                        put("port", port)
                        putJsonArray("users") {
                            addJsonObject {
                                put("id", uuid)
                                put("flow", "")
                                put("encryption", "none")
                                put("level", 0)
                            }
                        }
                    }
                }
            }
            putJsonObject("streamSettings") {
                put("network", "xhttp")
                put("security", "reality")
                putJsonObject("realitySettings") {
                    put("publicKey", pbk)
                    put("fingerprint", fp)
                    put("serverName", sni)
                    put("shortId", sid)
                    put("spiderX", spx)
                }
                putJsonObject("xhttpSettings") {
                    put("mode", "packet-up")
                    put("host", host)
                    put("path", path)
                    putJsonObject("extra") {
                        put("noGRPCHeader", extra["noGRPCHeader"] ?: JsonPrimitive(true))
                        put("scMaxEachPostBytes", extra["scMaxEachPostBytes"] ?: JsonPrimitive("150000-150000"))
                        put("scMinPostsIntervalMs", extra["scMinPostsIntervalMs"] ?: JsonPrimitive("15-15"))
                        put("xPaddingBytes", extra["xPaddingBytes"] ?: JsonPrimitive("50-355"))
                        put("xmux", if (xmuxUpload is JsonObject && xmuxUpload.isNotEmpty()) xmuxUpload else defaultXmux)
                        put("downloadSettings", downloadSettings)
                    }
                }
                if (useFragment) {
                    putJsonObject("sockopt") {
                        put("dialerProxy", "fragment")
                    }
                }
            }
        }
    }

    private fun buildVisionOutbound(tag: String, address: String, port: Int, uuid: String, params: Map<String, String>, flow: String, useFragment: Boolean = false): JsonObject {
        val sni = params["sni"] ?: address
        val fp = params["fp"] ?: "chrome"
        val pbk = params["pbk"] ?: ""
        val sid = params["sid"] ?: ""

        return buildJsonObject {
            put("tag", tag)
            put("protocol", "vless")
            putJsonObject("settings") {
                putJsonArray("vnext") {
                    addJsonObject {
                        put("address", address)
                        put("port", port)
                        putJsonArray("users") {
                            addJsonObject {
                                put("id", uuid)
                                put("flow", flow)
                                put("encryption", "none")
                                put("level", 0)
                            }
                        }
                    }
                }
            }
            putJsonObject("streamSettings") {
                put("network", "tcp")
                put("security", "reality")
                putJsonObject("realitySettings") {
                    put("publicKey", pbk)
                    put("fingerprint", fp)
                    put("serverName", sni)
                    put("shortId", sid)
                    put("spiderX", "")
                }
                if (useFragment) {
                    putJsonObject("sockopt") {
                        put("dialerProxy", "fragment")
                    }
                }
            }
        }
    }

    private fun intsFromWholeFloats(element: JsonElement): JsonElement {
        return when (element) {
            is JsonObject -> JsonObject(element.mapValues { intsFromWholeFloats(it.value) })
            is JsonArray -> JsonArray(element.map { intsFromWholeFloats(it) })
            is JsonPrimitive -> {
                if (element.isString) return element
                val d = element.doubleOrNull
                if (d != null && d == d.toLong().toDouble()) {
                    JsonPrimitive(d.toLong())
                } else {
                    element
                }
            }
            else -> element
        }
    }

    private fun parseQueryString(qs: String): Map<String, String> {
        if (qs.isEmpty()) return emptyMap()
        return qs.split("&").associate { param ->
            val (key, value) = param.split("=", limit = 2).let {
                it[0] to (it.getOrElse(1) { "" })
            }
            key to URLDecoder.decode(value, "UTF-8")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun jsonToMap(obj: JsonObject): Map<String, Any?> {
        return obj.mapValues { elementToAny(it.value) }
    }

    private fun elementToAny(e: JsonElement): Any? = when (e) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            e.isString -> e.content
            e.booleanOrNull != null -> e.boolean
            e.longOrNull != null -> e.long
            e.doubleOrNull != null -> e.double
            else -> e.content
        }
        is JsonArray -> e.map { elementToAny(it) }
        is JsonObject -> e.mapValues { elementToAny(it.value) }
    }
}
