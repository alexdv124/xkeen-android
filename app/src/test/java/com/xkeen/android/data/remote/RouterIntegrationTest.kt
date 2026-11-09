package com.xkeen.android.data.remote

import com.xkeen.android.data.ssh.SshClient
import com.xkeen.android.domain.model.RouterProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs the app's real data layer against a live router. Skipped unless XK_ROUTER_HOST is set:
 *
 *   XK_ROUTER_HOST=192.168.1.1 XK_ROUTER_PASS=... XK_STEP=enable XK_SUB_URL=... gradle testDebugUnitTest --tests '*RouterIntegrationTest*'
 *
 * Steps: status, enable, wait, updates, disable.
 */
class RouterIntegrationTest {
    private val host = System.getenv("XK_ROUTER_HOST").orEmpty()
    private val step = System.getenv("XK_STEP").orEmpty()

    private fun client() = SshClient(
        RouterProfile(alias = "it", host = host, username = System.getenv("XK_ROUTER_USER") ?: "root",
            password = System.getenv("XK_ROUTER_PASS").orEmpty(), port = (System.getenv("XK_ROUTER_PORT") ?: "22").toInt())
    )

    @Test fun live() = runBlocking {
        assumeTrue("set XK_ROUTER_HOST to run", host.isNotBlank())
        val ssh = client()
        try {
            val sub = SubscriptionRemote(ssh)
            when (step) {
                "status" -> println("STATUS: ${sub.status()}")
                "enable" -> {
                    val (ok, msg) = sub.enable(System.getenv("XK_SUB_URL").orEmpty(),
                        SubscriptionSettings(System.getenv("XK_TOP")?.toInt() ?: 10, System.getenv("XK_TIME") ?: "04:00"))
                    println("ENABLE: $ok $msg")
                    assertTrue(msg, ok)
                    val s = sub.status()
                    println("STATUS: running=${s.running} cron=${s.cronInstalled} script=${s.scriptInstalled} url=${s.url}")
                    assertTrue(s.enabled && s.cronInstalled && s.scriptInstalled)
                    assertTrue("job must show as running right after enable", s.running)
                    assertEquals(SubscriptionNames.BEST, MihomoApi(ssh).selectedOf("PROXY"))
                }
                "wait" -> {
                    var s = sub.status()
                    var waited = 0
                    while (s.running && waited < 900) { delay(10000); waited += 10; s = sub.status() }
                    println("DONE after ${waited}s, error='${s.lastError}'")
                    s.servers.forEach { println("  ${if (it.chosen) "*" else " "} ${it.name}  ${it.delayMs}ms  down=${it.downMbit} up=${it.upMbit}") }
                    println("LOG:\n${s.log}")
                    assertFalse(s.running)
                    assertTrue(s.chosen.isNotEmpty())
                    val best = MihomoApi(ssh).getProxies()[SubscriptionNames.BEST]
                    println("SUB-BEST now=${best?.now} members=${best?.all}")
                    assertEquals(s.chosen.map { it.name }.toSet(), best?.all?.toSet())
                }
                "rebench" -> println("REBENCH: ${sub.rebenchNow()}")
                "refresh" -> println("REFRESH: ${sub.refreshNow()}")
                "settings" -> println("SETTINGS: ${sub.updateSettings(SubscriptionSettings(System.getenv("XK_TOP")?.toInt() ?: 10, System.getenv("XK_TIME") ?: "04:00"))}")
                "updates" -> println("UPDATES: ${UpdatesRemote(ssh).check()}")
                "disable" -> {
                    val (ok, msg) = sub.disable()
                    println("DISABLE: $ok $msg")
                    assertTrue(msg, ok)
                    assertFalse(sub.status().enabled)
                }
                "core" -> println("CORE: ${RouterCommands(ssh).getCoreState(withConfigTests = true)}")
                "diag" -> RouterCommands(ssh).runDiagnostics().checks.forEach { println("DIAG: $it") }
                "backup" -> {
                    val cmds = RouterCommands(ssh)
                    val ts = cmds.backupConfigs()
                    println("BACKUP: $ts in ${cmds.listBackups().take(3)}")
                    println("RESTORE: ${cmds.restoreBackup(ts)}")
                }
                else -> fail("unknown XK_STEP=$step")
            }
        } finally {
            ssh.close()
        }
    }
}
