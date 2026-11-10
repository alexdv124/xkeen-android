package com.xkeen.android.data.remote

import com.xkeen.android.data.ssh.SshConnection
import com.xkeen.android.data.ssh.SshResult
import com.xkeen.android.domain.model.CustomRoute
import com.xkeen.android.domain.model.RoutingPreset
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class XraySubscriptionTest {
    private class Files : SshConnection {
        val files = (XRAY_CONFIGS + (XraySubscriptionRemote.CONFIG to """{"outbounds":[{"tag":"proxy-sub-best-test","protocol":"vless","settings":{"vnext":[{"address":"sub.example","port":443,"users":[{"id":"id"}]}]}}]}""")).toMutableMap()
        override suspend fun readFile(path: String) = files[path].orEmpty()
        override suspend fun writeFileB64(path: String, content: String) { files[path] = content }
        override suspend fun exec(cmd: String, timeout: Int) = SshResult(if (cmd.startsWith("test -s ${XraySubscriptionRemote.CONFIG}")) "XK_SUB" else "", "", 0)
    }

    @Test fun `managed servers are visible alongside manual servers`() = runBlocking {
        val list = XrayConfigRemote(Files()).getProxyList()
        assertTrue(list.any { it.tag == "proxy-a" })
        assertEquals("sub.example", list.single { it.tag == "proxy-sub-best-test" }.address)
    }

    @Test fun `geo routes survive presets but all direct disables them and explicit direct wins`() = runBlocking {
        val files = Files(); val remote = XrayConfigRemote(files)
        val custom = listOf(CustomRoute("chatgpt.com", "direct", routeType = "domain"))
        for (preset in listOf(RoutingPreset.RU_DIRECT, RoutingPreset.ALL_DIRECT, RoutingPreset.ALL_VPN)) {
            assertTrue(remote.applyPreset(preset, custom).first)
            val rules = XrayJson.parseObject(files.files.getValue(Paths.ROUTING))["routing"]!!.jsonObject["rules"]!!.jsonArray
            val geo = rules.indexOfFirst { it.jsonObject["balancerTag"]?.jsonPrimitive?.content == XraySubscriptionRemote.GEO_BALANCER }
            if (preset == RoutingPreset.ALL_DIRECT) assertEquals(-1, geo)
            else {
                val direct = rules.indexOfFirst { it.jsonObject["domain"]?.jsonArray?.any { d -> d.jsonPrimitive.content == "domain:chatgpt.com" } == true && it.jsonObject["outboundTag"]?.jsonPrimitive?.content == "direct" }
                assertTrue(direct >= 0 && direct < geo)
            }
            assertFalse(remote.getRoutingConfig().customRoutes.any { it.value == "anthropic.com" })
        }
    }

    @Test fun `deleting manual server preserves subscription balancer and observers`() {
        val config = XRAY_CONFIGS.toMutableMap()
        config[Paths.ROUTING] = """{"routing":{"balancers":[{"tag":"proxy-balancer","selector":["proxy-sub-best-"]}],"rules":[{"network":"tcp,udp","balancerTag":"proxy-balancer"}]}}"""
        config[Paths.OBSERVATORY] = """{"observatory":{"subjectSelector":["proxy-sub-"]}}"""
        val managed = Files().files.getValue(XraySubscriptionRemote.CONFIG)
        val edited = XrayServerEditor().remove(config, setOf("proxy-a"), managed)
        assertTrue(edited.getValue(Paths.ROUTING).contains("proxy-sub-best-"))
        assertTrue(edited.getValue(Paths.OBSERVATORY).contains("proxy-sub-"))
        assertFalse(edited.containsKey(XraySubscriptionRemote.CONFIG))
    }

    @Test fun `Xray status shows speed and geo checks using existing card model`() {
        val remote = XraySubscriptionRemote(Files())
        val status = remote.parseStatus("""{"enabled":true,"url":"https://host/sub","settings":{"topN":2,"dailyTime":"05:30"},"scriptInstalled":true,"cronInstalled":true,"running":true,"last_update":123,"geoGroup":true,"result":{"servers":[{"name":"Fast","down":50,"up":20,"delay":10,"chosen":true}]},"geo":{"servers":[{"name":"Geo","ok":true,"reason":"OK"}]}}""")
        assertTrue(status.enabled && status.running && status.cronInstalled && status.geoGroup)
        assertEquals("05:30", status.settings.dailyTime)
        assertEquals("Fast", status.chosen.single().name)
        assertEquals("Geo", status.geoOk.single().name)
    }
}
