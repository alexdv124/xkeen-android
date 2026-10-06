package com.xkeen.android.data.remote

import com.xkeen.android.data.ssh.SshConnection
import com.xkeen.android.data.ssh.SshResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MihomoConfigRemoteTest {
    @Test fun `batch deploy validates before install and reloads once without xray`() = runBlocking {
        val router = FakeRouter()
        val (ok, message) = MihomoConfigRemote(router).addVless(listOf(VLESS_LINK, VLESS_LINK.replace("new.example", "second.example")), "", listOf("PROXY", "AUTO"))
        assertTrue(message, ok)
        assertEquals(1, router.tests)
        assertEquals(1, router.reloads)
        assertEquals(router.files[router.path], router.runningConfig)
        assertEquals(3, entries(parseYaml(router.runningConfig), "proxies").size)
        assertTrue(router.commands.any { it.contains("/proxies/PROXY") && it.contains("proxy-old") })
        assertFalse(router.commands.any { it.contains("xray") || it.contains("xkeen -mihomo") })
        assertEquals(MIHOMO_CONFIG, router.files.entries.single { it.key.endsWith(".bak") }.value)
        assertFalse(router.files.keys.any { it.endsWith(".tmp") })
    }

    @Test fun `failed validation leaves file and running config untouched`() = runBlocking {
        val router = FakeRouter().apply { validConfig = false }
        val (ok, _) = MihomoConfigRemote(router).addVless(listOf(VLESS_LINK), "", listOf("PROXY"))
        assertFalse(ok)
        assertEquals(MIHOMO_CONFIG, router.files[router.path])
        assertEquals(MIHOMO_CONFIG, router.runningConfig)
        assertEquals(0, router.reloads)
        assertFalse(router.files.keys.any { it.endsWith(".tmp") || it.endsWith(".bak") })
    }

    @Test fun `failed reload restores persisted and running config`() = runBlocking {
        val router = FakeRouter().apply { failFirstReload = true }
        val (ok, message) = MihomoConfigRemote(router).addVless(listOf(VLESS_LINK), "", listOf("PROXY"))
        assertFalse(ok)
        assertTrue(message, message.contains("восстановлен"))
        assertEquals(MIHOMO_CONFIG, router.files[router.path])
        assertEquals(MIHOMO_CONFIG, router.runningConfig)
        assertEquals(2, router.reloads)
    }

    @Test fun `uncertain move is rolled back even when write already happened`() = runBlocking {
        val router = FakeRouter().apply { failMoveAfterWriting = true }
        val (ok, _) = MihomoConfigRemote(router).addVless(listOf(VLESS_LINK), "", listOf("PROXY"))
        assertFalse(ok)
        assertEquals(MIHOMO_CONFIG, router.files[router.path])
        assertEquals(MIHOMO_CONFIG, router.runningConfig)
    }

    @Test fun `bad batch performs no writes`() = runBlocking {
        val router = FakeRouter()
        val (ok, _) = MihomoConfigRemote(router).addVless(listOf(VLESS_LINK, "vless://invalid"), "", listOf("PROXY"))
        assertFalse(ok)
        assertEquals(mapOf(router.path to MIHOMO_CONFIG), router.files)
        assertEquals(0, router.tests)
    }

    @Test fun `unavailable API performs no writes`() = runBlocking {
        val router = FakeRouter().apply { apiAvailable = false }
        val (ok, _) = MihomoConfigRemote(router).addVless(listOf(VLESS_LINK), "", listOf("PROXY"))
        assertFalse(ok)
        assertEquals(mapOf(router.path to MIHOMO_CONFIG), router.files)
    }

    @Test fun `concurrent external edit is not overwritten`() = runBlocking {
        val router = FakeRouter().apply { changeDuringTest = true }
        val (ok, _) = MihomoConfigRemote(router).addVless(listOf(VLESS_LINK), "", listOf("PROXY"))
        assertFalse(ok)
        assertEquals("# external change\n$MIHOMO_CONFIG", router.files[router.path])
        assertEquals(0, router.reloads)
    }

    @Test fun `failed rollback is reported with backup location`() = runBlocking {
        val router = FakeRouter().apply { failFirstReload = true; failRollback = true }
        val (ok, message) = MihomoConfigRemote(router).addVless(listOf(VLESS_LINK), "", listOf("PROXY"))
        assertFalse(ok)
        assertTrue(message, message.contains("Резервная копия:"))
        assertEquals(MIHOMO_CONFIG, router.files.entries.single { it.key.endsWith(".bak") }.value)
    }

    @Test fun `applying routing after import keeps Mihomo servers and groups`() = runBlocking {
        val router = FakeRouter()
        val remote = MihomoConfigRemote(router)
        assertTrue(remote.addVless(listOf(VLESS_LINK), "", listOf("PROXY", "AUTO")).first)
        val before = parseYaml(router.runningConfig)
        router.files[Paths.ROUTING] = """{"routing":{"rules":[{"network":"tcp,udp","outboundTag":"direct"}]}}"""
        val (ok, message) = remote.applyRoutingFromXray()
        assertTrue(message, ok)
        val after = parseYaml(router.runningConfig)
        assertEquals(before.filterKeys { it != "rules" }, after.filterKeys { it != "rules" })
        assertEquals(listOf("MATCH,DIRECT"), after["rules"])
    }
    @Test fun `bulk delete applies once and selects surviving member instead of deleted active node`() = runBlocking {
        val initial = MihomoConfigEditor().addVless(MIHOMO_CONFIG, listOf(VLESS_LINK, VLESS_LINK.replace("new.example", "second.example")), "", listOf("PROXY", "AUTO"))
        val router = FakeRouter(initial)
        val (ok, message) = MihomoConfigRemote(router).removeProxies(setOf("proxy-old", "proxy-new"))
        assertTrue(message, ok)
        assertEquals(1, router.tests)
        assertEquals(1, router.reloads)
        assertEquals(listOf("proxy-second"), entries(parseYaml(router.runningConfig), "proxies").map { it["name"] })
        assertTrue(router.commands.any { "/proxies/PROXY" in it && "AUTO" in it })
        assertFalse(router.commands.any { "/proxies/PROXY" in it && "proxy-old" in it })
    }

    @Test fun `failed bulk delete reload restores both nodes and original selection`() = runBlocking {
        val initial = MihomoConfigEditor().addVless(MIHOMO_CONFIG, listOf(VLESS_LINK, VLESS_LINK.replace("new.example", "second.example")), "", listOf("PROXY", "AUTO"))
        val router = FakeRouter(initial).apply { failFirstReload = true }
        val (ok, _) = MihomoConfigRemote(router).removeProxies(setOf("proxy-old", "proxy-new"))
        assertFalse(ok)
        assertEquals(initial, router.runningConfig)
        assertEquals(initial, router.files[router.path])
        assertTrue(router.commands.any { "/proxies/PROXY" in it && "proxy-old" in it })
    }

    @Test fun `deleting final node makes no remote changes`() = runBlocking {
        val router = FakeRouter()
        assertFalse(MihomoConfigRemote(router).removeProxies(setOf("proxy-old")).first)
        assertEquals(mapOf(router.path to MIHOMO_CONFIG), router.files)
        assertEquals(0, router.tests)
    }

}

