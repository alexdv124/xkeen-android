package com.xkeen.android.data.remote

import com.xkeen.android.data.ssh.SshConnection
import com.xkeen.android.data.ssh.SshResult
import org.junit.Assert.*
import org.junit.Test

private const val SUB_URL = "https://sub.example.com/api/sub/abc123?client=mihomo"

class SubscriptionEditorTest {
    private val editor = MihomoConfigEditor()

    @Test fun `enable adds provider groups listener and bench rule while preserving the rest`() {
        val before = parseYaml(MIHOMO_CONFIG)
        val result = parseYaml(editor.enableSubscription(MIHOMO_CONFIG, SUB_URL))
        for (key in listOf("redir-port", "tproxy-port", "external-controller", "secret", "dns", "proxies")) {
            assertEquals(key, before[key], result[key])
        }
        @Suppress("UNCHECKED_CAST")
        val providers = result["proxy-providers"] as Map<String, Map<String, Any?>>
        assertEquals((before["proxy-providers"] as Map<*, *>)["subscription"], providers["subscription"])
        val sub = providers.getValue(SubscriptionNames.PROVIDER)
        assertEquals("http", sub["type"])
        assertEquals(SUB_URL, sub["url"])
        assertEquals(SubscriptionNames.PROVIDER_PATH, sub["path"])

        val groups = entries(result, "proxy-groups").associateBy { it["name"] }
        assertEquals(listOf(SubscriptionNames.BEST, SubscriptionNames.ALL, "AUTO", "proxy-old"), groups.getValue("PROXY")["proxies"])
        assertEquals(listOf(SubscriptionNames.PROVIDER), groups.getValue(SubscriptionNames.BEST)["use"])
        assertEquals("url-test", groups.getValue(SubscriptionNames.BEST)["type"])
        assertEquals("select", groups.getValue(SubscriptionNames.BENCH)["type"])
        assertEquals(true, groups.getValue(SubscriptionNames.BENCH)["hidden"])
        assertEquals(entries(before, "proxy-groups")[2], groups.getValue("Work"))

        val listener = entries(result, "listeners").single()
        assertEquals("127.0.0.1", listener["listen"])
        assertEquals(SubscriptionNames.BENCH_PORT, listener["port"])

        @Suppress("UNCHECKED_CAST")
        val rules = result["rules"] as List<String>
        assertEquals(SubscriptionNames.BENCH_RULE, rules.first())
        assertEquals(before["rules"], rules.drop(1))
    }

    @Test fun `enable twice is idempotent and keeps the benchmark filter`() {
        val once = editor.enableSubscription(MIHOMO_CONFIG, SUB_URL)
        val withFilter = once.replace("name: SUB-BEST", "name: SUB-BEST\n  filter: ^(?:a|b)$")
        val parsed = parseYaml(withFilter)
        assertEquals("^(?:a|b)$", entries(parsed, "proxy-groups").first { it["name"] == SubscriptionNames.BEST }["filter"])
        val twice = parseYaml(editor.enableSubscription(withFilter, "https://other.example.com/sub"))
        val groups = entries(twice, "proxy-groups")
        assertEquals(1, groups.count { it["name"] == SubscriptionNames.BEST })
        assertEquals("^(?:a|b)$", groups.first { it["name"] == SubscriptionNames.BEST }["filter"])
        assertEquals(listOf(SubscriptionNames.BEST, SubscriptionNames.ALL, "AUTO", "proxy-old"), groups.first { it["name"] == "PROXY" }["proxies"])
        assertEquals(1, entries(twice, "listeners").size)
        @Suppress("UNCHECKED_CAST")
        assertEquals(1, (twice["rules"] as List<String>).count { it == SubscriptionNames.BENCH_RULE })
        assertEquals("https://other.example.com/sub", editor.subscriptionUrl(editor.enableSubscription(withFilter, "https://other.example.com/sub")))
    }

    @Test fun `disable restores the original structure`() {
        val enabled = editor.enableSubscription(MIHOMO_CONFIG, SUB_URL)
        val disabled = parseYaml(editor.disableSubscription(enabled))
        val before = parseYaml(MIHOMO_CONFIG)
        assertEquals(before["rules"], disabled["rules"])
        assertEquals(before["proxy-groups"], disabled["proxy-groups"])
        assertEquals(before["proxy-providers"], disabled["proxy-providers"])
        assertFalse(disabled.containsKey("listeners"))
        assertNull(editor.subscriptionUrl(editor.disableSubscription(enabled)))
    }

