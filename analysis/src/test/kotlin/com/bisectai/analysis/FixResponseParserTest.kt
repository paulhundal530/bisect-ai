package com.bisectai.analysis

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class FixResponseParserTest {

    @Test
    fun `parses a valid fix proposal`() {
        val proposal = FixResponseParser.parse(
            """
            Here you go:
            {
              "edits": [
                {"path": "Calc.kt", "oldString": "a - b", "newString": "a * b", "rationale": "restore multiply"}
              ],
              "explanation": "Restored the multiplication operator."
            }
            """.trimIndent(),
        )
        assertEquals(1, proposal.edits.size)
        assertEquals("Calc.kt", proposal.edits[0].path)
        assertEquals("a - b", proposal.edits[0].oldString)
        assertEquals("a * b", proposal.edits[0].newString)
        assertEquals("Restored the multiplication operator.", proposal.explanation)
    }

    @Test
    fun `rejects a proposal with no edits`() {
        assertThrows(InvalidFixResponseException::class.java) {
            FixResponseParser.parse("""{"edits": [], "explanation": "nothing"}""")
        }
    }

    @Test
    fun `rejects an edit missing newString`() {
        assertThrows(InvalidFixResponseException::class.java) {
            FixResponseParser.parse("""{"edits": [{"path": "a", "oldString": "x"}]}""")
        }
    }

    @Test
    fun `rejects non-json`() {
        assertThrows(InvalidFixResponseException::class.java) {
            FixResponseParser.parse("no json here")
        }
    }
}
