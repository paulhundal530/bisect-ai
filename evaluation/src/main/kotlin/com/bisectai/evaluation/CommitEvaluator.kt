package com.bisectai.evaluation

import com.bisectai.core.CommitEvaluation
import com.bisectai.core.EvaluationStatus
import com.bisectai.core.ExecutionResult
import com.bisectai.core.FailurePolicy
import com.bisectai.core.InvestigationDefinition
import com.bisectai.core.ProcessRequest
import com.bisectai.execution.ProcessRunner
import java.io.File
import java.time.Duration

/**
 * Evaluates one checked-out commit by running the deterministic validation command in [workingDir]
 * and classifying the result (§12/§14).
 *
 * - Warmup attempts run first and are discarded (they do not contribute to classification, §12).
 * - Each measured attempt is classified; if all agree the commit is GOOD/BAD, otherwise it is
 *   UNKNOWN ("could not reliably classify" → maps to `git bisect skip`).
 * - A timeout or launch failure is an *execution failure*: the [FailurePolicy] decides whether the
 *   commit becomes ERROR (abort) or UNKNOWN (skip).
 */
class CommitEvaluator(private val runner: ProcessRunner) {

    fun evaluate(
        commit: String,
        workingDir: File,
        definition: InvestigationDefinition,
    ): CommitEvaluation {
        val validation = definition.validation
        val timeout = Duration.ofSeconds(validation.timeoutSeconds)
        val request = ProcessRequest(validation.command, workingDir, timeout)

        // Warmup attempts: run and discard.
        repeat(validation.warmupAttempts) { runner.execute(request) }

        val measured = mutableListOf<ExecutionResult>()
        val evidence = mutableListOf<String>()
        var totalDuration = Duration.ZERO

        repeat(validation.attempts) { index ->
            val result = runner.execute(request)
            measured += result
            totalDuration = totalDuration.plus(result.duration)

            // Execution failure short-circuits into the failure policy.
            if (result.timedOut || !result.launched) {
                val reason = if (result.timedOut) {
                    "Attempt ${index + 1}: timed out after ${validation.timeoutSeconds}s"
                } else {
                    "Attempt ${index + 1}: process could not be launched"
                }
                evidence += reason
                appendStderr(evidence, result)
                val status = when (definition.failurePolicy) {
                    FailurePolicy.ABORT -> EvaluationStatus.ERROR
                    FailurePolicy.SKIP -> EvaluationStatus.UNKNOWN
                }
                return CommitEvaluation(commit, status, measured, totalDuration, evidence)
            }

            val classification = Classifier.classify(definition.classification, result)
            evidence += "Attempt ${index + 1}: exit ${result.exitCode} -> $classification"
        }

        val classifications = measured.map { Classifier.classify(definition.classification, it) }
        val status = when {
            classifications.all { it == EvaluationStatus.GOOD } -> EvaluationStatus.GOOD
            classifications.all { it == EvaluationStatus.BAD } -> EvaluationStatus.BAD
            else -> {
                evidence += "Attempts disagreed ($classifications) -> UNKNOWN (skip)"
                EvaluationStatus.UNKNOWN
            }
        }
        return CommitEvaluation(commit, status, measured, totalDuration, evidence)
    }

    private fun appendStderr(evidence: MutableList<String>, result: ExecutionResult) {
        val stderr = result.stderr.trim()
        if (stderr.isNotBlank()) {
            evidence += "stderr: " + stderr.lines().take(5).joinToString(" ").take(300)
        }
    }
}
