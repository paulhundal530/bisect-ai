package com.bisectai.reporting

import com.bisectai.core.AnalysisResult
import com.bisectai.core.CommitEvaluation
import com.bisectai.core.CulpritCommit
import com.bisectai.core.EvaluationStatus
import com.bisectai.core.InvestigationResult
import com.bisectai.core.InvestigationStatus
import com.bisectai.core.RootCauseAnalysis
import com.bisectai.core.VerificationResult
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

class RendererTest {

    private val mapper = ObjectMapper()

    private fun baseResult(analysis: AnalysisResult?) = InvestigationResult(
        status = InvestigationStatus.FOUND,
        investigation = "calculator-regression",
        repository = "/repo",
        originalGoodCommit = "abc123",
        originalBadCommit = "def456",
        culprit = CulpritCommit(
            sha = "c37219a",
            parentSha = "a28120c",
            subject = "Change calculator total behavior",
            message = "Change calculator total behavior",
            author = "Dev",
            date = "2026-01-01T00:00:00Z",
            changedFiles = listOf("src/main/kotlin/Calculator.kt"),
            diff = "--- diff ---",
        ),
        evaluations = listOf(
            CommitEvaluation("a28120c", EvaluationStatus.GOOD, emptyList(), Duration.ofSeconds(12), listOf("exit 0 -> GOOD")),
            CommitEvaluation("c37219a", EvaluationStatus.BAD, emptyList(), Duration.ofSeconds(12), listOf("exit 1 -> BAD")),
        ),
        verification = VerificationResult(EvaluationStatus.GOOD, EvaluationStatus.BAD, verified = true),
        analysis = analysis,
        duration = Duration.ofSeconds(75),
    )

    private val successfulAnalysis = AnalysisResult.success(
        RootCauseAnalysis(
            summary = "The calculation behavior was changed.",
            likelyCause = "calculateTotal adds one to the sum.",
            relevantFiles = listOf("src/main/kotlin/Calculator.kt"),
            supportingEvidence = listOf("The parent passes the calculator test."),
            suggestedFix = "Restore the previous summation behavior.",
            confidence = 0.97,
        ),
    )

    @Test
    fun `json renders the canonical shape with successful analysis`() {
        val json = JsonRenderer().render(baseResult(successfulAnalysis))
        val tree = mapper.readTree(json)
        assertEquals("FOUND", tree["status"].asText())
        assertEquals("calculator-regression", tree["investigation"].asText())
        assertEquals("abc123", tree["range"]["good"].asText())
        assertEquals("def456", tree["range"]["bad"].asText())
        assertEquals("c37219a", tree["culprit"]["sha"].asText())
        assertEquals("a28120c", tree["culprit"]["parentSha"].asText())
        assertEquals("GOOD", tree["verification"]["parent"].asText())
        assertEquals("BAD", tree["verification"]["culprit"].asText())
        assertEquals("SUCCESS", tree["analysis"]["status"].asText())
        assertEquals(0.97, tree["analysis"]["confidence"].asDouble())
    }

    @Test
    fun `json represents analysis failure separately`() {
        val json = JsonRenderer().render(
            baseResult(AnalysisResult.failed("Claude authentication failed.")),
        )
        val tree = mapper.readTree(json)
        // Deterministic result is intact...
        assertEquals("FOUND", tree["status"].asText())
        assertEquals("c37219a", tree["culprit"]["sha"].asText())
        // ...and the analysis failure is represented, not fatal.
        assertEquals("FAILED", tree["analysis"]["status"].asText())
        assertEquals("Claude authentication failed.", tree["analysis"]["reason"].asText())
    }

    @Test
    fun `markdown renders all required sections`() {
        val md = MarkdownRenderer().render(baseResult(successfulAnalysis))
        listOf(
            "## Investigation",
            "## Result",
            "## Regression Boundary",
            "## Culprit Commit",
            "## Validation Evidence",
            "## AI Root-Cause Analysis",
            "## Supporting Evidence",
            "## Suggested Remediation",
            "## Execution Summary",
        ).forEach { section ->
            assertTrue(md.contains(section), "missing section: $section")
        }
        assertTrue(md.contains("c37219a"))
        assertTrue(md.contains("AI-generated interpretation"))
        assertTrue(md.contains("Restore the previous summation behavior."))
    }

    @Test
    fun `markdown shows unavailable analysis on failure`() {
        val md = MarkdownRenderer().render(
            baseResult(AnalysisResult.failed("Claude authentication failed.")),
        )
        assertTrue(md.contains("AI Analysis:** Unavailable"))
        assertTrue(md.contains("Claude authentication failed."))
        assertTrue(md.contains("deterministic bisect result above remains valid"))
    }
}
