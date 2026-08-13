package com.bisectai.reporting

import com.fasterxml.jackson.databind.ObjectMapper

/** The fields the `fix` command needs from a prior investigation JSON report. */
data class ResultSummary(
    val status: String,
    val investigation: String,
    val repository: String,
    val culpritSha: String?,
    val parentSha: String?,
    val changedFiles: List<String>,
    val analysisHint: String?,
)

/** Reads the JSON produced by [JsonRenderer] back into the fields `fix` consumes. */
class ResultReader {

    private val mapper = ObjectMapper()

    fun read(json: String): ResultSummary {
        val root = mapper.readTree(json)
        val culprit = root["culprit"]
        val analysis = root["analysis"]
        val hint = analysis?.get("likelyCause")?.asText()
            ?: analysis?.get("summary")?.asText()
        return ResultSummary(
            status = root["status"]?.asText().orEmpty(),
            investigation = root["investigation"]?.asText().orEmpty(),
            repository = root["repository"]?.asText().orEmpty(),
            culpritSha = culprit?.get("sha")?.asText(),
            parentSha = culprit?.get("parentSha")?.asText(),
            changedFiles = culprit?.get("changedFiles")?.mapNotNull { it.asText() } ?: emptyList(),
            analysisHint = hint,
        )
    }
}
