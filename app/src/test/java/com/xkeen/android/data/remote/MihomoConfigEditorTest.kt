package com.xkeen.android.data.remote

import org.junit.Assert.*
import org.junit.Test
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

internal const val VLESS_LINK = "vless://11111111-1111-1111-1111-111111111111@new.example.com:443?type=tcp&security=tls&sni=example.com#New"
internal val MIHOMO_CONFIG = """
    redir-port: 4000
    tproxy-port: 4001
    external-controller: 127.0.0.1:9090
    secret: "keep-me"
    dns:
      nameserver: [https://1.1.1.1/dns-query]
    proxies:
      - {name: proxy-old, type: vless, server: old.example.com, port: 443, uuid: old, tls: true}
    proxy-groups:
      - {name: PROXY, type: select, proxies: [AUTO, proxy-old]}
      - {name: AUTO, type: url-test, proxies: [proxy-old], url: https://example.com/test, interval: 90}
      - {name: Work, type: select, proxies: [proxy-old]}
    proxy-providers:
      subscription: {type: file, path: ./providers/work.yaml}
    rules:
      - DOMAIN-SUFFIX,example.org,DIRECT
      - MATCH,PROXY
""".trimIndent()

internal fun parseYaml(raw: String): Map<String, Any?> = Yaml(SafeConstructor(LoaderOptions())).load(raw)

@Suppress("UNCHECKED_CAST")
internal fun entries(config: Map<String, Any?>, key: String): List<Map<String, Any?>> =
    config[key] as List<Map<String, Any?>>

class MihomoConfigEditorTest {
    private val editor = MihomoConfigEditor()

    @Test fun `batch import preserves existing settings and adds nodes to selected groups`() {
        val result = parseYaml(editor.addVless(MIHOMO_CONFIG, listOf(VLESS_LINK, VLESS_LINK.replace("new.example", "second.example")), "", listOf("PROXY", "AUTO")))
        val before = parseYaml(MIHOMO_CONFIG)
        for (key in listOf("redir-port", "tproxy-port", "external-controller", "secret", "dns", "rules", "proxy-providers")) {
            assertEquals(key, before[key], result[key])
        }
        val proxies = entries(result, "proxies")
        assertEquals(3, proxies.size)
        assertEquals(entries(before, "proxies").first(), proxies.first())
        assertEquals("new.example.com", proxies[1]["server"])
        assertEquals(true, proxies[1]["tls"])
        assertFalse(proxies[1].containsKey("reality-opts"))
        assertFalse(proxies[1].containsKey("flow"))
        val groups = entries(result, "proxy-groups")
        assertEquals(listOf("AUTO", "proxy-old", "proxy-new", "proxy-second"), groups[0]["proxies"])
        assertEquals(listOf("proxy-old", "proxy-new", "proxy-second"), groups[1]["proxies"])
        assertEquals(entries(before, "proxy-groups")[2], groups[2])
        assertEquals(90, groups[1]["interval"])
    }

    @Test fun `automatic names are unique across existing nodes and batch`() {
        val result = parseYaml(editor.addVless(MIHOMO_CONFIG, listOf(VLESS_LINK.replace("new.example", "old.example"), VLESS_LINK.replace("new.example", "old.example")), "", listOf("Work")))
        assertEquals(listOf("proxy-old", "proxy-old-2", "proxy-old-3"), entries(result, "proxies").map { it["name"] })
    }

    @Test fun `explicit duplicate and reserved names are rejected`() {
        for (name in listOf("proxy-old", "AUTO", "DIRECT")) {
            assertThrows(IllegalArgumentException::class.java) { editor.addVless(MIHOMO_CONFIG, listOf(VLESS_LINK), name, listOf("PROXY")) }
        }
    }

    @Test fun `custom names survive yaml quoting`() {
        val name = "Москва 'один' \"два\" \\ # test"
        val result = parseYaml(editor.addVless(MIHOMO_CONFIG, listOf(VLESS_LINK), name, listOf("Work")))
        assertEquals(name, entries(result, "proxies").last()["name"])
        assertEquals(name, (entries(result, "proxy-groups").last()["proxies"] as List<*>).last())
    }

    @Test fun `xhttp reality options and short id survive conversion`() {
        val link = VLESS_LINK.substringBefore('?') + "?type=xhttp&security=reality&pbk=publicKey&sid=001234&mode=auto&path=%2Fstream&sni=sni.example"
        val result = parseYaml(editor.addVless(MIHOMO_CONFIG, listOf(link), "", listOf("AUTO")))
        val proxy = entries(result, "proxies").last()
        assertEquals("xhttp", proxy["network"])
        assertEquals("001234", (proxy["reality-opts"] as Map<*, *>)["short-id"])
        val xhttp = proxy["xhttp-opts"] as Map<*, *>
        assertEquals("auto", xhttp["mode"])
        assertEquals("/stream", xhttp["path"])
        assertFalse(xhttp.containsKey("download-settings"))
    }

