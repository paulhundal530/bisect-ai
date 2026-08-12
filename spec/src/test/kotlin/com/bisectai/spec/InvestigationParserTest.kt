package com.bisectai.spec

import com.bisectai.core.BisectAiException
import com.bisectai.core.ClassificationSpec
import com.bisectai.core.FailurePolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InvestigationParserTest {

    private val parser = InvestigationParser()

    private val validDefinition = """
        ---
        version: 1
        name: "Calculation regression"
        validation:
          command: "./gradlew test"
          attempts: 2
          warmupAttempts: 1
          timeoutSeconds: 120
        classification:
          type: "exit-code"
          goodExitCodes:
            - 0
        failure:
          onExecutionFailure: "skip"
        ---

        # Regression Description

        The calculator test suite began failing.
    """.trimIndent()

    @Test
    fun `parses a valid definition into the typed model`() {
        val def = parser.parse(validDefinition)
        assertEquals(1, def.version)
        assertEquals("Calculation regression", def.name)
        assertEquals("./gradlew test", def.validation.command)
        assertEquals(2, def.validation.attempts)
        assertEquals(1, def.validation.warmupAttempts)
        assertEquals(120, def.validation.timeoutSeconds)
        assertEquals(FailurePolicy.SKIP, def.failurePolicy)
        val classification = def.classification as ClassificationSpec.ExitCode
        assertEquals(listOf(0), classification.goodExitCodes)
        assertTrue(def.context.contains("calculator test suite"))
    }

    @Test
    fun `defaults are applied for optional fields`() {
        val def = parser.parse(
            """
            ---
            version: 1
            name: "Minimal"
            validation:
              command: "true"
            classification:
              type: "exit-code"
            ---
            body
            """.trimIndent(),
        )
        assertEquals(1, def.validation.attempts)
        assertEquals(0, def.validation.warmupAttempts)
        assertEquals(300, def.validation.timeoutSeconds)
        assertEquals(FailurePolicy.ABORT, def.failurePolicy)
        assertEquals(listOf(0), (def.classification as ClassificationSpec.ExitCode).goodExitCodes)
    }

    @Test
    fun `missing required field is rejected`() {
        val ex = assertThrows(BisectAiException::class.java) {
            parser.parse(
                """
                ---
                version: 1
                validation:
                  command: "true"
                classification:
                  type: "exit-code"
                ---
                """.trimIndent(),
            )
        }
        assertTrue(ex.message!!.contains("name"), ex.message)
    }

    @Test
    fun `missing validation command is rejected`() {
        val ex = assertThrows(BisectAiException::class.java) {
            parser.parse(
                """
                ---
                version: 1
                name: "x"
                validation:
                  attempts: 1
                classification:
                  type: "exit-code"
                ---
                """.trimIndent(),
            )
        }
        assertTrue(ex.message!!.contains("validation.command"), ex.message)
    }

    @Test
    fun `unsupported version is rejected`() {
        val ex = assertThrows(BisectAiException::class.java) {
            parser.parse(validDefinition.replace("version: 1", "version: 99"))
        }
        assertTrue(ex.message!!.contains("version"), ex.message)
    }

    @Test
    fun `invalid timeout is rejected`() {
        val ex = assertThrows(BisectAiException::class.java) {
            parser.parse(validDefinition.replace("timeoutSeconds: 120", "timeoutSeconds: 0"))
        }
        assertTrue(ex.message!!.contains("timeoutSeconds"), ex.message)
    }

    @Test
    fun `invalid attempts is rejected`() {
        val ex = assertThrows(BisectAiException::class.java) {
            parser.parse(validDefinition.replace("attempts: 2", "attempts: 0"))
        }
        assertTrue(ex.message!!.contains("attempts"), ex.message)
    }

    @Test
    fun `invalid classification type is rejected`() {
        val ex = assertThrows(BisectAiException::class.java) {
            parser.parse(validDefinition.replace("type: \"exit-code\"", "type: \"magic\""))
        }
        assertTrue(ex.message!!.contains("classification type"), ex.message)
    }

    @Test
    fun `missing front matter is rejected`() {
        val ex = assertThrows(BisectAiException::class.java) {
            parser.parse("# no front matter\njust markdown")
        }
        assertTrue(ex.message!!.contains("front matter"), ex.message)
    }

    @Test
    fun `generated template parses back into a valid definition`() {
        val def = parser.parse(InvestigationTemplate.render("My Investigation"))
        assertEquals("My Investigation", def.name)
        assertEquals("./gradlew test", def.validation.command)
    }
}
