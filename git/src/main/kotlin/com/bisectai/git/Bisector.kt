package com.bisectai.git

import com.bisectai.core.BisectAiException
import com.bisectai.core.EvaluationStatus
import com.bisectai.core.ExecutionResult
import com.bisectai.core.ExitCode
import java.io.File

/** The next thing the bisect driver should do, as reported by Git. */
sealed interface BisectStep {
    /** Git checked out [commit]; evaluate it. [remainingApprox] is Git's own estimate. */
    data class Evaluate(val commit: String, val remainingApprox: Int?) : BisectStep

    /** Git identified [commit] as the first bad commit. */
    data class FirstBad(val commit: String) : BisectStep

    /** Git could not conclusively locate a single first-bad commit (e.g. only skips remain). */
    data class Inconclusive(val reason: String) : BisectStep
}

/**
 * Drives Git's real bisect inside an isolated worktree (§22). BisectAI never re-implements the
 * binary search; it only feeds Git the deterministic GOOD/BAD/UNKNOWN verdicts and interprets
 * Git's response. All state lives in the per-worktree bisect log and is reset during cleanup.
 */
class Bisector(
    private val git: GitClient,
    private val worktreeDir: File,
) {

    /** Begins a bisect over (good, bad] and returns the first commit to evaluate. */
    fun start(good: String, bad: String): BisectStep {
        exec("bisect", "start")
        exec("bisect", "good", good)
        // Marking bad triggers Git to check out the first midpoint.
        val result = exec("bisect", "bad", bad)
        return interpret(result)
    }

    /** Reports the verdict for the current commit and returns Git's next step. */
    fun mark(status: EvaluationStatus): BisectStep {
        val verb = when (status) {
            EvaluationStatus.GOOD -> "good"
            EvaluationStatus.BAD -> "bad"
            EvaluationStatus.UNKNOWN -> "skip"
            EvaluationStatus.ERROR ->
                throw BisectAiException(
                    ExitCode.VALIDATION_EXECUTION_FAILURE,
                    "Cannot continue bisect: the commit evaluation errored.",
                )
        }
        val result = exec("bisect", verb)
        return interpret(result)
    }

    /** Resets bisect state. Best-effort — used from cleanup, must never throw. */
    fun reset() {
        runCatching { git.run(worktreeDir, "bisect", "reset") }
    }

    private fun interpret(result: ExecutionResult): BisectStep {
        val output = (result.stdout + "\n" + result.stderr)
        FIRST_BAD.find(output)?.let { return BisectStep.FirstBad(it.groupValues[1]) }

        val inconclusive = INCONCLUSIVE_MARKERS.firstOrNull { output.contains(it, ignoreCase = true) }
        if (inconclusive != null) return BisectStep.Inconclusive(inconclusive)

        if (result.exitCode != 0) {
            return BisectStep.Inconclusive(
                result.stderr.ifBlank { result.stdout }.trim().take(300)
                    .ifBlank { "git bisect returned a non-zero status." },
            )
        }

        val remaining = REMAINING.find(output)?.groupValues?.get(1)?.toIntOrNull()
        val commit = git.currentCommit(worktreeDir)
        return BisectStep.Evaluate(commit, remaining)
    }

    private fun exec(vararg args: String): ExecutionResult = git.run(worktreeDir, *args)

    companion object {
        private val FIRST_BAD = Regex("([0-9a-f]{7,40}) is the first bad commit")
        private val REMAINING = Regex("Bisecting:\\s+(\\d+)\\s+revisions? left")
        private val INCONCLUSIVE_MARKERS = listOf(
            "There are only 'skip'ped commits left to test",
            "The merge base",
            "This means the bug has been fixed",
        )
    }
}
