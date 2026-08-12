package com.bisectai.core

import java.time.Duration

/** Overall status of an investigation. */
enum class InvestigationStatus {
    /** A culprit was found and its GOOD -> BAD transition verified. */
    FOUND,

    /** Bisect could not conclusively identify a single first-bad commit. */
    INCONCLUSIVE,

    /** The investigation was aborted (e.g. boundary verification failed, execution failure). */
    ABORTED,

    /** BisectAI failed for an internal reason. */
    FAILED,
}

/** The deterministically identified first bad commit and the context gathered for analysis. */
data class CulpritCommit(
    val sha: String,
    val parentSha: String,
    val subject: String,
    val message: String,
    val author: String,
    val date: String,
    val changedFiles: List<String>,
    val diff: String,
)

/** The verified final GOOD -> BAD transition (Culprit Verification, §26). */
data class VerificationResult(
    val parentStatus: EvaluationStatus,
    val culpritStatus: EvaluationStatus,
    val verified: Boolean,
)

/**
 * The single canonical result model. Both JSON and Markdown output derive from this
 * (Invariant 10). No separate business models per output format.
 */
data class InvestigationResult(
    val status: InvestigationStatus,
    val investigation: String,
    val repository: String,
    val originalGoodCommit: String,
    val originalBadCommit: String,
    val culprit: CulpritCommit?,
    val evaluations: List<CommitEvaluation>,
    val verification: VerificationResult?,
    val analysis: AnalysisResult?,
    val duration: Duration,
    /** A human-readable explanation when [status] is not FOUND. */
    val message: String? = null,
)

/** Which rendered form the final result takes. `REPORT` means Markdown. */
enum class OutputType { JSON, REPORT }
