package com.bisectai.spec

import com.bisectai.core.BisectAiException
import com.bisectai.core.ClassificationSpec
import com.bisectai.core.ExitCode
import com.bisectai.core.FailurePolicy
import com.bisectai.core.InvestigationDefinition
import com.bisectai.core.ValidationSpec
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory

/**
 * Parses an investigation Markdown file into the typed [InvestigationDefinition] exactly once,
 * validating every field per §12/§13/§20. All problems surface as
 * [BisectAiException] with [ExitCode.INVALID_INVESTIGATION] and an actionable message (§40).
 */
class InvestigationParser {

    private val mapper = ObjectMapper(YAMLFactory())
        .findAndRegisterModules()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun parse(content: String): InvestigationDefinition {
        val frontMatter = try {
            FrontMatterSplitter.split(content)
        } catch (e: IllegalArgumentException) {
            throw invalid(e.message ?: "Malformed investigation front matter.")
        }

        val yaml: InvestigationYaml = try {
            mapper.readValue(frontMatter.yaml, InvestigationYaml::class.java)
        } catch (e: Exception) {
            throw invalid("Investigation YAML could not be parsed: ${e.message}")
        } ?: throw invalid("Investigation front matter is empty.")

        val version = yaml.version ?: throw invalid("Missing required field: version.")
        if (version != SUPPORTED_VERSION) {
            throw invalid("Unsupported investigation version: $version (supported: $SUPPORTED_VERSION).")
        }

        val name = yaml.name?.takeIf { it.isNotBlank() }
            ?: throw invalid("Missing required field: name.")

        val validation = parseValidation(yaml.validation)
        val classification = parseClassification(yaml.classification)
        val failurePolicy = parseFailurePolicy(yaml.failure)

        return InvestigationDefinition(
            version = version,
            name = name,
            validation = validation,
            classification = classification,
            failurePolicy = failurePolicy,
            context = frontMatter.body,
        )
    }

    private fun parseValidation(v: ValidationYaml?): ValidationSpec {
        if (v == null) throw invalid("Missing required section: validation.")
        val command = v.command?.takeIf { it.isNotBlank() }
            ?: throw invalid("Missing required field: validation.command.")
        val attempts = v.attempts ?: 1
        val warmupAttempts = v.warmupAttempts ?: 0
        val timeoutSeconds = v.timeoutSeconds ?: 300

        if (attempts < 1) throw invalid("validation.attempts must be >= 1 (was $attempts).")
        if (warmupAttempts < 0) {
            throw invalid("validation.warmupAttempts must be >= 0 (was $warmupAttempts).")
        }
        if (timeoutSeconds <= 0) {
            throw invalid("validation.timeoutSeconds must be > 0 (was $timeoutSeconds).")
        }

        return ValidationSpec(command, attempts, warmupAttempts, timeoutSeconds)
    }

    private fun parseClassification(c: ClassificationYaml?): ClassificationSpec {
        if (c == null) throw invalid("Missing required section: classification.")
        return when (val type = c.type?.trim()?.lowercase()) {
            "exit-code" -> {
                val good = c.goodExitCodes?.takeIf { it.isNotEmpty() } ?: listOf(0)
                ClassificationSpec.ExitCode(good)
            }
            null -> throw invalid("Missing required field: classification.type.")
            else -> throw invalid(
                "Unsupported classification type: \"$type\". V1 supports: exit-code.",
            )
        }
    }

    private fun parseFailurePolicy(f: FailureYaml?): FailurePolicy {
        val raw = f?.onExecutionFailure?.trim()?.lowercase() ?: return FailurePolicy.ABORT
        return when (raw) {
            "abort" -> FailurePolicy.ABORT
            "skip" -> FailurePolicy.SKIP
            else -> throw invalid(
                "Unsupported failure.onExecutionFailure: \"$raw\". V1 supports: abort, skip.",
            )
        }
    }

    private fun invalid(message: String) =
        BisectAiException(ExitCode.INVALID_INVESTIGATION, message)

    companion object {
        const val SUPPORTED_VERSION = 1
    }
}
