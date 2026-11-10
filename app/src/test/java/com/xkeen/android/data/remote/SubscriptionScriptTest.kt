package com.xkeen.android.data.remote

import com.xkeen.android.data.ssh.SshConnection
import com.xkeen.android.data.ssh.SshResult
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class SubscriptionScriptTest {
    private fun function(name: String, end: String = "\n}\n"): String {
        val source = scriptResource("xk-sub.sh")
        val start = source.indexOf("$name() ")
        return source.substring(start, source.indexOf(end, start) + end.length)
    }

    @Test fun `one or two healthy selected servers do not trigger a half hourly benchmark`() {
        val conf = Files.createTempFile("xkeen-conf", ".txt").toFile()
        try {
            for ((top, alive, expected) in listOf(Triple(1, 1, false), Triple(2, 2, false),
                Triple(3, 3, false), Triple(10, 10, false), Triple(2, 1, true), Triple(10, 2, true))) {
                val prelude = """
                    CONF='${conf.path}'; TOP_N=$top; MIN_ALIVE=3; REBENCH_DAYS=7; GEO_GROUP=SUB-GEO
                    has_provider() { return 0; }
                    mihomo_up() { return 0; }
                    lock() { :; }
                    provider_hash() { echo abc; }
                    state_get() { case "${'$'}1" in provider_hash) echo abc;; *) date +%s;; esac; }
                    best_alive() { echo $alive; }
                    has_group() { return 0; }
                    geo_alive() { echo 5; }
                    log() { :; }
                    bench() { echo BENCH; }
                    geo() { echo GEO; }
                """.trimIndent()
                val result = runTestShell(prelude + "\n" + function("cmd_check") + "\ncmd_check")
                assertEquals(result.second, 0, result.first)
                assertEquals("TOP_N=$top alive=$alive", expected, "BENCH" in result.second)
            }
        } finally { conf.delete() }
    }

    @Test fun `removing last cron jobs empties crontab and keeps unrelated jobs`() {
        val remote = SubscriptionRemote(object : SshConnection {
            override suspend fun exec(cmd: String, timeout: Int) = SshResult("", "", 0)
            override suspend fun readFile(path: String) = ""
            override suspend fun writeFileB64(path: String, content: String) {}
        })
        val dir = Files.createTempDirectory("xkeen-cron").toFile()
        try {
            val cron = File(dir, "root")
            for (other in listOf("", "5 * * * * /opt/bin/unrelated-job\n")) {
                cron.writeText(other + remote.cronLines(SubscriptionSettings()).joinToString("\n") + "\n")
                val result = runTestShell(remote.removeCronCommand(cron.path))
                assertEquals(result.second, 0, result.first)
                assertEquals(other, cron.readText())
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun `benchmark holds same config lock as app throughout validation and installation`() {
        val dir = Files.createTempDirectory("xkeen-concurrent").toFile()
        try {
            File(dir, "xk-config-lock.sh").writeText(scriptResource("xk-config-lock.sh"))
            val cfg = File(dir, "config.yaml").apply { writeText("original\n") }
            val staged = File(dir, "app.tmp").apply { writeText("app change\n") }
            val app = File(dir, "app.sh").apply {
                writeText(MihomoConfigTransaction.command(cfg.path, cfg.readText(), staged.path,
                    File(dir, "app.bak").path, null))
            }
            val prelude = """
                XK_DIR='${dir.path}'; CFG='${cfg.path}'; CFG_DIR='${dir.path}'
                has_group() { return 0; }
                log() { echo "${'$'}*"; }
                die() { echo "${'$'}*"; exit 1; }
                yq() {
                    if [ "${'$'}1" = -r ]; then echo old; else
                        for arg in "${'$'}@"; do target="${'$'}arg"; done
                        printf 'script change\n' > "${'$'}target"
                    fi
                }
                mihomo() { sh '${app.path}' > '${dir}/app-result'; return 0; }
                api_code() { echo 204; }
                jq() { echo '{}'; }
            """.trimIndent()
            val result = runTestShell(prelude + "\n" + function("set_group_filter", "\n)\n") +
                "\nset_group_filter SUB-BEST new", mapOf("XK_CONFIG_LOCK" to File(dir, "lock").path))
            assertEquals(result.second, 0, result.first)
            assertTrue(File(dir, "app-result").readText().contains("XK_BUSY"))
            assertEquals("script change\n", cfg.readText())
            assertFalse(File(dir, "lock").exists())
            // A retry using the stale app snapshot must also refuse to overwrite the script.
            val retry = runTestShell(app.readText(), mapOf("XK_CONFIG_LOCK" to File(dir, "lock").path))
            assertTrue(retry.second, retry.second.contains("XK_CONFLICT"))
            assertEquals("script change\n", cfg.readText())
        } finally { dir.deleteRecursively() }
    }

    @Test fun `dead config lock owner can be recovered`() {
        val dir = Files.createTempDirectory("xkeen-dead-lock").toFile()
        try {
            val lock = File(dir, "lock").apply { mkdir() }
            File(lock, "pid").writeText("2147483647\n")
            val result = runTestShell(scriptResource("xk-config-lock.sh") + "\nxk_config_lock || exit 1\necho ACQUIRED",
                mapOf("XK_CONFIG_LOCK" to lock.path))
            assertEquals(result.second, 0, result.first)
            assertTrue(result.second.contains("ACQUIRED"))
            assertFalse(lock.exists())
        } finally { dir.deleteRecursively() }
    }
}
