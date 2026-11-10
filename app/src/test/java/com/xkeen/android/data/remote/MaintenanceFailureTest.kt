package com.xkeen.android.data.remote

import com.xkeen.android.data.ssh.SshConnection
import com.xkeen.android.data.ssh.SshResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MaintenanceFailureTest {
    private class RestoreRouter(val router: FakeRouter) : SshConnection {
        val backup = MIHOMO_CONFIG.replace("old.example.com", "restored.example.com")
        override suspend fun readFile(path: String) =
            if (path.endsWith("/mihomo-config.yaml")) backup else router.readFile(path)
        override suspend fun writeFileB64(path: String, content: String) = router.writeFileB64(path, content)
        override suspend fun exec(cmd: String, timeout: Int): SshResult = when {
            cmd.startsWith("ls /opt/etc/xray/configs/backups/") -> SshResult("mihomo-config.yaml", "", 0)
            cmd.startsWith("xkeen -status") -> SshResult("mihomo", "", 0)
            cmd.startsWith("for f in") || cmd.startsWith("command -v curl") ||
                cmd.startsWith("grep -E") || cmd.startsWith("curl") || cmd.startsWith("mihomo -t") ||
                cmd.startsWith("# XK_CONFIG_TRANSACTION") || cmd.startsWith("rm -f") -> router.exec(cmd, timeout)
            else -> SshResult("", "", 0)
        }
    }

    @Test fun `invalid backup returns failure without replacing or reloading config`() = runBlocking {
        val router = FakeRouter().apply { validConfig = false }
        val result = RouterCommands(RestoreRouter(router)).restoreBackup("20261109_210000")
        assertFalse(result.second, result.first)
        assertEquals(MIHOMO_CONFIG, router.files[router.path])
        assertEquals(MIHOMO_CONFIG, router.runningConfig)
        assertEquals(0, router.reloads)
    }

    @Test fun `backup reload failure restores both stored and running config`() = runBlocking {
        val router = FakeRouter().apply { failFirstReload = true }
        val result = RouterCommands(RestoreRouter(router)).restoreBackup("20261109_210000")
        assertFalse(result.second, result.first)
        assertEquals(MIHOMO_CONFIG, router.files[router.path])
        assertEquals(MIHOMO_CONFIG, router.runningConfig)
        assertEquals(2, router.reloads)
    }

    @Test fun `valid backup is applied before success is returned`() = runBlocking {
        val router = FakeRouter()
        val ssh = RestoreRouter(router)
        val result = RouterCommands(ssh).restoreBackup("20261109_210000")
        assertTrue(result.second, result.first)
        assertEquals(ssh.backup, router.files[router.path])
        assertEquals(ssh.backup, router.runningConfig)
        assertEquals(1, router.reloads)
    }

    private class UpdateRouter(val exit: Int, val installed: String, val latest: String) : SshConnection {
        var checks = 0
        override suspend fun readFile(path: String) = ""
        override suspend fun writeFileB64(path: String, content: String) = Unit
        override suspend fun exec(cmd: String, timeout: Int): SshResult {
            if (cmd.startsWith("V=/opt/sbin/.xkeen")) {
                checks++
                val current = if (checks == 1) "2.1" else installed
                val newest = if (checks == 1) "2.2" else latest
                return SshResult("XKEEN_CUR=$current\nXKEEN_NEW=$newest\nAUTO=1", "", 0)
            }
            if (cmd.startsWith("xkeen -uk")) return SshResult("", if (exit == 0) "" else "update failed", exit)
            return SshResult("", "", 0)
        }
    }

    @Test fun `failed updater plus failed version lookup cannot report current version`() = runBlocking {
        val result = UpdatesRemote(UpdateRouter(1, "2.1", "")).updateXkeen()
        assertFalse(result.second, result.first)
        assertFalse(result.second.contains("уже последней"))
        assertTrue(result.second.contains("update failed"))
    }

    @Test fun `missing version alone is not proof of successful update`() = runBlocking {
        val result = UpdatesRemote(UpdateRouter(0, "2.1", "")).updateXkeen()
        assertFalse(result.second, result.first)
        assertTrue(result.second.contains("не удалось подтвердить"))
    }

    @Test fun `confirmed version increase is successful even if latest lookup fails`() = runBlocking {
        val result = UpdatesRemote(UpdateRouter(0, "2.2", "")).updateXkeen()
        assertTrue(result.second, result.first)
        assertTrue(result.second.contains("2.1 → 2.2"))
    }
}