    @Test fun `disable is refused when a group would be left without servers`() {
        val enabled = editor.enableSubscription(MIHOMO_CONFIG, SUB_URL)
        val onlySubscription = editor.removeProxies(enabled, setOf("proxy-old"))
        val error = runCatching { editor.disableSubscription(onlySubscription) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty(), error?.message.orEmpty().contains("не останется серверов"))
    }

    @Test fun `with a subscription every manual server may be deleted`() {
        val enabled = editor.enableSubscription(MIHOMO_CONFIG, SUB_URL)
        val result = parseYaml(editor.removeProxies(enabled, setOf("proxy-old")))
        val groups = entries(result, "proxy-groups").associateBy { it["name"] }
        assertEquals(listOf(SubscriptionNames.BEST, SubscriptionNames.ALL, "AUTO"), groups.getValue("PROXY")["proxies"])
        assertEquals(listOf(SubscriptionNames.BEST), groups.getValue("AUTO")["proxies"])
        assertEquals(listOf(SubscriptionNames.BEST), groups.getValue("Work")["proxies"])
    }

    @Test fun `routing presets keep the bench rule first`() {
        val enabled = editor.enableSubscription(MIHOMO_CONFIG, SUB_URL)
        @Suppress("UNCHECKED_CAST")
        val rules = parseYaml(editor.replaceRules(enabled, listOf("GEOSITE,category-ru,DIRECT", "MATCH,PROXY")))["rules"] as List<String>
        assertEquals(listOf(SubscriptionNames.BENCH_RULE, "GEOSITE,category-ru,DIRECT", "MATCH,PROXY"), rules)
        @Suppress("UNCHECKED_CAST")
        val plain = parseYaml(editor.replaceRules(MIHOMO_CONFIG, listOf("MATCH,PROXY")))["rules"] as List<String>
        assertEquals(listOf("MATCH,PROXY"), plain)
    }

    @Test fun `managed groups are not offered as import targets and name clashes are refused`() {
        val enabled = editor.enableSubscription(MIHOMO_CONFIG, SUB_URL)
        assertEquals(listOf("PROXY", "AUTO", "Work"), editor.groups(enabled))
        val clash = MIHOMO_CONFIG.replace("name: Work", "name: SUB-ALL")
        assertTrue(runCatching { editor.enableSubscription(clash, SUB_URL) }.isFailure)
    }

    @Test fun `config without PROXY gets one`() {
        val noProxy = MIHOMO_CONFIG.replace("{name: PROXY, type: select, proxies: [AUTO, proxy-old]}", "{name: MAIN, type: select, proxies: [AUTO, proxy-old]}")
            .replace("MATCH,PROXY", "MATCH,MAIN")
        val groups = entries(parseYaml(editor.enableSubscription(noProxy, SUB_URL)), "proxy-groups")
        assertEquals(listOf(SubscriptionNames.BEST, SubscriptionNames.ALL), groups.first { it["name"] == "PROXY" }["proxies"])
    }

    @Test fun `regenerating the config keeps the subscription and its benchmark filter`() {
        val withSub = editor.enableSubscription(MIHOMO_CONFIG, SUB_URL)
            .replace("name: SUB-BEST", "name: SUB-BEST\n  filter: ^(?:a|b)$")
        val regenerated = MIHOMO_CONFIG.replace("proxy-old", "proxy-fresh")
        val carried = parseYaml(editor.carrySubscription(withSub, regenerated))
        assertEquals(SUB_URL, editor.subscriptionUrl(editor.carrySubscription(withSub, regenerated)))
        val groups = entries(carried, "proxy-groups").associateBy { it["name"] }
        assertEquals("^(?:a|b)$", groups.getValue(SubscriptionNames.BEST)["filter"])
        assertEquals(listOf(SubscriptionNames.BEST, SubscriptionNames.ALL, "AUTO", "proxy-fresh"), groups.getValue("PROXY")["proxies"])
        // No subscription before: the generated config is returned untouched.
        assertSame(regenerated, editor.carrySubscription(MIHOMO_CONFIG, regenerated))
    }

    @Test fun `subscription urls are validated`() {
        assertTrue(isValidSubscriptionUrl(SUB_URL))
        assertTrue(isValidSubscriptionUrl("http://1.2.3.4:8080/sub"))
        for (bad in listOf("", "ftp://x.y/sub", "vless://abc@host:443", "https://", "https://a b/c", "https://x.y/\nrm")) {
            assertFalse(bad, isValidSubscriptionUrl(bad))
            assertTrue(bad, runCatching { editor.enableSubscription(MIHOMO_CONFIG, bad) }.isFailure)
        }
    }
}

