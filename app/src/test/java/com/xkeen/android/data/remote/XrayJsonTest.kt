package com.xkeen.android.data.remote

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class XrayJsonTest {
    @Test fun `comments before and inside config preserve JSON values`() {
        val raw = """
            // папмвапмвапм
            {
              /* comment across
                 two lines */ "routing": {"rules": []}, # another comment
              "url": "https://example.com/a//b#part",
              "literal": "/* not a comment */",
              "escaped": "quote\"//still string\\",
              "value": true /* tail */
            } // EOF
        """.trimIndent()
        val obj = XrayJson.parseObject(raw)
        assertEquals("https://example.com/a//b#part", obj["url"]!!.jsonPrimitive.content)
        assertEquals("/* not a comment */", obj["literal"]!!.jsonPrimitive.content)
        assertEquals("quote\"//still string\\", obj["escaped"]!!.jsonPrimitive.content)
        assertTrue(obj["value"]!!.jsonPrimitive.boolean)
        assertEquals(raw.length, XrayJson.withoutComments(raw).length)
        assertEquals(raw.count { it == '\n' }, XrayJson.withoutComments(raw).count { it == '\n' })
    }

    @Test fun `comments cannot hide malformed JSON`() {
        for (raw in listOf("{} /* unclosed", "{\"v\":tr/*x*/ue}", "{\"v\":1,}", "{} // fine\n {}")) {
            assertTrue(raw, runCatching { XrayJson.parseObject(raw) }.isFailure)
        }
    }

    @Test fun `routing conversion for Mihomo retains commented rules`() {
        val raw = """// route header
            {"routing":{"rules":[
              {"domain":["domain:example.org"],"outboundTag":"direct"}, /* direct */
              {"network":"tcp,udp","outboundTag":"proxy-a"}
            ]}}
        """.trimIndent()
        assertEquals(listOf("DOMAIN-SUFFIX,example.org,DIRECT", "MATCH,PROXY"), MihomoConfigBuilder().buildRules(raw))
    }

    @Test fun `Mihomo generation accepts commented outbounds and routing`() {
        val outbounds = """// servers
          {"outbounds":[{"tag":"proxy-one","protocol":"vless",
            "settings":{"vnext":[{"address":"example.com","port":443,"users":[{"id":"test"}]}]},
            "streamSettings":{"network":"tcp","security":"tls","tlsSettings":{"serverName":"example.com"}}
          }]}
        """.trimIndent()
        val config = parseYaml(MihomoConfigBuilder().fromXray(outbounds,
            """/* routing */ {"routing":{"rules":[{"domain":["domain:example.org"],"outboundTag":"direct"}]}}"""))
        assertTrue((config["rules"] as List<*>).contains("DOMAIN-SUFFIX,example.org,DIRECT"))
        assertEquals("example.com", ((config["proxies"] as List<*>).first() as Map<*, *>)["server"])
    }

    @Test fun `server deletion accepts comments in all edited config files`() {
        val configs = XRAY_CONFIGS.mapValues { (_, raw) -> "// header\n/* details */\n$raw\n# footer" }
        val edited = XrayServerEditor().remove(configs, setOf("proxy-a"))
        val remaining = Json.parseToJsonElement(edited.getValue(Paths.OUTBOUNDS)).jsonObject["outbounds"]!!.jsonArray
        assertFalse(remaining.any { it.jsonObject["tag"]!!.jsonPrimitive.content == "proxy-a" })
        assertEquals(3, edited.size)
    }
}
