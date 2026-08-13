package com.bisectai.analysis

import com.bisectai.core.FixRequest

/** Builds the bounded prompt for proposing a fix confined to the implicated files. */
object FixPrompt {

    const val MAX_DIFF_CHARS = 16_000
    const val MAX_FILE_CHARS = 16_000

    val SYSTEM_PROMPT = """
        You are a senior engineer repairing a software regression that BisectAI has already
        localized deterministically via git bisect. You are given the culprit commit's diff and
        the CURRENT contents (at HEAD) of the files that commit touched.

        Produce a minimal fix to the CURRENT code that removes the regression while preserving any
        legitimate changes. You may ONLY edit the files provided; do not invent new files or edit
        anything else.

        Respond with a SINGLE JSON object and nothing else — no markdown, no code fences:
        {
          "edits": [
            {
              "path": "<one of the provided file paths>",
              "oldString": "<exact text to replace; must occur exactly once in that file>",
              "newString": "<replacement text>",
              "rationale": "<why>"
            }
          ],
          "explanation": "<one or two sentences on the fix>"
        }

        Keep oldString large enough to be unique but as small as possible. Preserve surrounding
        formatting and indentation exactly.
    """.trimIndent()

    fun buildUserPrompt(request: FixRequest): String = buildString {
        appendLine("Investigation: ${request.investigationName}")
        appendLine()
        appendLine("Regression context:")
        appendLine(request.regressionContext.ifBlank { "(none provided)" })
        appendLine()
        request.analysisHint?.takeIf { it.isNotBlank() }?.let {
            appendLine("Prior root-cause hint: $it")
            appendLine()
        }
        appendLine("Culprit ${request.culpritSha} (parent ${request.parentSha}) introduced this diff:")
        appendLine(request.culpritDiff.take(MAX_DIFF_CHARS).ifBlank { "(empty)" })
        appendLine()
        appendLine("You may edit ONLY these files. Current contents at HEAD:")
        request.implicatedFiles.forEach { file ->
            appendLine()
            appendLine("=== ${file.path} ===")
            appendLine(file.content.take(MAX_FILE_CHARS))
        }
        request.previousFailure?.takeIf { it.isNotBlank() }?.let {
            appendLine()
            appendLine("Your previous attempt did NOT fix it. Validation reported:")
            appendLine(it.take(2_000))
        }
    }
}
