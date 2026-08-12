package com.bisectai.cli

import com.bisectai.core.EvaluationStatus
import java.io.PrintStream
import java.time.Duration

/**
 * Emits human-readable progress so the CLI is never silent during long operations (§24).
 *
 * Progress ALWAYS goes to stderr so that machine-readable JSON written to stdout is never
 * corrupted and stays safe for `| jq` (§37).
 */
class ProgressReporter(
    private val out: PrintStream = System.err,
    private val enabled: Boolean = true,
) {
    fun banner() {
        if (enabled) out.println("BisectAI")
    }

    fun line(message: String = "") {
        if (enabled) out.println(message)
    }

    fun step(message: String) {
        if (enabled) out.println(message)
    }

    /** Reports one commit evaluation, e.g. `abc123  GOOD  12.4s`. */
    fun commitResult(
        sha: String,
        status: EvaluationStatus,
        duration: Duration,
        remainingApprox: Int? = null,
        indexLabel: String? = null,
    ) {
        if (!enabled) return
        val prefix = indexLabel?.let { "[$it] " } ?: ""
        out.println("$prefix${short(sha)}  ${status.name.padEnd(7)} ${seconds(duration)}")
        remainingApprox?.let {
            out.println("    Remaining range: approximately $it commit${if (it == 1) "" else "s"}")
        }
    }

    private fun short(sha: String) = if (sha.length > 9) sha.substring(0, 9) else sha

    private fun seconds(duration: Duration): String =
        "%.1fs".format(duration.toMillis() / 1000.0)
}
