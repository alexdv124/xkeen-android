package com.xkeen.android.data.remote

import com.xkeen.android.data.ssh.SshConnection
import com.xkeen.android.data.ssh.SshResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class XrayServerRemoteTest {
    @Test fun `batch is tested in staging then applied with one restart`() = runBlocking {
        val router = FakeXrayRouter()
        val (ok, message) = XrayServerRemote(router).removeProxies(setOf("proxy-a", "proxy-b"))
        assertTrue(message, ok)
        assertEquals(1, router.tests)
        assertEquals(1, router.restarts)
        assertEquals(router.liveFiles(), router.running)
        assertFalse(router.running.getValue(Paths.OUTBOUNDS).contains("proxy-a"))
        assertFalse(router.running.getValue(Paths.OUTBOUNDS).contains("proxy-b"))
        assertEquals(XRAY_CONFIGS[Paths.OUTBOUNDS], router.files.entries.single { "/backups/" in it.key && it.key.endsWith("04_outbounds.json") }.value)
        assertFalse(router.files.keys.any { ".android-delete-" in it })
    }

    @Test fun `validation failure changes no live files and does not restart`() = runBlocking {
        val router = FakeXrayRouter().apply { valid = false }
        assertFalse(XrayServerRemote(router).removeProxies(setOf("proxy-a", "proxy-b")).first)
        assertEquals(XRAY_CONFIGS, router.liveFiles())
        assertEquals(XRAY_CONFIGS, router.running)
        assertEquals(0, router.restarts)
    }

    @Test fun `partial write failure restores every affected file`() = runBlocking {
        val router = FakeXrayRouter().apply { failWrite = 2 }
        val (ok, message) = XrayServerRemote(router).removeProxies(setOf("proxy-a", "proxy-b"))
        assertFalse(ok)
        assertTrue(message, message.contains("восстановлен"))
        assertEquals(XRAY_CONFIGS, router.liveFiles())
        assertEquals(XRAY_CONFIGS, router.running)
        assertEquals(1, router.restarts)
    }

    @Test fun `failed restart rolls back the entire batch and restarts original config`() = runBlocking {
        val router = FakeXrayRouter().apply { failFirstRestart = true }
        val (ok, message) = XrayServerRemote(router).removeProxies(setOf("proxy-a", "proxy-b"))
        assertFalse(ok)
        assertTrue(message, message.contains("восстановлен"))
        assertEquals(XRAY_CONFIGS, router.liveFiles())
        assertEquals(XRAY_CONFIGS, router.running)
        assertEquals(2, router.restarts)
    }

    @Test fun `core switch during deletion stops operation before installation`() = runBlocking {
        val router = FakeXrayRouter().apply { coreIsXray = false }
        assertFalse(XrayServerRemote(router).removeProxies(setOf("proxy-a", "proxy-b")).first)
        assertEquals(XRAY_CONFIGS, router.liveFiles())
        assertEquals(0, router.restarts)
    }

    @Test fun `last server is rejected before any writes`() = runBlocking {
        val router = FakeXrayRouter()
        assertFalse(XrayServerRemote(router).removeProxies(setOf("proxy-a", "proxy-b", "proxy-c")).first)
        assertEquals(XRAY_CONFIGS, router.files)
        assertEquals(0, router.tests)
    }

    @Test fun `failed rollback reports backup instead of claiming success`() = runBlocking {
        val router = FakeXrayRouter().apply { failFirstRestart = true; failRollback = true }
        val (ok, message) = XrayServerRemote(router).removeProxies(setOf("proxy-a", "proxy-b"))
        assertFalse(ok)
        assertTrue(message, message.contains("Резервная копия:"))
        assertTrue(router.files.keys.any { "/backups/" in it })
    }
}

private class FakeXrayRouter : SshConnection {
    val files = XRAY_CONFIGS.toMutableMap()
    var running: Map<String, String> = XRAY_CONFIGS
    var valid = true
    var tests = 0
    var restarts = 0
    var failWrite = -1
    var failFirstRestart = false
    var failRollback = false
    var coreIsXray = true
    private var writes = 0

    fun liveFiles() = files.filterKeys { it in XRAY_CONFIGS.keys }
    override suspend fun readFile(path: String): String = files.getValue(path)
    override suspend fun writeFileB64(path: String, content: String) { files[path] = content }

    override suspend fun exec(cmd: String, timeout: Int): SshResult {
        fun result(code: Int = 0, output: String = "") = SshResult(output, "", code)
        val args = Regex("'([^']*)'").findAll(cmd).map { it.groupValues[1] }.toList()
        return when {
            cmd.startsWith("test -f ") -> result(if (Paths.OBSERVATORY in files) 0 else 1)
            cmd.startsWith("mkdir -p") -> {
                val (staged, backup) = args
                for ((path, content) in liveFiles()) {
                    files["$staged/${path.substringAfterLast('/')}"] = content
                    files["$backup/${path.substringAfterLast('/')}"] = content
                }
                result()
            }
            cmd.startsWith("XRAY_LOCATION_ASSET=") -> {
                tests++
                assertEquals(XRAY_CONFIGS, liveFiles())
                val stage = args.first()
                assertFalse(files.getValue("$stage/04_outbounds.json").contains("proxy-a"))
                assertFalse(files.getValue("$stage/05_routing.json").contains("proxy-a"))
                result(if (valid) 0 else 1, if (valid) "Configuration OK" else "Invalid config")
            }
            cmd.startsWith("grep -q") -> result(if (coreIsXray) 0 else 1)
            cmd.startsWith("cp '") -> {
                val source = args.first()
                val target = args.last()
                if ("/backups/" in source && failRollback) result(1)
                else {
                    files[target] = files.getValue(source)
                    writes++
                    // An unknown exit code can occur after a write has completed remotely.
                    result(if (writes == failWrite) -1 else 0)
                }
            }
            cmd == "/opt/etc/init.d/S99xkeen restart 2>&1" -> {
                restarts++
                running = liveFiles()
                result(if (failFirstRestart && restarts == 1) 1 else 0)
            }
            cmd == "pidof xray" -> result(output = "12345")
            cmd.startsWith("rm -rf '") -> {
                val path = args.single()
                files.keys.removeAll { it.startsWith("$path/") }
                result()
            }
            else -> error("Unexpected command: $cmd")
        }
    }
}
