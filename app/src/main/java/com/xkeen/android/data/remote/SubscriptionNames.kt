package com.xkeen.android.data.remote

/** Names shared by the app, the Mihomo config and the router-side script `xk-sub.sh`. */
object SubscriptionNames {
    const val PROVIDER = "xk-sub"
    const val PROVIDER_PATH = "./providers/xk-sub.yaml"
    const val BEST = "SUB-BEST"
    const val ALL = "SUB-ALL"
    const val BENCH = "SUB-BENCH"
    /** Servers that geo-blocking services accept (checked by xk-sub.sh). */
    const val GEO = "SUB-GEO"
    const val LISTENER = "xk-bench"
    const val BENCH_PORT = 17899
    const val BENCH_RULE = "IN-NAME,$LISTENER,$BENCH"
    const val TEST_URL = "http://cp.cloudflare.com/generate_204"
    val GROUPS = setOf(BEST, ALL, BENCH, GEO)

    /** Services that block by exit country; routed through SUB-GEO. Shown in the app as one line. */
    val GEO_SERVICES = listOf("ElevenLabs", "ChatGPT", "Claude")
    val GEO_DOMAINS = listOf(
        "elevenlabs.io", "elevenlabs.com",
        "openai.com", "chatgpt.com", "oaistatic.com", "oaiusercontent.com", "sora.com",
        "anthropic.com", "claude.ai", "claude.com", "claudeusercontent.com"
    )
    val GEO_RULES: List<String> = GEO_DOMAINS.map { "DOMAIN-SUFFIX,$it,$GEO" }

    /** Rules the app manages; they must stay first and survive routing changes. */
    fun isManagedRule(rule: Any?): Boolean =
        rule == BENCH_RULE || (rule is String && rule.substringAfterLast(',') == GEO)

    const val DIR = "/opt/etc/xkeen-android"
    const val SCRIPT = "$DIR/xk-sub.sh"
    const val CONF = "$DIR/subscription.conf"
    const val CRONTAB = "/opt/var/spool/cron/crontabs/root"
}

internal fun isValidSubscriptionUrl(url: String): Boolean {
    val uri = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return false
    return uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() &&
        url.none { it.isWhitespace() || it.code < 0x20 }
}
