package com.bisectai.reporting

import com.bisectai.core.AnalysisStatus
import com.bisectai.core.InvestigationResult
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature

/**
 * Renders the canonical [InvestigationResult] as JSON (§38). Machine-readable and safe for
 * `| jq`. Derived from the same model as the Markdown report (Invariant 10).
 */
class JsonRenderer {

    private val mapper = ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT)

    fun render(result: InvestigationResult): String {
        val root = linkedMapOf<String, Any?>(
            "status" to result.status.name,
            "investigation" to result.investigation,
            "repository" to result.repository,
            "range" to linkedMapOf(
                "good" to result.originalGoodCommit,
                "bad" to result.originalBadCommit,
            ),
            "durationSeconds" to result.duration.toMillis() / 1000.0,
        )

        result.message?.let { root["message"] = it }

        result.culprit?.let { culprit ->
            root["culprit"] = linkedMapOf(
                "sha" to culprit.sha,
                "parentSha" to culprit.parentSha,
                "subject" to culprit.subject,
                "author" to culprit.author,
                "date" to culprit.date,
                "changedFiles" to culprit.changedFiles,
            )
        }

        result.verification?.let { v ->
            root["verification"] = linkedMapOf(
                "parent" to v.parentStatus.name,
                "culprit" to v.culpritStatus.name,
                "verified" to v.verified,
            )
        }

        result.analysis?.let { analysis ->
            val node = linkedMapOf<String, Any?>("status" to analysis.status.name)
            if (analysis.status == AnalysisStatus.SUCCESS && analysis.analysis != null) {
                val a = analysis.analysis!!
                node["summary"] = a.summary
                node["likelyCause"] = a.likelyCause
                node["relevantFiles"] = a.relevantFiles
                node["supportingEvidence"] = a.supportingEvidence
                node["suggestedFix"] = a.suggestedFix
                node["confidence"] = a.confidence
            } else {
                node["reason"] = analysis.failureReason
            }
            root["analysis"] = node
        }

        return mapper.writeValueAsString(root)
    }
}
