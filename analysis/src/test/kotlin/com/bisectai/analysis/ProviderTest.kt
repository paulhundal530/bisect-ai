package com.bisectai.analysis

import com.bisectai.core.AnalysisStatus
import com.bisectai.core.RootCauseAnalysisRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProviderTest {

    private val request = RootCauseAnalysisRequest(
        investigationName = "calc",
        regressionContext = "tests should pass",
        lastGoodCommit = "abc",
        culpritCommit = "def",
        culpritSubject = "change total",
        culpritMessage = "change total",
        culpritAuthor = "dev",
        culpritDate = "2026-01-01",
        changedFiles = listOf("Calculator.kt"),
        diff = "diff",
        goodEvidence = listOf("exit 0 -> GOOD"),
        badEvidence = listOf("exit 1 -> BAD"),
    )

    @Test
    fun `fake provider returns a successful analysis`() {
        val result = FakeAnalysisProvider().analyze(request)
        assertEquals(AnalysisStatus.SUCCESS, result.status)
        assertTrue(result.analysis!!.confidence in 0.0..1.0)
        assertEquals(listOf("Calculator.kt"), result.analysis!!.relevantFiles)
    }

    @Test
    fun `claude provider returns FAILED when the client cannot be created`() {
        // Simulate missing credentials: the client factory throws.
        val provider = ClaudeAnalysisProvider(
            model = "claude-sonnet-5",
            maxTokens = 100,
            analysisTimeout = java.time.Duration.ofSeconds(5),
        ) { throw IllegalStateException("no api key") }
        val result = provider.analyze(request)
        assertEquals(AnalysisStatus.FAILED, result.status)
        assertTrue(result.failureReason!!.contains("ANTHROPIC_API_KEY"))
        provider.close()
    }

    @Test
    fun `api error dumps are reduced to an actionable message`() {
        val provider = ClaudeAnalysisProvider(
            model = "m", maxTokens = 100, analysisTimeout = java.time.Duration.ofSeconds(5),
        ) { throw IllegalStateException("stub") }
        val credit = "400: AnthropicError{additionalProperties={type=error, " +
            "error={type=invalid_request_error, message=Your credit balance is too low to access " +
            "the Anthropic API. Please go to Plans & Billing to upgrade or purchase credits.}, " +
            "request_id=req_x}}"
        assertEquals(
            "HTTP 400: Your credit balance is too low to access the Anthropic API. " +
                "Please go to Plans & Billing to upgrade or purchase credits.",
            provider.cleanApiError(credit),
        )
        assertEquals(
            "HTTP 401: x-api-key header is required",
            provider.cleanApiError("401: AnthropicError{error={message=x-api-key header is required}}"),
        )
        // Non-API errors pass through unchanged.
        assertEquals("Connection reset", provider.cleanApiError("Connection reset"))
        provider.close()
    }
}
