package com.bisectai.reporting

import com.bisectai.core.FixResult
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature

/** Renders a [FixResult] as JSON. */
class FixJsonRenderer {
    private val mapper = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)

    fun render(result: FixResult): String {
        val root = linkedMapOf<String, Any?>(
            "status" to result.status.name,
            "investigation" to result.investigation,
            "repository" to result.repository,
            "culprit" to result.culprit,
            "strategy" to result.strategyUsed?.name,
            "branch" to result.branch,
            "verified" to result.verified,
            "filesChanged" to result.filesChanged,
            "explanation" to result.explanation,
        )
        result.message?.let { root["message"] = it }
        return mapper.writeValueAsString(root)
    }
}

/** Renders a [FixResult] as a human-readable Markdown report. */
class FixReportRenderer {
    fun render(result: FixResult): String = buildString {
        appendLine("# BisectAI Fix")
        appendLine()
        appendLine("- **Investigation:** ${result.investigation}")
        appendLine("- **Repository:** ${result.repository}")
        appendLine("- **Culprit:** `${result.culprit}`")
        appendLine("- **Status:** ${result.status}")
        result.strategyUsed?.let { appendLine("- **Strategy:** $it") }
        appendLine("- **Verified:** ${if (result.verified) "yes" else "no"}")
        result.branch?.let { appendLine("- **Fix branch:** `$it`") }
        result.message?.let { appendLine("- **Note:** $it") }
        appendLine()
        result.explanation?.takeIf { it.isNotBlank() }?.let {
            appendLine("## What changed")
            appendLine()
            appendLine("> The fix is an AI-generated (or deterministic revert) change; review before merging.")
            appendLine()
            appendLine(it)
            appendLine()
        }
        if (result.filesChanged.isNotEmpty()) {
            appendLine("## Files changed")
            appendLine()
            result.filesChanged.forEach { appendLine("- `$it`") }
            appendLine()
        }
        if (result.diff.isNotBlank()) {
            appendLine("## Diff")
            appendLine()
            appendLine("```diff")
            appendLine(result.diff.trim())
            appendLine("```")
        }
    }
}
