package com.xkeen.android.domain.model

data class RouterProfile(
    val id: Long = 0,
    val alias: String,
    val host: String,
    val username: String,
    val password: String,
    val port: Int = 22
)

data class RouterStatus(
    val xrayRunning: Boolean = false,
    val xrayPid: String = "",
    val xrayMem: String = "",
    val memTotal: Long = 0,
    val memUsed: Long = 0,
    val memFree: Long = 0,
    val cpuUsed: Double = 0.0,
    val loadAvg: String = "",
    val uptime: String = "",
    val xkeenVersion: String = "",
    val observatory: ObservatoryState = ObservatoryState()
)

data class ObservatoryState(
    val failedProxies: List<String> = emptyList(),
    val usage: Map<String, Int> = emptyMap(),
    val selected: String? = null
)

data class ProxyInfo(
    val tag: String,
    val address: String,
    val port: Int,
    val transport: String,
    val sni: String = "",
    val failed: Boolean = false,
    val requests: Int = 0,
    val selected: Boolean = false
)

sealed class RoutingMode {
    data object Auto : RoutingMode()
    data class Manual(val tag: String) : RoutingMode()
}

data class ConfigTestResult(
    val ok: Boolean,
    val output: String
)

data class ConfigState(
    val proxyCount: Int = 0,
    val hasBalancer: Boolean = false,
    val hasObservatory: Boolean = false,
    val proxyTags: List<String> = emptyList()
) {
    val isSingleServer get() = proxyCount == 1 && !hasBalancer
    val isReady get() = proxyCount >= 2 && hasBalancer && hasObservatory
    val isEmpty get() = proxyCount == 0

    companion object {
        val Empty = ConfigState()
    }
}

enum class RoutingPreset(val title: String, val description: String) {
    ALL_DIRECT("Всё напрямую", "VPN отключён, весь трафик идёт через провайдера"),
    RU_DIRECT("Стандартный", "Российские сайты напрямую, остальное через VPN"),
    ALL_VPN("Всё через VPN", "Весь трафик через прокси-серверы")
}

data class CustomRoute(
    val value: String,       // IP (107.155.52.0/23) or domain (example.com)
    val target: String,      // "proxy" or "direct"
    val comment: String = "", // e.g. "Aqara Cloud"
    val routeType: String = "ip" // "ip" = destination-based, "source" = source-based (for LAN devices)
)

data class RoutingConfig(
    val preset: RoutingPreset = RoutingPreset.RU_DIRECT,
    val mode: RoutingMode = RoutingMode.Auto,
    val balancerTags: List<String> = emptyList(),
    val customRoutes: List<CustomRoute> = emptyList(),
    val quicBlocked: Boolean = true
)

data class SetupState(
    val xkeenInstalled: Boolean = false,
    val configsExist: Boolean = false,
    val hasProxies: Boolean = false,
    val needsSetup: Boolean = false
)

data class ParsedVless(
    val tag: String,
    val name: String,
    val address: String,
    val port: Int,
    val transport: String,
    val outbound: Map<String, Any?>
)
