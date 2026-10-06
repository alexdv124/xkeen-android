package com.xkeen.android.data.ssh

/** Router transport, also used to exercise config deployment without a live router. */
interface SshConnection {
    suspend fun exec(cmd: String, timeout: Int = 15000): SshResult
    suspend fun readFile(path: String): String
    suspend fun writeFileB64(path: String, content: String)
}
