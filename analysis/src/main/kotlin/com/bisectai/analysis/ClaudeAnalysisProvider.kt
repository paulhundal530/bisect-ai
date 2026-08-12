package com.bisectai.analysis

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.MessageCreateParams
import com.bisectai.core.AnalysisProvider
import com.bisectai.core.AnalysisResult
import com.bisectai.core.RootCauseAnalysisRequest
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * [AnalysisProvider] backed by Anthropic's official Java SDK (§30–34).
 *
 * - Owns and reuses a single client for the process lifetime (§32); the client is created lazily
 *   so that missing credentials do not crash the app but instead surface as a best-effort
 *   analysis failure (Invariant 9).
 * - Uses standard environment-based auth (`ANTHROPIC_API_KEY` / `ANTHROPIC_AUTH_TOKEN`); secrets
 *   are never taken from CLI args or investigation files (§31).
 * - Asks Claude for structured JSON and validates it (§33). Any failure — missing credentials,
 *   auth error, rate limit, network failure, invalid response, timeout — becomes an
 *   [AnalysisResult] with FAILED status; it never throws (§34).
 */
class ClaudeAnalysisProvider internal constructor(
    private val model: String,
    private val maxTokens: Long,
    private val analysisTimeout: Duration,
    // The SDK client seam is kept internal so the Anthropic type does not leak into consumers'
    // classpaths; the public constructor never exposes it.
    private val clientFactory: () -> AnthropicClient,
) : AnalysisProvider, AutoCloseable {

    /** Public constructor: uses environment-based auth via the official SDK (§31). */
    constructor(
        model: String = DEFAULT_MODEL,
        maxTokens: Long = 1500,
        analysisTimeout: Duration = Duration.ofSeconds(120),
    ) : this(model, maxTokens, analysisTimeout, { AnthropicOkHttpClient.fromEnv() })

    private var client: AnthropicClient? = null
    private var clientError: String? = null
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "claude-analysis").apply { isDaemon = true }
    }

    override fun analyze(request: RootCauseAnalysisRequest): AnalysisResult {
        val activeClient = client() ?: return AnalysisResult.failed(
            "Claude client could not be initialized: $clientError. " +
                "Ensure ANTHROPIC_API_KEY (or ANTHROPIC_AUTH_TOKEN) is set.",
        )

        val future: Future<AnalysisResult> = executor.submit<AnalysisResult> {
            callClaude(activeClient, request)
        }
        return try {
            future.get(analysisTimeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            AnalysisResult.failed("Claude analysis timed out after ${analysisTimeout.toSeconds()}s.")
        } catch (e: Exception) {
            AnalysisResult.failed("Claude analysis failed: ${rootMessage(e)}")
        }
    }

    private fun callClaude(
        client: AnthropicClient,
        request: RootCauseAnalysisRequest,
    ): AnalysisResult {
        return try {
            val params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .system(AnalysisPrompt.SYSTEM_PROMPT)
                .addUserMessage(AnalysisPrompt.buildUserPrompt(request))
                .build()

            val message = client.messages().create(params)
            val text = message.content()
                .mapNotNull { block -> if (block.isText()) block.asText().text() else null }
                .joinToString("\n")
                .trim()

            if (text.isBlank()) {
                return AnalysisResult.failed("Claude returned an empty response.")
            }
            AnalysisResult.success(AnalysisResponseParser.parse(text))
        } catch (e: InvalidAnalysisResponseException) {
            AnalysisResult.failed("Claude returned an invalid analysis: ${e.message}")
        } catch (e: Exception) {
            AnalysisResult.failed("Claude analysis failed: ${rootMessage(e)}")
        }
    }

    private fun client(): AnthropicClient? {
        if (client == null && clientError == null) {
            try {
                client = clientFactory()
            } catch (e: Exception) {
                clientError = rootMessage(e)
            }
        }
        return client
    }

    private fun rootMessage(e: Throwable): String =
        e.message ?: e.cause?.message ?: e.javaClass.simpleName

    override fun close() {
        runCatching { client?.close() }
        runCatching { executor.shutdownNow() }
    }

    companion object {
        const val DEFAULT_MODEL = "claude-sonnet-5"
    }
}
