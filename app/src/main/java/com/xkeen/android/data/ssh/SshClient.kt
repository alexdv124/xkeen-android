package com.xkeen.android.data.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.xkeen.android.domain.model.RouterProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Base64

class SshClient(private val profile: RouterProfile) {
    private var session: Session? = null
    private val mutex = Mutex()

    private fun connect() {
        val s = session
        if (s != null && s.isConnected) {
            try {
                s.sendKeepAliveMsg()
                return
            } catch (_: Exception) {
                session = null
            }
        }

        val jsch = JSch()
        val newSession = jsch.getSession(profile.username, profile.host, profile.port).apply {
            setPassword(profile.password)
            setConfig("StrictHostKeyChecking", "no")
            setConfig("PreferredAuthentications", "password,keyboard-interactive")
            setServerAliveInterval(30000)
            connect(10000)
        }
        session = newSession
    }

    suspend fun exec(cmd: String, timeout: Int = 15000): SshResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            connect()
            val currentSession = session ?: throw IllegalStateException("SSH not connected")
            val channel = currentSession.openChannel("exec") as ChannelExec
            channel.setCommand(cmd)
            channel.inputStream = null

            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            channel.outputStream = stdout
            channel.setErrStream(stderr)

            channel.connect(timeout)

            val startTime = System.currentTimeMillis()
            while (!channel.isClosed && System.currentTimeMillis() - startTime < timeout) {
                delay(100)
            }

            val out = stdout.toString(Charsets.UTF_8.name())
            val err = stderr.toString(Charsets.UTF_8.name())
            channel.disconnect()

            SshResult(out, err)
        }
    }

    suspend fun readFile(path: String): String {
        return exec("cat $path").stdout
    }

    suspend fun writeFileB64(path: String, content: String) {
        val b64 = Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8))
        val chunkSize = 4000
        val chunks = b64.chunked(chunkSize)

        exec("printf '%s' '${chunks[0]}' > /tmp/_panel.b64")
        for (chunk in chunks.drop(1)) {
            exec("printf '%s' '$chunk' >> /tmp/_panel.b64")
        }
        exec("base64 -d /tmp/_panel.b64 > $path; rm /tmp/_panel.b64")
    }

    fun close() {
        session?.disconnect()
        session = null
    }
}

data class SshResult(
    val stdout: String,
    val stderr: String
)
