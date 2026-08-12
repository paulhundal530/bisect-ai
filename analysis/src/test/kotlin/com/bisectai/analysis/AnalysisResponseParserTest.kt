package com.bisectai.analysis

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AnalysisResponseParserTest {

    private val validJson = """
        {
          "summary": "The calculation behavior changed.",
          "likelyCause": "calculateTotal adds one to the sum.",
          "relevantFiles": ["src/main/kotlin/Calculator.kt"],
          "supportingEvidence": ["Parent passes", "Culprit fails"],
          "suggestedFix": "Restore the previous behavior.",
          "confidence": 0.97
        }
    """.trimIndent()

    @Test
    fun `parses a valid response`() {
        val a = AnalysisResponseParser.parse(validJson)
        assertEquals("The calculation behavior changed.", a.summary)
        assertEquals(0.97, a.confidence)
        assertEquals(listOf("src/main/kotlin/Calculator.kt"), a.relevantFiles)
        assertEquals(2, a.supportingEvidence.size)
    }

    @Test
    fun `tolerates prose and code fences around the json`() {
        val wrapped = "Here is the analysis:\n```json\n$validJson\n```\nThanks!"
        val a = AnalysisResponseParser.parse(wrapped)
        assertEquals(0.97, a.confidence)
    }

    @Test
    fun `empty suggested fix becomes null`() {
        val a = AnalysisResponseParser.parse(validJson.replace("\"Restore the previous behavior.\"", "\"\""))
        assertNull(a.suggestedFix)
    }

    @Test
    fun `rejects out-of-range confidence`() {
        assertThrows(InvalidAnalysisResponseException::class.java) {
            AnalysisResponseParser.parse(validJson.replace("0.97", "1.5"))
        }
    }

    @Test
    fun `rejects missing required field`() {
        assertThrows(InvalidAnalysisResponseException::class.java) {
            AnalysisResponseParser.parse(validJson.replace("\"summary\"", "\"ignored\""))
        }
    }

    @Test
    fun `rejects non-json`() {
        assertThrows(InvalidAnalysisResponseException::class.java) {
            AnalysisResponseParser.parse("no json here")
        }
    }
}
