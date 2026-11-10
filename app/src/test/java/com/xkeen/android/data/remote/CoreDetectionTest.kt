package com.xkeen.android.data.remote

import com.xkeen.android.data.ssh.SshConnection
import com.xkeen.android.data.ssh.SshResult
import com.xkeen.android.domain.model.ProxyCore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CoreDetectionTest {
    private class Router(
        val status: String = "",
        val configured: String = "",
        val xrayProcess: String = "",
        val mihomoProcess: String = ""
    ) : SshConnection {
        override suspend fun exec(cmd: String, timeout: Int): SshResult = SshResult(when {
            cmd.startsWith("xkeen -status") -> status
            cmd.startsWith("grep -m1 '^name_client='") -> configured
            cmd.contains("pidof xray") -> xrayProcess
            cmd.contains("pidof mihomo") -> mihomoProcess
            else -> ""
        }, "", 0)
        override suspend fun readFile(path: String): String = error("Core detection must not read proxy configs")
        override suspend fun writeFileB64(path: String, content: String) = error("Read-only operation")
    }

    @Test fun `sole running Mihomo overrides Xray status and configuration`() = runBlocking {
        val core = RouterCommands(Router("Xray", "xray", mihomoProcess = "1532 34816")).getCoreState()
        assertEquals(ProxyCore.MIHOMO, core.activeCore)
        assertTrue(core.activeRunning)
        assertEquals("1532", core.mihomoPid)
    }

    @Test fun `sole running Xray overrides status mentioning installed Mihomo`() = runBlocking {
        val core = RouterCommands(Router("Xray running; Mihomo installed", "mihomo", xrayProcess = "42 1024")).getCoreState()
        assertEquals(ProxyCore.XRAY, core.activeCore)
    }

    @Test fun `configured core resolves both or neither running`() = runBlocking {
        for (pid in listOf("", "42 1024")) {
            val core = RouterCommands(Router("Xray and Mihomo", "mihomo", pid, pid)).getCoreState()
            assertEquals(ProxyCore.MIHOMO, core.activeCore)
        }
    }

    @Test fun `ambiguous status without configured core stays unknown`() = runBlocking {
        assertEquals(ProxyCore.UNKNOWN, RouterCommands(Router("Xray and Mihomo")).getCoreState().activeCore)
        assertEquals(ProxyCore.MIHOMO, RouterCommands(Router("Mihomo stopped")).getCoreState().activeCore)
    }

    @Test fun `non PID output is not a running process`() = runBlocking {
        val core = RouterCommands(Router(configured = "mihomo", xrayProcess = "warning: process unavailable")).getCoreState()
        assertFalse(core.xrayRunning)
        assertFalse(core.activeRunning)
        assertEquals(ProxyCore.MIHOMO, core.activeCore)
    }
}
