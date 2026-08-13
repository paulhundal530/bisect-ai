package com.bisectai.analysis

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.MessageCreateParams
import com.bisectai.core.FixProvider
import com.bisectai.core.FixProposal
import com.bisectai.core.FixRequest
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Raised when a fix cannot be generated (so the orchestrator can fall back to REVERT). */
class FixGenerationException(message: String) : RuntimeException(message)

/**
 * [FixProvider] backed by Anthropic's Java SDK. Mirrors [ClaudeAnalysisProvider]: single lazily
 * created client (§32), env-based auth (§31), structured+validated output. Throws
 * [FixGenerationException] on any failure so the caller can fall back to a deterministic revert.
 */
class ClaudeFixProvider internal constructor(
    private val model: String,
    private val maxTokens: Long,
    private val timeout: Duration,
    private val clientFactory: () -> AnthropicClient,
) : FixProvider, AutoCloseable {

    constructor(
        model: String = ClaudeAnalysisProvider.DEFAULT_MODEL,
        maxTokens: Long = 4096,
        timeout: Duration = Duration.ofSeconds(180),
    ) : this(model, maxTokens, timeout, { AnthropicOkHttpClient.fromEnv() })

    private var client: AnthropicClient? = null
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "claude-fix").apply { isDaemon = true }
    }

    override fun propose(request: FixRequest): FixProposal {
        val active = client() ?: throw FixGenerationException(
            "Claude client could not be initialized. Ensure ANTHROPIC_API_KEY is set.",
        )
        val future = executor.submit<FixProposal> { call(active, request) }
        return try {
            future.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            future.cancel(true)
            throw FixGenerationException("Claude fix generation failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun call(client: AnthropicClient, request: FixRequest): FixProposal {
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(maxTokens)
            .system(FixPrompt.SYSTEM_PROMPT)
            .addUserMessage(FixPrompt.buildUserPrompt(request))
            .build()
        val message = client.messages().create(params)
        val text = message.content()
            .mapNotNull { if (it.isText()) it.asText().text() else null }
            .joinToString("\n").trim()
        if (text.isBlank()) throw FixGenerationException("Claude returned an empty fix response.")
        return try {
            FixResponseParser.parse(text)
        } catch (e: InvalidFixResponseException) {
            throw FixGenerationException("Claude returned an invalid fix: ${e.message}")
        }
    }

    private fun client(): AnthropicClient? {
        if (client == null) {
            client = runCatching { clientFactory() }.getOrNull()
        }
        return client
    }

    override fun close() {
        runCatching { client?.close() }
        runCatching { executor.shutdownNow() }
    }
}
