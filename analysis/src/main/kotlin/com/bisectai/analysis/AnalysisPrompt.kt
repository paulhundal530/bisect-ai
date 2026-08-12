package com.bisectai.analysis

import com.bisectai.core.RootCauseAnalysisRequest

/**
 * Builds the bounded analysis prompt handed to Claude (§28). Claude's sole responsibility is to
 * analyze the *already-verified* GOOD -> BAD transition and explain the most likely root cause;
 * it does not select commits or classify anything (§27).
 */
object AnalysisPrompt {

    /** Upper bound on diff characters included, so the request stays bounded. */
    const val MAX_DIFF_CHARS = 24_000

    val SYSTEM_PROMPT = """
        You are a senior software engineer performing root-cause analysis of a software regression.

        BisectAI has already deterministically identified the first bad commit ("culprit") using
        git bisect and verified that the parent commit is GOOD and the culprit is BAD. You must
        NOT question which commit is the culprit or re-classify GOOD/BAD. Your only job is to
        explain WHY the culprit commit most likely introduced the regression, based on the diff
        and validation evidence provided.

        Respond with a SINGLE JSON object and nothing else — no markdown, no code fences, no prose
        before or after. The JSON must have exactly these fields:
        {
          "summary": string,              // one or two sentences
          "likelyCause": string,          // the specific code change that caused the regression
          "relevantFiles": [string],      // files most relevant to the cause
          "supportingEvidence": [string], // concrete evidence from the diff/validation
          "suggestedFix": string,         // may be empty if unclear
          "confidence": number            // between 0.0 and 1.0
        }
    """.trimIndent()

    fun buildUserPrompt(request: RootCauseAnalysisRequest): String {
        val diff = if (request.diff.length > MAX_DIFF_CHARS) {
            request.diff.take(MAX_DIFF_CHARS) + "\n... [diff truncated] ..."
        } else {
            request.diff
        }
        return buildString {
            appendLine("Investigation: ${request.investigationName}")
            appendLine()
            appendLine("Regression description / context:")
            appendLine(request.regressionContext.ifBlank { "(none provided)" })
            appendLine()
            appendLine("Last good commit: ${request.lastGoodCommit}")
            appendLine("First bad commit (culprit): ${request.culpritCommit}")
            appendLine("Culprit subject: ${request.culpritSubject}")
            appendLine("Culprit author: ${request.culpritAuthor}")
            appendLine("Culprit date: ${request.culpritDate}")
            appendLine()
            appendLine("Culprit commit message:")
            appendLine(request.culpritMessage.ifBlank { "(none)" })
            appendLine()
            appendLine("Changed files:")
            if (request.changedFiles.isEmpty()) appendLine("(none)")
            else request.changedFiles.forEach { appendLine("- $it") }
            appendLine()
            appendLine("GOOD validation evidence:")
            request.goodEvidence.forEach { appendLine("- $it") }
            appendLine()
            appendLine("BAD validation evidence:")
            request.badEvidence.forEach { appendLine("- $it") }
            appendLine()
            appendLine("Diff (last good -> culprit):")
            appendLine(diff.ifBlank { "(empty)" })
        }
    }
}
