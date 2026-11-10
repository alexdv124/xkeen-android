package com.xkeen.android.data.remote

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class MihomoConfigTransactionTest {
    @Test fun `first config can be installed without an existing file or running core`() {
        val dir = Files.createTempDirectory("xkeen-first-config").toFile()
        try {
            val cfg = File(dir, "new 'config.yaml")
            val staged = File(dir, "staged").apply { writeText("new config\n") }
            val result = runTestShell(MihomoConfigTransaction.command(cfg.path, null, staged.path,
                File(dir, "backup").path, null), mapOf("XK_CONFIG_LOCK" to File(dir, "lock").path))
            assertEquals(result.second, 0, result.first)
            assertEquals("new config\n", cfg.readText())
            assertFalse(File(dir, "lock").exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun `backup failure leaves original config untouched and releases lock`() {
        val dir = Files.createTempDirectory("xkeen-backup-failure").toFile()
        try {
            val cfg = File(dir, "config").apply { writeText("original\n") }
            val staged = File(dir, "staged").apply { writeText("replacement\n") }
            val result = runTestShell(MihomoConfigTransaction.command(cfg.path, cfg.readText(), staged.path,
                File(dir, "missing-directory/backup").path, null), mapOf("XK_CONFIG_LOCK" to File(dir, "lock").path))
            assertNotEquals(0, result.first)
            assertTrue(result.second, result.second.contains("XK_BACKUP_FAILED"))
            assertEquals("original\n", cfg.readText())
            assertFalse(File(dir, "lock").exists())
        } finally { dir.deleteRecursively() }
    }
}