    @Test fun `bad link anywhere in batch rejects entire edit`() {
        assertThrows(IllegalArgumentException::class.java) { editor.addVless(MIHOMO_CONFIG, listOf(VLESS_LINK, "vless://invalid"), "", listOf("PROXY")) }
        assertThrows(IllegalArgumentException::class.java) { editor.addVless(MIHOMO_CONFIG, listOf(VLESS_LINK.replace("type=tcp", "type=ws")), "", listOf("PROXY")) }
    }

    @Test fun `missing groups and duplicate yaml keys reject edits`() {
        assertThrows(IllegalArgumentException::class.java) { editor.addVless(MIHOMO_CONFIG, listOf(VLESS_LINK), "", listOf("Missing")) }
        assertThrows(Exception::class.java) { editor.groups("proxy-groups: []\nproxy-groups: []") }
    }

    @Test fun `routing changes keep directly imported servers and other settings`() {
        val withNew = editor.addVless(MIHOMO_CONFIG, listOf(VLESS_LINK), "", listOf("PROXY"))
        val rules = listOf("GEOIP,RU,DIRECT,no-resolve", "MATCH,PROXY")
        val updated = parseYaml(editor.replaceRules(withNew, rules))
        assertEquals(parseYaml(withNew).filterKeys { it != "rules" }, updated.filterKeys { it != "rules" })
        assertEquals(rules, updated["rules"])
    }

    @Test fun `provider groups and automatic inclusion preserve their behavior`() {
        val raw = MIHOMO_CONFIG.replace("{name: Work, type: select, proxies: [proxy-old]}", "{name: Work, type: select, include-all-proxies: true, filter: '.*', use: [subscription]}")
        val result = parseYaml(editor.addVless(raw, listOf(VLESS_LINK), "", listOf("Work")))
        assertEquals(entries(parseYaml(raw), "proxy-groups").last(), entries(result, "proxy-groups").last())
    }
    @Test fun `bulk delete cleans every group and redirects direct node rules`() {
        val initial = editor.addVless(MIHOMO_CONFIG, listOf(VLESS_LINK, VLESS_LINK.replace("new.example", "second.example")), "", listOf("PROXY", "AUTO"))
            .replace("MATCH,PROXY", "IP-CIDR,1.1.1.0/24,proxy-old,no-resolve")
        val result = parseYaml(editor.removeProxies(initial, setOf("proxy-old", "proxy-new")))
        assertEquals(listOf("proxy-second"), entries(result, "proxies").map { it["name"] })
        val groups = entries(result, "proxy-groups")
        assertEquals(listOf("AUTO", "proxy-second"), groups[0]["proxies"])
        assertEquals(listOf("proxy-second"), groups[1]["proxies"])
        assertEquals(listOf("proxy-second"), groups[2]["proxies"])
        assertEquals("IP-CIDR,1.1.1.0/24,proxy-second,no-resolve", (result["rules"] as List<*>).last())
        for (key in listOf("dns", "secret", "redir-port", "proxy-providers")) {
            assertEquals(parseYaml(initial)[key], result[key])
        }
    }

    @Test fun `delete protects last node builtins and provider nodes`() {
        for (names in listOf(setOf("proxy-old"), setOf("DIRECT"), setOf("subscription-node"), emptySet())) {
            assertThrows(IllegalArgumentException::class.java) { editor.removeProxies(MIHOMO_CONFIG, names) }
        }
    }

    @Test fun `delete rejects leaving a dangling dialer dependency`() {
        val initial = editor.addVless(MIHOMO_CONFIG, listOf(VLESS_LINK), "", listOf("PROXY"))
            .replace("server: new.example.com", "server: new.example.com\n  dialer-proxy: proxy-old")
        assertThrows(IllegalArgumentException::class.java) { editor.removeProxies(initial, setOf("proxy-old")) }
    }

    @Test fun `delete preserves dynamic provider group without adding unrelated node`() {
        val initial = editor.addVless(MIHOMO_CONFIG.replace("{name: Work, type: select, proxies: [proxy-old]}",
            "{name: Work, type: select, proxies: [proxy-old], use: [subscription]}"), listOf(VLESS_LINK), "", listOf("PROXY"))
        val result = parseYaml(editor.removeProxies(initial, setOf("proxy-old")))
        assertEquals(emptyList<String>(), entries(result, "proxy-groups").last()["proxies"])
        assertEquals(listOf("subscription"), entries(result, "proxy-groups").last()["use"])
    }

}
