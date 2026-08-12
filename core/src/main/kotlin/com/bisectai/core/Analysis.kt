package com.bisectai.core

/**
 * The bounded input handed to an [analysis provider][AnalysisProvider] once the culprit has
 * been deterministically identified and the GOOD -> BAD transition verified.
 *
 * AI begins only AFTER this point; it never participates in bisect selection, classification,
 * or boundary verification (AI Boundary, §27).
 */
data class RootCauseAnalysisRequest(
    val investigationName: String,
    val regressionContext: String,
    val lastGoodCommit: String,
    val culpritCommit: String,
    val culpritSubject: String,
    val culpritMessage: String,
    val culpritAuthor: String,
    val culpritDate: String,
    val changedFiles: List<String>,
    val diff: String,
    val goodEvidence: List<String>,
    val badEvidence: List<String>,
)

/** Structured root-cause analysis produced by Claude. Confidence is in [0.0, 1.0]. */
data class RootCauseAnalysis(
    val summary: String,
    val likelyCause: String,
    val relevantFiles: List<String>,
    val supportingEvidence: List<String>,
    val suggestedFix: String?,
    val confidence: Double,
)

/** Whether post-bisect AI analysis succeeded. Analysis is best-effort (Invariant 9). */
enum class AnalysisStatus { SUCCESS, FAILED, SKIPPED }

/** The result of the (best-effort) analysis stage, carrying success or a failure reason. */
data class AnalysisResult(
    val status: AnalysisStatus,
    val analysis: RootCauseAnalysis?,
    val failureReason: String?,
) {
    companion object {
        fun success(analysis: RootCauseAnalysis) = AnalysisResult(AnalysisStatus.SUCCESS, analysis, null)
        fun failed(reason: String) = AnalysisResult(AnalysisStatus.FAILED, null, reason)
        fun skipped(reason: String) = AnalysisResult(AnalysisStatus.SKIPPED, null, reason)
    }
}

/**
 * Abstraction over root-cause analysis providers so the rest of the application is not coupled
 * to Anthropic (§30). Implementations MUST NOT throw for provider/network/credential failures;
 * they return an [AnalysisResult] with [AnalysisStatus.FAILED] instead (Invariant 9).
 */
interface AnalysisProvider {
    fun analyze(request: RootCauseAnalysisRequest): AnalysisResult
}
