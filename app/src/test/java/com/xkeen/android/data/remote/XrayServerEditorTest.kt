package com.xkeen.android.data.remote

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal val XRAY_CONFIGS = linkedMapOf(
    Paths.OUTBOUNDS to """{"outbounds":[
        {"tag":"proxy-a","protocol":"vless","settings":{"secret":"a"}},
        {"tag":"proxy-b","protocol":"vless","settings":{"secret":"b"}},
        {"tag":"proxy-c","protocol":"vless","settings":{"secret":"c"}},
        {"tag":"direct","protocol":"freedom"},{"tag":"block","protocol":"blackhole"}]}""",
    Paths.ROUTING to """{"routing":{"domainStrategy":"IPIfNonMatch","rules":[
        {"domain":["domain:example.com"],"outboundTag":"proxy-a"},
        {"ip":["geoip:ru"],"outboundTag":"direct"},
        {"network":"tcp,udp","balancerTag":"proxy-balancer"}],
        "balancers":[{"tag":"proxy-balancer","selector":["proxy-a","proxy-b"],"fallbackTag":"proxy-a","strategy":{"type":"leastPing"}}]}}""",
    Paths.OBSERVATORY to """{"burstObservatory":{"subjectSelector":["proxy-a","proxy-b"],"pingConfig":{"interval":"60s"}}}"""
)

internal fun obj(raw: String) = Json.parseToJsonElement(raw).jsonObject

class XrayServerEditorTest {
    private val editor = XrayServerEditor()

    @Test fun `bulk removal cleans balancing manual routes and observatory`() {
        val result = editor.remove(XRAY_CONFIGS, setOf("proxy-a", "proxy-b"))
        val outbounds = obj(result.getValue(Paths.OUTBOUNDS))["outbounds"]!!.jsonArray
        assertEquals(listOf("proxy-c", "direct", "block"), outbounds.map { it.jsonObject["tag"]!!.jsonPrimitive.content })
        val routing = obj(result.getValue(Paths.ROUTING))["routing"]!!.jsonObject
        assertEquals("IPIfNonMatch", routing["domainStrategy"]!!.jsonPrimitive.content)
        assertEquals(0, routing["balancers"]!!.jsonArray.size)
        assertEquals(listOf("proxy-c", "direct", "proxy-c"), routing["rules"]!!.jsonArray.map { it.jsonObject["outboundTag"]!!.jsonPrimitive.content })
        val observer = obj(result.getValue(Paths.OBSERVATORY))["burstObservatory"]!!.jsonObject
        assertEquals(listOf("proxy-c"), observer["subjectSelector"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("60s", observer["pingConfig"]!!.jsonObject["interval"]!!.jsonPrimitive.content)
    }

    @Test fun `single removal preserves valid balancer and replaces fallback`() {
        val result = editor.remove(XRAY_CONFIGS, setOf("proxy-a"))
        val balancer = obj(result.getValue(Paths.ROUTING))["routing"]!!.jsonObject["balancers"]!!.jsonArray.single().jsonObject
        assertEquals(listOf("proxy-b"), balancer["selector"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("proxy-b", balancer["fallbackTag"]!!.jsonPrimitive.content)
    }

    @Test fun `selector prefix remains when it still matches a surviving node`() {
        val original = XRAY_CONFIGS.mapValues { it.value.replace("proxy-b", "proxy-a2") }
        val result = editor.remove(original, setOf("proxy-a"))
        val balancer = obj(result.getValue(Paths.ROUTING))["routing"]!!.jsonObject["balancers"]!!.jsonArray.single().jsonObject
        assertEquals(listOf("proxy-a", "proxy-a2"), balancer["selector"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun `last server internal outbounds and unknown tags are protected`() {
        for (tags in listOf(setOf("proxy-a", "proxy-b", "proxy-c"), setOf("direct"), setOf("missing"), emptySet())) {
            assertThrows(IllegalArgumentException::class.java) { editor.remove(XRAY_CONFIGS, tags) }
        }
    }

    @Test fun `remaining dialer dependencies block deletion`() {
        val original = XRAY_CONFIGS.toMutableMap()
        original[Paths.OUTBOUNDS] = original.getValue(Paths.OUTBOUNDS).replace("\"secret\":\"c\"}", "\"secret\":\"c\"},\"streamSettings\":{\"sockopt\":{\"dialerProxy\":\"proxy-a\"}}")
        assertThrows(IllegalArgumentException::class.java) { editor.remove(original, setOf("proxy-a")) }
    }

    @Test fun `configs without observatory or balancers support deletion`() {
        val original = XRAY_CONFIGS.filterKeys { it != Paths.OBSERVATORY }.toMutableMap()
        original[Paths.ROUTING] = """{"routing":{"rules":[{"outboundTag":"proxy-a","network":"tcp,udp"}]}}"""
        val result = editor.remove(original, setOf("proxy-a", "proxy-b"))
        assertFalse(result.containsKey(Paths.OBSERVATORY))
        val routing = obj(result.getValue(Paths.ROUTING))["routing"]!!.jsonObject
        assertFalse(routing.containsKey("balancers"))
        assertEquals("proxy-c", routing["rules"]!!.jsonArray.single().jsonObject["outboundTag"]!!.jsonPrimitive.content)
    }
}
