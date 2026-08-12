package com.bisectai.analysis

import com.bisectai.core.AnalysisProvider
import com.bisectai.core.AnalysisResult
import com.bisectai.core.RootCauseAnalysis
import com.bisectai.core.RootCauseAnalysisRequest

/**
 * Deterministic [AnalysisProvider] used by tests so they never require a real Anthropic API key
 * (§45). Echoes the request into a plausible, fixed analysis.
 */
class FakeAnalysisProvider(
    private val confidence: Double = 0.9,
) : AnalysisProvider {

    override fun analyze(request: RootCauseAnalysisRequest): AnalysisResult =
        AnalysisResult.success(
            RootCauseAnalysis(
                summary = "The regression was introduced by commit ${request.culpritCommit}.",
                likelyCause = "Change in \"${request.culpritSubject}\" altered the validated behavior.",
                relevantFiles = request.changedFiles,
                supportingEvidence = listOf(
                    "Parent ${request.lastGoodCommit} classified GOOD.",
                    "Culprit ${request.culpritCommit} classified BAD.",
                ),
                suggestedFix = "Review the change in ${request.culpritSubject} and revert the behavioral difference.",
                confidence = confidence,
            ),
        )
}
