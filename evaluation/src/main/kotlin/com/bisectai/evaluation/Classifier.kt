package com.bisectai.evaluation

import com.bisectai.core.ClassificationSpec
import com.bisectai.core.EvaluationStatus
import com.bisectai.core.ExecutionResult

/**
 * Turns a single successful execution into GOOD or BAD using the deterministic classification
 * strategy (§13). Only called for executions that launched and produced an exit code; timeouts
 * and launch failures are handled by the failure policy, not here.
 */
object Classifier {

    fun classify(spec: ClassificationSpec, result: ExecutionResult): EvaluationStatus =
        when (spec) {
            is ClassificationSpec.ExitCode -> {
                if (result.exitCode in spec.goodExitCodes) EvaluationStatus.GOOD
                else EvaluationStatus.BAD
            }
        }
}
