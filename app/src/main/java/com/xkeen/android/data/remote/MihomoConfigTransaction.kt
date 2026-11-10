package com.xkeen.android.data.remote

import com.xkeen.android.data.ssh.SshConnection
import java.security.MessageDigest

/** The lock, comparison, install, reload and rollback live in one remote shell.
 * No lock is left dependent on a phone-side coroutine remaining alive. */
internal object MihomoConfigTransaction {
    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun command(path: String, original: String?, staged: String, backup: String,
                controller: MihomoApi.Controller?): String {
        val helpers = requireNotNull(javaClass.getResourceAsStream("/xk-config-lock.sh"))
            .bufferedReader().use { it.readText() }
        val hash = original?.let {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }
        }
        val reload = if (controller == null) "return 0" else {
            val body = kotlinx.serialization.json.buildJsonObject {
                put("path", kotlinx.serialization.json.JsonPrimitive(path))
            }.toString()
            val auth = if (controller.secret.isBlank()) "" else
                " -H ${shellQuote("Authorization: Bearer ${controller.secret}")}"
            "code=\$(curl -sS -m 20 -o /dev/null -w '%{http_code}' -X PUT$auth " +
                "-H 'Content-Type: application/json' --data ${shellQuote(body)} " +
                "${shellQuote("http://${controller.base}/configs?force=true")}); " +
                "case \"\$code\" in 2??) return 0;; *) return 1;; esac"
        }
        val compare = if (hash == null) "[ ! -e \"\$cfg\" ]" else
            "[ \"\$(sha256sum \"\$cfg\" | cut -d' ' -f1)\" = '$hash' ]"
        val save = if (original == null) ":" else "cp -p \"\$cfg\" \"\$backup\""
        val restore = if (original == null) "rm -f \"\$cfg\"" else
            "cp -p \"\$backup\" \"\$staged.rollback\" && mv \"\$staged.rollback\" \"\$cfg\""
        return "# XK_CONFIG_TRANSACTION\n" + helpers + "\n" + """
            cfg=${shellQuote(path)}
            staged=${shellQuote(staged)}
            backup=${shellQuote(backup)}
            xk_reload() { $reload; }
            xk_restore() { $restore && xk_reload; }
            xk_config_lock || { echo XK_BUSY; exit 1; }
            $compare || { echo XK_CONFLICT; exit 1; }
            $save || { echo XK_BACKUP_FAILED; exit 1; }
            # A signal after an uncertain move must restore the previous file too.
            trap 'xk_restore; exit 1' HUP INT TERM
            if mv "${'$'}staged" "${'$'}cfg" && xk_reload; then
                trap 'exit 1' HUP INT TERM
                echo XK_APPLIED
            elif xk_restore; then
                echo XK_ROLLED_BACK
                exit 1
            else
                echo XK_ROLLBACK_FAILED
                exit 1
            fi
        """.trimIndent()
    }

    suspend fun install(ssh: SshConnection, path: String, original: String?, staged: String,
                        backup: String, controller: MihomoApi.Controller?): Pair<Boolean, String> {
        val result = ssh.exec(command(path, original, staged, backup, controller), timeout = 90000)
        val markers = result.stdout.lines().map { it.trim() }
        return when {
            result.exitCode == 0 && "XK_APPLIED" in markers -> true to "Применено"
            "XK_BUSY" in markers -> false to "Конфиг занят другой операцией. Повторите действие"
            "XK_CONFLICT" in markers -> false to "Конфиг изменился во время операции. Повторите действие"
            "XK_BACKUP_FAILED" in markers -> false to "Не удалось сохранить резервную копию; конфиг не изменён"
            "XK_ROLLED_BACK" in markers -> false to "Не удалось применить конфиг. Прежний конфиг восстановлен"
            else -> false to "Не удалось подтвердить применение или откат. Резервная копия: $backup"
        }
    }
}
