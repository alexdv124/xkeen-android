package com.xkeen.android.data.remote

import com.xkeen.android.data.ssh.SshConnection
import com.xkeen.android.data.ssh.SshResult
import com.xkeen.android.domain.model.CustomRoute
import com.xkeen.android.domain.model.RoutingPreset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RoutingPresetTest {
    private class Files : SshConnection {
        val files = mutableMapOf(
            Paths.ROUTING to """{"routing":{"domainStrategy":"IPIfNonMatch","rules":[]}}""",
            Paths.OUTBOUNDS to """{"outbounds":[{"tag":"proxy-a","protocol":"vless"},{"tag":"direct","protocol":"freedom"},{"tag":"block","protocol":"blackhole"}]}"""
        )
        override suspend fun exec(cmd: String, timeout: Int) = SshResult("", "", 0)
        override suspend fun readFile(path: String) = files[path].orEmpty()
        override suspend fun writeFileB64(path: String, content: String) { files[path] = content }
    }

    @Test fun `toggles survive a round trip through all-direct`() = runBlocking {
        val remote = XrayConfigRemote(Files())
        assertTrue(remote.applyPreset(RoutingPreset.RU_DIRECT, emptyList(), true, true, true, true).first)
        val standard = remote.getRoutingConfig()
        assertTrue(standard.zonaDirect && standard.youtubeUnblock && standard.aqaraEnabled)

        // The routing screen re-applies presets with the flags it read back from the rules.
        assertTrue(remote.applyPreset(RoutingPreset.ALL_DIRECT, standard.customRoutes, standard.quicBlocked,
            standard.youtubeUnblock, standard.aqaraEnabled, standard.zonaDirect).first)
        val direct = remote.getRoutingConfig()
        assertEquals(RoutingPreset.ALL_DIRECT, direct.preset)
        assertTrue("Zona must be remembered while everything goes direct", direct.zonaDirect)

        assertTrue(remote.applyPreset(RoutingPreset.RU_DIRECT, direct.customRoutes, direct.quicBlocked,
            direct.youtubeUnblock, direct.aqaraEnabled, direct.zonaDirect).first)
        val back = remote.getRoutingConfig()
        assertEquals(RoutingPreset.RU_DIRECT, back.preset)
        assertTrue(back.zonaDirect && back.youtubeUnblock && back.aqaraEnabled)
    }

    @Test fun `custom routes survive all-direct and private subnets are not mistaken for presets`() = runBlocking {
        val remote = XrayConfigRemote(Files())
        val routes = listOf(
            CustomRoute("10.8.0.0/24", "direct"),
            CustomRoute("192.168.39.0/24", "direct"),
            CustomRoute("1.2.3.0/24", "proxy"),
            CustomRoute("example.org", "direct", routeType = "domain"),
            CustomRoute("habr.com", "proxy", routeType = "domain"),
            CustomRoute("192.168.1.50", "proxy", "Устройство", routeType = "source")
        )
        fun key(list: List<CustomRoute>) = list.map { Triple(it.value, it.target, it.routeType) }.toSet()
        assertTrue(remote.applyPreset(RoutingPreset.RU_DIRECT, routes).first)
        assertEquals(key(routes), key(remote.getRoutingConfig().customRoutes))

        val viaDirect = remote.getRoutingConfig()
        assertTrue(remote.applyPreset(RoutingPreset.ALL_DIRECT, viaDirect.customRoutes).first)
        assertEquals(key(routes), key(remote.getRoutingConfig().customRoutes))

        assertTrue(remote.applyPreset(RoutingPreset.ALL_VPN, remote.getRoutingConfig().customRoutes).first)
        assertTrue(remote.applyPreset(RoutingPreset.RU_DIRECT, remote.getRoutingConfig().customRoutes).first)
        assertEquals(key(routes), key(remote.getRoutingConfig().customRoutes))
    }

    @Test fun `manually selected server survives preset toggles`() = runBlocking {
        val files = Files()
        files.files[Paths.OUTBOUNDS] = """{"outbounds":[{"tag":"proxy-a","protocol":"vless"},{"tag":"proxy-b","protocol":"vless"},{"tag":"direct","protocol":"freedom"}]}"""
        files.files[Paths.ROUTING] = """{"routing":{"balancers":[{"tag":"proxy-balancer","selector":["proxy-"]}],"rules":[]}}"""
        val remote = XrayConfigRemote(files)
        assertTrue(remote.applyPreset(RoutingPreset.RU_DIRECT).first)
        assertEquals(com.xkeen.android.domain.model.RoutingMode.Auto, remote.getRoutingMode())
        assertTrue(remote.setRoutingMode(com.xkeen.android.domain.model.RoutingMode.Manual("proxy-b")).first)
        assertTrue(remote.applyPreset(RoutingPreset.RU_DIRECT, youtubeUnblock = true).first)
        assertEquals(com.xkeen.android.domain.model.RoutingMode.Manual("proxy-b"), remote.getRoutingMode())
        // A deleted manual server falls back to the balancer.
        files.files[Paths.OUTBOUNDS] = """{"outbounds":[{"tag":"proxy-a","protocol":"vless"},{"tag":"direct","protocol":"freedom"}]}"""
        assertTrue(remote.applyPreset(RoutingPreset.RU_DIRECT).first)
        assertEquals(com.xkeen.android.domain.model.RoutingMode.Auto, remote.getRoutingMode())
    }

    @Test fun `presets pointing at a server without the proxy- prefix are still recognised`() = runBlocking {
        val files = Files()
        files.files[Paths.ROUTING] = """{"routing":{"rules":[
            {"type":"field","outboundTag":"my-server","domain":["domain:googlevideo.com"]},
            {"type":"field","outboundTag":"my-server","domain":["domain:aqara.com"]},
            {"type":"field","outboundTag":"direct","domain":["domain:googlevideo.com"]},
            {"type":"field","network":"tcp,udp","outboundTag":"my-server"}]}}"""
        val rc = XrayConfigRemote(files).getRoutingConfig()
        assertTrue(rc.youtubeUnblock)
        assertTrue(rc.aqaraEnabled)
    }
}
