package com.bisectai.spec

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * Raw YAML front-matter shape. Fields are nullable so that "missing" can be distinguished from
 * "invalid" and reported with an actionable message during [InvestigationParser] normalization.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
internal data class InvestigationYaml(
    val version: Int? = null,
    val name: String? = null,
    val validation: ValidationYaml? = null,
    val classification: ClassificationYaml? = null,
    val failure: FailureYaml? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class ValidationYaml(
    val command: String? = null,
    val attempts: Int? = null,
    val warmupAttempts: Int? = null,
    val timeoutSeconds: Long? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class ClassificationYaml(
    val type: String? = null,
    val goodExitCodes: List<Int>? = null,
    val instructions: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class FailureYaml(
    val onExecutionFailure: String? = null,
)
