package com.bisectai.core

/** A single scoped edit: replace [oldString] (which must match exactly once) with [newString]. */
data class FixEdit(
    val path: String,
    val oldString: String,
    val newString: String,
    val rationale: String? = null,
)

/** A proposed fix: a set of edits confined to the implicated files, plus an explanation. */
data class FixProposal(
    val edits: List<FixEdit>,
    val explanation: String,
)

/** The current content of one implicated file, supplied to the fix provider. */
data class FileContent(val path: String, val content: String)

/**
 * Bounded input for proposing a fix. Mirrors [RootCauseAnalysisRequest] but is aimed at editing
 * *current* code (at HEAD) to remove the regression the culprit introduced.
 */
data class FixRequest(
    val investigationName: String,
    val regressionContext: String,
    val culpritSha: String,
    val parentSha: String,
    val culpritDiff: String,
    /** Files the deterministic bisect implicated — edits MUST be confined to these. */
    val implicatedFiles: List<FileContent>,
    /** Optional prior root-cause hint from the analysis stage. */
    val analysisHint: String?,
    /** On a retry, the validation output that shows why the previous attempt did not fix it. */
    val previousFailure: String? = null,
)

/** Which repair strategy to use. */
enum class FixStrategy {
    /** Ask the AI to edit the implicated files; fall back to REVERT if it can't be verified. */
    AI,

    /** Deterministically reverse the culprit commit (`git revert`). No AI. */
    REVERT,
}

/** Outcome of a fix attempt. */
enum class FixStatus {
    /** A fix was produced and verified (validation now GOOD, or the tester confirmed it). */
    FIXED,

    /** A fix was produced but could not be verified. */
    UNVERIFIED,

    /** No fix could be produced or applied. */
    FAILED,

    /** The user declined to create the fix commit. */
    ABORTED,
}

/**
 * Abstraction over fix providers (parallels [AnalysisProvider]). Implementations propose edits
 * confined to the request's implicated files. May throw on generation failure; the orchestrator
 * falls back to REVERT.
 */
interface FixProvider {
    fun propose(request: FixRequest): FixProposal
}

/** Canonical result of the fix command; rendered to JSON or Markdown. */
data class FixResult(
    val status: FixStatus,
    val investigation: String,
    val repository: String,
    val culprit: String,
    val strategyUsed: FixStrategy?,
    val branch: String?,
    val filesChanged: List<String>,
    val diff: String,
    val explanation: String?,
    val verified: Boolean,
    val message: String? = null,
)
