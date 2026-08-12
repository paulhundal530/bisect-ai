package com.bisectai.reporting

import com.bisectai.core.AnalysisStatus
import com.bisectai.core.CommitEvaluation
import com.bisectai.core.InvestigationResult
import com.bisectai.core.InvestigationStatus

/**
 * Renders the canonical [InvestigationResult] as a human-readable Markdown report (§39).
 *
 * Deterministic evidence (the bisect boundary, the culprit, validation results) is presented
 * separately from — and is clearly distinguished from — the AI-generated interpretation.
 */
class MarkdownRenderer {

    fun render(result: InvestigationResult): String = buildString {
        appendLine("# BisectAI Regression Investigation")
        appendLine()

        appendLine("## Investigation")
        appendLine()
        appendLine("- **Name:** ${result.investigation}")
        appendLine("- **Repository:** ${result.repository}")
        appendLine()

        appendLine("## Result")
        appendLine()
        appendLine("- **Status:** ${describe(result.status)}")
        result.message?.let { appendLine("- **Note:** $it") }
        appendLine("- **Duration:** ${formatDuration(result.duration.toMillis())}")
        appendLine()

        appendLine("## Regression Boundary")
        appendLine()
        val culprit = result.culprit
        if (culprit != null) {
            appendLine("| | Commit |")
            appendLine("| --- | --- |")
            appendLine("| Last Good Commit | `${culprit.parentSha}` |")
            appendLine("| First Bad Commit | `${culprit.sha}` |")
            appendLine()
            appendLine("Transition:")
            appendLine()
            appendLine("```")
            appendLine("${short(culprit.parentSha)} -> GOOD")
            appendLine("${short(culprit.sha)} -> BAD")
            appendLine("```")
        } else {
            appendLine("- **Original good:** `${result.originalGoodCommit}`")
            appendLine("- **Original bad:** `${result.originalBadCommit}`")
            appendLine("- No culprit was identified.")
        }
        appendLine()

        appendLine("## Culprit Commit")
        appendLine()
        if (culprit != null) {
            appendLine("- **SHA:** `${culprit.sha}`")
            appendLine("- **Subject:** ${culprit.subject}")
            appendLine("- **Author:** ${culprit.author}")
            appendLine("- **Date:** ${culprit.date}")
            if (culprit.changedFiles.isNotEmpty()) {
                appendLine("- **Changed files:**")
                culprit.changedFiles.forEach { appendLine("  - `$it`") }
            }
        } else {
            appendLine("_Not available._")
        }
        appendLine()

        appendLine("## Validation Evidence")
        appendLine()
        appendLine("_Deterministic results produced by running the investigation's validation command._")
        appendLine()
        val boundaryEvaluations = result.evaluations
            .filter { culprit != null && (it.commit == culprit.sha || it.commit == culprit.parentSha) }
            .ifEmpty { result.evaluations }
            // Keep the last (verification) evaluation per commit to avoid duplicate rows.
            .associateBy { it.commit }.values.toList()
        if (boundaryEvaluations.isEmpty()) {
            appendLine("_No evaluations recorded._")
        } else {
            boundaryEvaluations.forEach { appendEvaluation(it, culprit) }
        }
        appendLine()

        appendAnalysis(result)

        appendLine("## Execution Summary")
        appendLine()
        appendLine("- **Commits evaluated:** ${result.evaluations.size}")
        appendLine("- **Total duration:** ${formatDuration(result.duration.toMillis())}")
        result.verification?.let {
            appendLine("- **Transition verified:** ${if (it.verified) "yes" else "no"}")
        }
    }

    private fun StringBuilder.appendEvaluation(
        evaluation: CommitEvaluation,
        culprit: com.bisectai.core.CulpritCommit?,
    ) {
        val role = when (evaluation.commit) {
            culprit?.parentSha -> " (last good)"
            culprit?.sha -> " (first bad)"
            else -> ""
        }
        appendLine("**`${short(evaluation.commit)}`$role — ${evaluation.status}**")
        appendLine()
        evaluation.evidence.forEach { appendLine("- $it") }
        appendLine()
    }

    private fun StringBuilder.appendAnalysis(result: InvestigationResult) {
        appendLine("## AI Root-Cause Analysis")
        appendLine()
        appendLine("> The following is an AI-generated interpretation, not a deterministic fact.")
        appendLine()
        val analysis = result.analysis
        if (analysis == null || analysis.status != AnalysisStatus.SUCCESS || analysis.analysis == null) {
            appendLine("**AI Analysis:** Unavailable")
            appendLine()
            val reason = analysis?.failureReason ?: "Analysis was not run."
            appendLine("**Reason:** $reason")
            appendLine()
            appendLine("The deterministic bisect result above remains valid.")
            appendLine()
            appendLine("## Supporting Evidence")
            appendLine()
            appendLine("_No AI analysis available._")
            appendLine()
            appendLine("## Suggested Remediation")
            appendLine()
            appendLine("_No AI analysis available._")
            appendLine()
            return
        }

        val a = analysis.analysis!!
        appendLine("- **Summary:** ${a.summary}")
        appendLine("- **Likely cause:** ${a.likelyCause}")
        appendLine("- **Confidence:** ${"%.2f".format(a.confidence)}")
        if (a.relevantFiles.isNotEmpty()) {
            appendLine("- **Relevant files:**")
            a.relevantFiles.forEach { appendLine("  - `$it`") }
        }
        appendLine()

        appendLine("## Supporting Evidence")
        appendLine()
        if (a.supportingEvidence.isEmpty()) {
            appendLine("_None provided._")
        } else {
            a.supportingEvidence.forEach { appendLine("- $it") }
        }
        appendLine()

        appendLine("## Suggested Remediation")
        appendLine()
        appendLine(a.suggestedFix?.let { "> $it" } ?: "_No suggestion provided._")
        appendLine()
    }

    private fun describe(status: InvestigationStatus): String = when (status) {
        InvestigationStatus.FOUND -> "FOUND — first bad commit identified and verified"
        InvestigationStatus.INCONCLUSIVE -> "INCONCLUSIVE — could not identify a single first bad commit"
        InvestigationStatus.ABORTED -> "ABORTED"
        InvestigationStatus.FAILED -> "FAILED"
    }

    private fun short(sha: String): String = if (sha.length > 12) sha.substring(0, 12) else sha

    private fun formatDuration(millis: Long): String {
        val totalSeconds = millis / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
    }
}
