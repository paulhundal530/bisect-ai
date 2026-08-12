package com.bisectai.execution

import com.bisectai.core.ProcessRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration

class DefaultProcessRunnerTest {

    private val runner = DefaultProcessRunner()

    private fun request(command: String, dir: File, timeoutSeconds: Long = 10) =
        ProcessRequest(command, dir, Duration.ofSeconds(timeoutSeconds))

    @Test
    fun `successful process returns exit code zero`(@TempDir dir: File) {
        val result = runner.execute(request("exit 0", dir))
        assertEquals(0, result.exitCode)
        assertFalse(result.timedOut)
    }

    @Test
    fun `non-zero exit is captured`(@TempDir dir: File) {
        val result = runner.execute(request("exit 7", dir))
        assertEquals(7, result.exitCode)
        assertFalse(result.timedOut)
    }

    @Test
    fun `stdout is captured`(@TempDir dir: File) {
        val result = runner.execute(request("echo hello-stdout", dir))
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("hello-stdout"), "stdout was: ${result.stdout}")
        assertTrue(result.stderr.isBlank())
    }

    @Test
    fun `stderr is captured`(@TempDir dir: File) {
        val result = runner.execute(request("echo oops 1>&2", dir))
        assertEquals(0, result.exitCode)
        assertTrue(result.stderr.contains("oops"), "stderr was: ${result.stderr}")
        assertTrue(result.stdout.isBlank())
    }

    @Test
    fun `timeout terminates the process and is reported`(@TempDir dir: File) {
        val result = runner.execute(request("sleep 30", dir, timeoutSeconds = 1))
        assertTrue(result.timedOut)
        assertNull(result.exitCode)
        // Should return well before the 30s sleep would have finished.
        assertTrue(result.duration < Duration.ofSeconds(15), "took ${result.duration}")
    }

    @Test
    fun `working directory is honored`(@TempDir dir: File) {
        val marker = File(dir, "marker.txt").apply { writeText("x") }
        val result = runner.execute(request("ls", dir))
        assertTrue(result.stdout.contains(marker.name))
    }
}
