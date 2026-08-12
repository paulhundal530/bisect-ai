package com.bisectai.execution

import com.bisectai.core.ExecutionResult
import com.bisectai.core.ProcessRequest
import java.io.InputStream
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Runs commands via `sh -c "<command>"` so shell forms like `./gradlew test` work as written.
 *
 * stdout and stderr are drained on dedicated threads to avoid pipe-buffer deadlock. On timeout
 * the process is terminated gracefully, then forcibly, partial output is captured, and a
 * timed-out result is returned (§16). This class never waits indefinitely.
 */
class DefaultProcessRunner(
    /** Grace period between graceful and forced termination when a timeout occurs. */
    private val forcedKillGrace: Duration = Duration.ofSeconds(5),
) : ProcessRunner {

    override fun execute(request: ProcessRequest): ExecutionResult {
        val builder = ProcessBuilder("sh", "-c", request.command)
            .directory(request.workingDir)
        builder.environment().putAll(request.env)
        // Keep streams separate so we can attribute stdout vs stderr.
        builder.redirectErrorStream(false)

        val start = System.nanoTime()
        val process = try {
            builder.start()
        } catch (e: Exception) {
            // Could not launch at all — not a timeout, exitCode stays null.
            return ExecutionResult(
                command = request.command,
                exitCode = null,
                stdout = "",
                stderr = "Failed to launch process: ${e.message}",
                duration = elapsedSince(start),
                timedOut = false,
            )
        }

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val outPump = pump(process.inputStream, stdout)
        val errPump = pump(process.errorStream, stderr)

        val finishedInTime = process.waitFor(request.timeout.toMillis(), TimeUnit.MILLISECONDS)
        var timedOut = false
        if (!finishedInTime) {
            timedOut = true
            // 1. Graceful termination.
            process.destroy()
            if (!process.waitFor(forcedKillGrace.toMillis(), TimeUnit.MILLISECONDS)) {
                // 2. Force termination.
                process.destroyForcibly()
                process.waitFor(forcedKillGrace.toMillis(), TimeUnit.MILLISECONDS)
            }
        }

        // 3. Capture whatever output is available.
        outPump.join(forcedKillGrace.toMillis())
        errPump.join(forcedKillGrace.toMillis())

        val exitCode = if (timedOut) null else runCatching { process.exitValue() }.getOrNull()

        return ExecutionResult(
            command = request.command,
            exitCode = exitCode,
            stdout = stdout.toString(),
            stderr = stderr.toString(),
            duration = elapsedSince(start),
            timedOut = timedOut,
        )
    }

    private fun pump(stream: InputStream, sink: StringBuilder): Thread =
        thread(start = true, isDaemon = true, name = "process-stream-pump") {
            stream.bufferedReader().use { reader ->
                val buffer = CharArray(4096)
                while (true) {
                    val read = try {
                        reader.read(buffer)
                    } catch (_: Exception) {
                        break
                    }
                    if (read < 0) break
                    synchronized(sink) { sink.appendRange(buffer, 0, read) }
                }
            }
        }

    private fun elapsedSince(startNanos: Long): Duration =
        Duration.ofNanos(System.nanoTime() - startNanos)
}