private class FakeRouter(initial: String = MIHOMO_CONFIG) : SshConnection {
    val path = "/opt/etc/mihomo/config.yaml"
    val files = mutableMapOf(path to initial)
    val commands = mutableListOf<String>()
    var runningConfig = initial
    var validConfig = true
    var apiAvailable = true
    var failFirstReload = false
    var failMoveAfterWriting = false
    var failRollback = false
    var changeDuringTest = false
    var tests = 0
    var reloads = 0
    private var configBeforeStage = initial

    override suspend fun readFile(path: String): String = files.getValue(path)
    override suspend fun writeFileB64(path: String, content: String) {
        configBeforeStage = files.getValue(this.path)
        files[path] = content
    }

    override suspend fun exec(cmd: String, timeout: Int): SshResult {
        commands += cmd
        fun out(value: String = "", exit: Int = 0) = SshResult(value, "", exit)
        fun paths() = Regex("'([^']*)'").findAll(cmd).map { it.groupValues[1] }.toList()
        return when {
            cmd.startsWith("for f in") -> out(path)
            cmd.startsWith("command -v curl") -> out("/opt/bin/curl")
            cmd.startsWith("grep -E") -> out("external-controller: 127.0.0.1:9090\nsecret: keep-me")
            cmd.startsWith("curl") && "/version'" in cmd -> out(if (apiAvailable) "{\"version\":\"test\"}\n200" else "\n000")
            cmd.startsWith("curl") && "/proxies'" in cmd -> {
                val members = entries(parseYaml(runningConfig), "proxy-groups").first { it["name"] == "PROXY" }["proxies"] as List<*>
                out(buildJsonObject { putJsonObject("proxies") { putJsonObject("PROXY") {
                    put("type", "Selector"); put("now", "proxy-old")
                    put("all", JsonArray(members.map { JsonPrimitive(it.toString()) }))
                } } }.toString() + "\n200")
            }
            cmd.startsWith("curl") && "/configs?" in cmd -> {
                reloads++
                // Simulate a timeout/error after the server already applied the file.
                runningConfig = files.getValue(path)
                out(if (failFirstReload && reloads == 1) "error\n500" else "\n204")
            }
            cmd.startsWith("curl") && "/proxies/PROXY'" in cmd -> out("\n204")
            cmd.startsWith("mihomo -t") -> {
                tests++
                assertEquals(configBeforeStage, files[path])
                assertTrue(entries(parseYaml(files.getValue(paths().last())), "proxies").isNotEmpty())
                if (changeDuringTest) files[path] = "# external change\n$MIHOMO_CONFIG"
                out(if (validConfig) "test successful" else "invalid config", if (validConfig) 0 else 1)
            }
            cmd.startsWith("cp -p") -> {
                val (from, to) = paths()
                if (failRollback && from.endsWith(".bak")) out(exit = 1)
                else { files[to] = files.getValue(from); out() }
            }
            cmd.startsWith("mv ") -> {
                val (from, to) = paths()
                files[to] = files.remove(from)!!
                out(exit = if (failMoveAfterWriting) -1 else 0)
            }
            cmd.startsWith("rm -f") -> { files.remove(paths().single()); out() }
            else -> error("Unexpected router command: $cmd")
        }
    }
}
