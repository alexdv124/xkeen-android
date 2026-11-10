package com.xkeen.android.data.ssh

import org.junit.Assert.*
import org.junit.Test

class RemoteFileReadTest {
    private val path = "/opt/etc/xray/configs/07_observatory.json"

    @Test fun `successful empty files remain distinguishable from failed reads`() {
        assertEquals("", checkedFileContents(path, SshResult("", "", 0)))
        assertEquals("{}\n", checkedFileContents(path, SshResult("{}\n", "", 0)))
    }

    @Test fun `missing file and access denied retain path and distinct reasons`() {
        for ((stderr, reason) in listOf(
            "cat: $path: No such file or directory" to RemoteFileReadFailure.MISSING,
            "cat: $path: Permission denied" to RemoteFileReadFailure.DENIED
        )) {
            val error = assertThrows(RemoteFileReadException::class.java) {
                checkedFileContents(path, SshResult("", stderr, 1))
            }
            assertEquals(path, error.path)
            assertEquals(reason, error.reason)
            assertTrue(error.message.orEmpty().contains(path))
        }
    }

    @Test fun `timed out reads cannot expose partial config as complete JSON`() {
        val error = assertThrows(RemoteFileReadException::class.java) {
            checkedFileContents(path, SshResult("{\"password\":\"private-test-value", "", -1))
        }
        assertEquals(RemoteFileReadFailure.TIMEOUT, error.reason)
        assertFalse(error.message.orEmpty().contains("private-test-value"))
    }

    @Test fun `other command failures do not leak raw output`() {
        val error = assertThrows(RemoteFileReadException::class.java) {
            checkedFileContents(path, SshResult("secret-on-stdout", "secret-on-stderr", 127))
        }
        assertEquals(RemoteFileReadFailure.COMMAND_FAILED, error.reason)
        assertFalse(error.message.orEmpty().contains("secret-on"))
    }
}
