package com.xkeen.android.data.remote

import com.xkeen.android.data.ssh.SshClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One proxy or proxy-group as reported by Mihomo's `/proxies` endpoint. */
data class MihomoNode(
    val name: String,
    val type: String,
    val now: String = "",                  // currently selected member (groups only)
    val all: List<String> = emptyList(),   // member names (groups only)
    val delayMs: Int = 0,                   // last measured latency, 0 = unknown/timeout
    val isGroup: Boolean = false
)

/**
 * Client for Mihomo's RESTful external-controller.
 *
 * The controller binds to the router's loopback, so it is unreachable over HTTP from
 * the phone. Every call is therefore a `curl` run on the router through the existing
 * SSH channel — consistent with the rest of the app's SSH-only data layer.
 *
 * The controller address and secret are read from the running Mihomo config so we honour
 * whatever xkeen set up, falling back to the `127.0.0.1:9090` / no-secret default that the
 * app's own generated config uses.
 */
class MihomoApi(private val ssh: SshClient) {

    private val json = Json { ignoreUnknownKeys = true }

    data class Controller(val base: String, val secret: String)

    private var cachedController: Controller? = null
    private var curlChecked = false
    private var curlAvailable = false

    /** True if `curl` exists on the router (required for PUT/PATCH bodies). */
    suspend fun isAvailable(): Boolean {
        if (!curlChecked) {
            curlAvailable = ssh.exec("command -v curl 2>/dev/null || true").stdout.trim().isNotEmpty()
            curlChecked = true
        }
        return curlAvailable
    }

    /** Reads `external-controller` host:port and `secret` from the running Mihomo config. */
    suspend fun resolveController(): Controller {
        cachedController?.let { return it }
        val path = RouterCommands(ssh).findMihomoConfigPath()
        var port = "9090"
        var secret = ""
        if (path.isNotBlank()) {
            val raw = ssh.exec(
                "grep -E '^[[:space:]]*(external-controller|secret):' \"$path\" 2>/dev/null || true"
            ).stdout
            for (line in raw.lineSequence()) {
                val trimmed = line.trim()
                when {
                    trimmed.startsWith("external-controller:") -> {
                        val value = trimmed.substringAfter(':', "").trim().trim('"', '\'')
                        val p = value.substringAfterLast(':', "").trim()
                        if (p.toIntOrNull() != null) port = p
                    }
                    trimmed.startsWith("secret:") -> {
                        secret = trimmed.substringAfter(':', "").trim().trim('"', '\'')
                    }
                }
            }
        }
        // Always reach the controller over the router's loopback regardless of bind address.
        return Controller("127.0.0.1:$port", secret).also { cachedController = it }
    }

    /** All proxies and groups, keyed by name (insertion order preserved). */
    suspend fun getProxies(): Map<String, MihomoNode> {
        val resp = curl("GET", "/proxies")
        if (resp.body.isBlank()) return emptyMap()
        val root = runCatching { json.parseToJsonElement(resp.body).jsonObject }.getOrNull() ?: return emptyMap()
        val proxies = root["proxies"]?.let { runCatching { it.jsonObject }.getOrNull() } ?: return emptyMap()

        val result = LinkedHashMap<String, MihomoNode>()
        for ((name, el) in proxies) {
            val obj = runCatching { el.jsonObject }.getOrNull() ?: continue
            val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: ""
            val now = obj["now"]?.jsonPrimitive?.contentOrNull ?: ""
            val all = obj["all"]?.let { runCatching { it.jsonArray }.getOrNull() }
                ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
            val delay = obj["history"]?.let { runCatching { it.jsonArray }.getOrNull() }
                ?.lastOrNull()
                ?.let { runCatching { it.jsonObject["delay"]?.jsonPrimitive?.intOrNull }.getOrNull() }
                ?: 0
            val isGroup = all.isNotEmpty() || type in GROUP_TYPES
            result[name] = MihomoNode(name, type, now, all, delay, isGroup)
        }
        return result
    }

    /** Currently selected member of [group], or empty if it is not a selector. */
    suspend fun selectedOf(group: String): String =
        getProxies()[group]?.takeIf { it.isGroup }?.now ?: ""

