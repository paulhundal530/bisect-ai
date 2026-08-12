package com.bisectai.core

import java.time.Duration

/** The outcome of evaluating an investigation against one specific commit. */
enum class EvaluationStatus {
    /** Validation ran and the classification rule determined the regression is absent. */
    GOOD,

    /** Validation ran and the classification rule determined the regression is present. */
    BAD,

    /** The commit could not be reliably classified. Maps to `git bisect skip`. */
    UNKNOWN,

    /** BisectAI itself could not perform the evaluation. Normally aborts the investigation. */
    ERROR,
}

/** A structured record of one commit's evaluation. */
data class CommitEvaluation(
    val commit: String,
    val status: EvaluationStatus,
    val attempts: List<ExecutionResult>,
    val duration: Duration,
    val evidence: List<String>,
)
