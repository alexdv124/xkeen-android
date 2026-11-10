package com.xkeen.android.data.remote

import com.xkeen.android.data.ssh.SshConnection
import com.xkeen.android.data.ssh.SshResult
import com.xkeen.android.data.ssh.RemoteFileReadException
import com.xkeen.android.data.ssh.RemoteFileReadFailure
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class XrayConfigReadTest {
    private class Files : SshConnection {
        val errors = mutableMapOf<String, Exception>()
        val contents = mutableMapOf(
            Paths.OUTBOUNDS to """{"outbounds":[{"tag":"proxy-one","protocol":"vless","settings":{"vnext":[{"address":"example.com","port":443,"users":[{"id":"test"}]}]}}]}""",
            Paths.ROUTING to """{"routing":{"rules":[]}}"""
        )
        override suspend fun exec(cmd: String, timeout: Int) = SshResult("", "", 0)
        override suspend fun readFile(path: String): String {
            errors[path]?.let { throw it }
            return contents[path].orEmpty()
        }
        override suspend fun writeFileB64(path: String, content: String) {
            error("Loading the server list must never write a config")
        }
    }

    @Test fun `missing optional observatory does not break server list loading`() = runBlocking {
        val remote = XrayConfigRemote(Files())
        val state = remote.detectConfigState()
        assertEquals(1, state.proxyCount)
        assertFalse(state.hasObservatory)
        assertEquals("example.com", remote.getProxyList().single().address)
    }

    @Test fun `empty required config reports its filename instead of JSON parser internals`() = runBlocking {
        val files = Files().apply { contents[Paths.OUTBOUNDS] = "  \n" }
        val failure = runCatching { XrayConfigRemote(files).detectConfigState() }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty(), failure.message.orEmpty().contains(Paths.OUTBOUNDS))
        assertFalse(failure.message.orEmpty().contains("JSON input:"))
    }

    @Test fun `missing observatory reported by SSH is also optional`() = runBlocking {
        val files = Files().apply {
            errors[Paths.OBSERVATORY] = RemoteFileReadException(Paths.OBSERVATORY, RemoteFileReadFailure.MISSING)
        }
        assertEquals(1, XrayConfigRemote(files).detectConfigState().proxyCount)
    }

    @Test fun `permission and connection failures must not be treated as absent observatory`() = runBlocking {
        for (reason in listOf(RemoteFileReadFailure.DENIED, RemoteFileReadFailure.TIMEOUT)) {
            val expected = RemoteFileReadException(Paths.OBSERVATORY, reason)
            val files = Files().apply { errors[Paths.OBSERVATORY] = expected }
            val failure = runCatching { XrayConfigRemote(files).detectConfigState() }.exceptionOrNull()
            assertSame(expected, failure)
        }
    }

    @Test fun `valid observatory is still detected`() = runBlocking {
        val files = Files().apply { contents[Paths.OBSERVATORY] = """{"burstObservatory":{"subjectSelector":["proxy-"]}}""" }
        assertTrue(XrayConfigRemote(files).detectConfigState().hasObservatory)
    }

    @Test fun `invalid routing reports its filename without exposing configuration contents`() = runBlocking {
        val files = Files().apply { contents[Paths.ROUTING] = "{\"private-value\":\"secret-test-token\"," }
        val failure = runCatching { XrayConfigRemote(files).detectConfigState() }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty(), failure.message.orEmpty().contains(Paths.ROUTING))
        assertFalse(failure.message.orEmpty().contains("secret-test-token"))
    }
}
