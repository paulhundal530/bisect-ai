package com.bisectai.analysis

import com.bisectai.core.RootCauseAnalysis
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/** Raised when Claude's response cannot be validated into a [RootCauseAnalysis]. */
class InvalidAnalysisResponseException(message: String) : RuntimeException(message)

/**
 * Parses and validates Claude's JSON reply into a typed [RootCauseAnalysis] (§33). Tolerates a
 * reply wrapped in prose or code fences by extracting the outermost JSON object. Enforces the
 * confidence bound [0.0, 1.0].
 */
object AnalysisResponseParser {

    private val mapper = ObjectMapper()

    fun parse(raw: String): RootCauseAnalysis {
        val json = extractJsonObject(raw)
            ?: throw InvalidAnalysisResponseException("No JSON object found in analysis response.")
        val node = try {
            mapper.readTree(json)
        } catch (e: Exception) {
            throw InvalidAnalysisResponseException("Analysis response was not valid JSON: ${e.message}")
        }

        val summary = requireText(node, "summary")
        val likelyCause = requireText(node, "likelyCause")
        val confidence = node["confidence"]?.takeIf { it.isNumber }?.asDouble()
            ?: throw InvalidAnalysisResponseException("Missing or non-numeric field: confidence.")
        if (confidence < 0.0 || confidence > 1.0) {
            throw InvalidAnalysisResponseException("confidence must be in [0.0, 1.0] (was $confidence).")
        }

        val suggestedFix = node["suggestedFix"]?.takeIf { it.isTextual }?.asText()?.ifBlank { null }

        return RootCauseAnalysis(
            summary = summary,
            likelyCause = likelyCause,
            relevantFiles = stringList(node, "relevantFiles"),
            supportingEvidence = stringList(node, "supportingEvidence"),
            suggestedFix = suggestedFix,
            confidence = confidence,
        )
    }

    private fun requireText(node: JsonNode, field: String): String {
        val value = node[field]?.takeIf { it.isTextual }?.asText()
        if (value.isNullOrBlank()) {
            throw InvalidAnalysisResponseException("Missing or empty required field: $field.")
        }
        return value
    }

    private fun stringList(node: JsonNode, field: String): List<String> {
        val array = node[field] ?: return emptyList()
        if (!array.isArray) return emptyList()
        return array.mapNotNull { it.takeIf { n -> n.isTextual }?.asText()?.ifBlank { null } }
    }

    /** Extracts the substring from the first '{' to the matching final '}'. */
    private fun extractJsonObject(raw: String): String? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return raw.substring(start, end + 1)
    }
}