    /** Selects [member] inside the [group] selector (PUT /proxies/{group}). */
    suspend fun select(group: String, member: String): Boolean {
        val resp = curl("PUT", "/proxies/${enc(group)}", body = """{"name":"$member"}""")
        return resp.httpCode.startsWith("2")
    }

    /** Latency test of every node in [group] (GET /group/{group}/delay). Returns name → ms. */
    suspend fun testGroupDelay(group: String, timeoutMs: Int = 5000): Map<String, Int> {
        val resp = curl(
            "GET",
            "/group/${enc(group)}/delay?timeout=$timeoutMs&url=$DELAY_URL"
        )
        if (resp.body.isBlank()) return emptyMap()
        val obj = runCatching { json.parseToJsonElement(resp.body).jsonObject }.getOrNull() ?: return emptyMap()
        return obj.mapNotNull { (k, v) ->
            runCatching { v.jsonPrimitive.intOrNull }.getOrNull()?.let { k to it }
        }.toMap()
    }

    /** Latency test of a single node (GET /proxies/{name}/delay). Returns ms, or null on failure. */
    suspend fun testNodeDelay(name: String, timeoutMs: Int = 5000): Int? {
        val resp = curl("GET", "/proxies/${enc(name)}/delay?timeout=$timeoutMs&url=$DELAY_URL")
        val obj = runCatching { json.parseToJsonElement(resp.body).jsonObject }.getOrNull() ?: return null
        return obj["delay"]?.jsonPrimitive?.intOrNull
    }

    /** Hot-reloads the config file in place (PUT /configs?force=true). */
    suspend fun reloadConfig(path: String): Boolean {
        val resp = curl("PUT", "/configs?force=true", body = """{"path":"$path"}""")
        return resp.httpCode.startsWith("2")
    }

    suspend fun version(): String {
        val resp = curl("GET", "/version")
        val obj = runCatching { json.parseToJsonElement(resp.body).jsonObject }.getOrNull() ?: return ""
        return obj["version"]?.jsonPrimitive?.contentOrNull ?: ""
    }

    // ---- internals ----

    private data class CurlResult(val body: String, val httpCode: String)

    private suspend fun curl(method: String, path: String, body: String? = null): CurlResult {
        val c = resolveController()
        val cmd = buildString {
            // Append the HTTP status on its own trailing line via -w so callers can tell
            // 204-no-content success from a connection failure (empty body in both cases).
            append("curl -sS -m 10 -o - -w '\\n%{http_code}'")
            append(" -X ").append(method)
            if (c.secret.isNotBlank()) {
                append(" -H 'Authorization: Bearer ").append(shellEscape(c.secret)).append("'")
            }
            if (body != null) {
                append(" -H 'Content-Type: application/json' --data '").append(shellEscape(body)).append("'")
            }
            append(" 'http://").append(c.base).append(path).append("'")
        }
        val out = ssh.exec(cmd, timeout = 14000).stdout
        val trimmed = out.trimEnd()
        val nl = trimmed.lastIndexOf('\n')
        return if (nl >= 0) {
            CurlResult(trimmed.substring(0, nl), trimmed.substring(nl + 1).trim())
        } else {
            // No status line at all (e.g. curl missing / connection refused with no output).
            CurlResult("", trimmed.trim())
        }
    }

    /** Escapes a string for safe inclusion inside single quotes in a POSIX shell. */
    private fun shellEscape(value: String): String = value.replace("'", "'\\''")

    /** Minimal percent-encoding for a path segment (our names are ASCII, but be safe). */
    private fun enc(name: String): String = name
        .replace("%", "%25")
        .replace(" ", "%20")
        .replace("/", "%2F")
        .replace("#", "%23")
        .replace("?", "%3F")

    private companion object {
        val GROUP_TYPES = setOf("Selector", "URLTest", "Fallback", "LoadBalance", "Relay")
        // http://cp.cloudflare.com/generate_204, pre-encoded for the query string.
        const val DELAY_URL = "http%3A%2F%2Fcp.cloudflare.com%2Fgenerate_204"
    }
}