class SubscriptionRemoteTest {
    private val remote = SubscriptionRemote(object : SshConnection {
        override suspend fun exec(cmd: String, timeout: Int) = SshResult("", "", 0)
        override suspend fun readFile(path: String) = ""
        override suspend fun writeFileB64(path: String, content: String) {}
    })

    @Test fun `settings validate time and count`() {
        val s = SubscriptionSettings(10, "4:05")
        assertEquals(4, s.hour)
        assertEquals(5, s.minute)
        for (bad in listOf("24:00", "12:60", "1200", "", "aa:bb")) {
            assertTrue(bad, runCatching { SubscriptionSettings(10, bad) }.isFailure)
        }
        assertTrue(runCatching { SubscriptionSettings(0, "04:00") }.isFailure)
        assertTrue(runCatching { SubscriptionSettings(31, "04:00") }.isFailure)
    }

    @Test fun `cron runs daily at the chosen time plus a half-hourly check`() {
        assertEquals(
            listOf(
                "30 3 * * * ${SubscriptionNames.SCRIPT} daily >/dev/null 2>&1",
                "*/30 * * * * ${SubscriptionNames.SCRIPT} check >/dev/null 2>&1"
            ),
            remote.cronLines(SubscriptionSettings(10, "03:30"))
        )
    }

    @Test fun `conf quotes the config path for the shell`() {
        val conf = remote.buildConf("/opt/etc/mihomo/it's.yaml", SubscriptionSettings(7, "05:00"))
        assertTrue(conf, conf.contains("TOP_N=7\n"))
        assertTrue(conf, conf.contains("CANDIDATES=15\n"))
        assertTrue(conf, conf.contains("DAILY_TIME='05:00'\n"))
        assertTrue(conf, conf.contains("MIHOMO_CONFIG='/opt/etc/mihomo/it'\\''s.yaml'\n"))
    }

    @Test fun `status json from the router script is parsed`() {
        val body = """
            {"running":false,"last_update":"1762700000","last_bench":"1762700300","last_error":"",
             "log":"line1\nline2",
             "result":{"ts":1762700300,"servers":[
               {"name":"Sweden #4","delay":40,"down":276.0,"up":183.5,"chosen":true},
               {"name":"Estonia #2","delay":34,"down":2.0,"up":9.0,"chosen":false},
               {"name":"Poland","delay":45,"down":268.0,"up":122.0,"chosen":true}]}}
        """.trimIndent()
        val status = remote.parseStatusJson(body, SubscriptionStatus(enabled = true, url = SUB_URL))
        assertTrue(status.enabled)
        assertEquals(1762700000L, status.lastUpdate)
        assertEquals(1762700300L, status.lastBench)
        assertEquals(listOf("Sweden #4", "Poland"), status.chosen.map { it.name })
        assertEquals("Estonia #2", status.servers.last().name)
        assertEquals(183.5, status.chosen.first().upMbit, 0.01)
    }

    @Test fun `status without results or with garbage keeps the base`() {
        val base = SubscriptionStatus(enabled = true, url = SUB_URL)
        assertEquals(base, remote.parseStatusJson("", base))
        assertEquals(base, remote.parseStatusJson("ERROR: mihomo config not found", base))
        val running = remote.parseStatusJson("""{"running":true,"last_update":"","last_bench":"","last_error":"","log":"","result":null}""", base)
        assertTrue(running.running)
        assertTrue(running.servers.isEmpty())
    }
}

class VersionCompareTest {
    @Test fun `normalizes and compares versions`() {
        assertEquals("1.19.33", VersionCompare.normalize("Mihomo Meta v1.19.33 linux arm64 with go1.26.10"))
        assertEquals("2.1", VersionCompare.normalize("XKeen 2.1 Stable"))
        assertTrue(VersionCompare.compare("v1.19.34", "1.19.33") > 0)
        assertTrue(VersionCompare.compare("1.19.9", "1.19.10") < 0)
        assertEquals(0, VersionCompare.compare("2.1", "2.1.0"))
        assertTrue(VersionCompare.compare("2.2", "2.1") > 0)
    }

    @Test fun `update availability`() {
        assertTrue(ComponentVersion("Mihomo", "1.19.33", "1.19.34").updateAvailable)
        assertFalse(ComponentVersion("Mihomo", "1.19.33", "1.19.33").updateAvailable)
        assertFalse(ComponentVersion("Mihomo", "1.19.33", "").updateAvailable)
        assertFalse(ComponentVersion("Mihomo", "", "1.19.34").updateAvailable)
    }
}
