package com.bisectai.analysis

import com.bisectai.core.FixEdit
import com.bisectai.core.FixProposal
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/** Raised when a fix response cannot be validated into a [FixProposal]. */
class InvalidFixResponseException(message: String) : RuntimeException(message)

/** Parses and validates Claude's JSON fix reply into a typed [FixProposal]. */
object FixResponseParser {

    private val mapper = ObjectMapper()

    fun parse(raw: String): FixProposal {
        val json = extractJsonObject(raw)
            ?: throw InvalidFixResponseException("No JSON object found in fix response.")
        val node = try {
            mapper.readTree(json)
        } catch (e: Exception) {
            throw InvalidFixResponseException("Fix response was not valid JSON: ${e.message}")
        }

        val editsNode = node["edits"]
        if (editsNode == null || !editsNode.isArray || editsNode.isEmpty) {
            throw InvalidFixResponseException("Fix response contained no edits.")
        }
        val edits = editsNode.map { toEdit(it) }
        val explanation = node["explanation"]?.takeIf { it.isTextual }?.asText()?.trim().orEmpty()
        return FixProposal(edits, explanation)
    }

    private fun toEdit(node: JsonNode): FixEdit {
        val path = node["path"]?.takeIf { it.isTextual }?.asText()
            ?: throw InvalidFixResponseException("An edit is missing its \"path\".")
        val oldString = node["oldString"]?.takeIf { it.isTextual }?.asText()
            ?: throw InvalidFixResponseException("Edit for $path is missing \"oldString\".")
        val newString = node["newString"]?.takeIf { it.isTextual }?.asText()
            ?: throw InvalidFixResponseException("Edit for $path is missing \"newString\".")
        if (oldString.isEmpty()) {
            throw InvalidFixResponseException("Edit for $path has an empty \"oldString\".")
        }
        return FixEdit(path, oldString, newString, node["rationale"]?.asText()?.ifBlank { null })
    }

    private fun extractJsonObject(raw: String): String? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return raw.substring(start, end + 1)
    }
}
