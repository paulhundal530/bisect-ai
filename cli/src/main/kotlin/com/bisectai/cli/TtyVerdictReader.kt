package com.bisectai.cli

import com.bisectai.evaluation.ManualVerdict
import com.bisectai.evaluation.VerdictReader
import com.bisectai.evaluation.VerdictResult
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.PrintStream

/**
 * Collects human verdicts from the controlling terminal for manual classification.
 *
 * Reads from and writes prompts to `/dev/tty` directly — not stdin/stdout — so prompts stay
 * visible and answerable even when stdout carries JSON (`... --output-type json | jq`) or stderr
 * is redirected. [isAvailable] returns false when there is no controlling terminal (CI, cron,
 * fully piped), letting the run fail fast rather than hang.
 */
class TtyVerdictReader : VerdictReader {

    override fun isAvailable(): Boolean =
        runCatching { FileInputStream(TTY).use { true } }.getOrDefault(false)

    override fun read(commit: String, instructions: String?): VerdictResult {
        FileOutputStream(TTY).use { rawOut ->
            val tty = PrintStream(rawOut, true)
            BufferedReader(InputStreamReader(FileInputStream(TTY))).use { reader ->
                tty.println()
                instructions?.let { tty.println(it) }
                tty.println("Commit under test: $commit")

                while (true) {
                    tty.print("Verdict — [g]ood / [b]ad / [s]kip / [a]bort: ")
                    tty.flush()
                    val answer = reader.readLine()?.trim()?.lowercase()
                        ?: return VerdictResult(ManualVerdict.ABORT, null) // EOF => abort
                    val verdict = when (answer) {
                        "g", "good" -> ManualVerdict.GOOD
                        "b", "bad" -> ManualVerdict.BAD
                        "s", "skip" -> ManualVerdict.SKIP
                        "a", "abort" -> ManualVerdict.ABORT
                        else -> {
                            tty.println("  Please answer g, b, s, or a.")
                            continue
                        }
                    }
                    val note = if (verdict == ManualVerdict.GOOD || verdict == ManualVerdict.BAD) {
                        tty.print("Optional note (what did you observe?): ")
                        tty.flush()
                        reader.readLine()?.trim()?.ifBlank { null }
                    } else {
                        null
                    }
                    return VerdictResult(verdict, note)
                }
                @Suppress("UNREACHABLE_CODE")
                return VerdictResult(ManualVerdict.ABORT, null)
            }
        }
    }

    companion object {
        private const val TTY = "/dev/tty"
    }
}
