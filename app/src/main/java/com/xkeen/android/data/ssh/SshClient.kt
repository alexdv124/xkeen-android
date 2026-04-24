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
    private val writeMutex = Mutex()

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

    suspend fun writeFileB64(path: String, content: String) = writeMutex.withLock {
        val b64 = Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8))
        val chunkSize = 4000
        val chunks = b64.chunked(chunkSize)
        val tmpB64 = "/tmp/_panel_${System.nanoTime()}.b64"
        val tmpOut = "$path.tmp"

        try {
            exec("printf '%s' '${chunks[0]}' > $tmpB64")
            for (chunk in chunks.drop(1)) {
                exec("printf '%s' '$chunk' >> $tmpB64")
            }
            // Atomic write: decode to .tmp, only mv on success
            val result = exec("base64 -d $tmpB64 > $tmpOut && mv $tmpOut $path && echo OK")
            if (!result.stdout.contains("OK")) {
                exec("rm -f $tmpOut")
                throw IllegalStateException("writeFileB64 failed: ${result.stderr.take(200)}")
            }
        } finally {
            exec("rm -f $tmpB64")
        }
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
