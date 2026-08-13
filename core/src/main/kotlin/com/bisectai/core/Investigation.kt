package com.bisectai.core

/**
 * The typed, normalized representation of an investigation definition.
 *
 * The Markdown/YAML file is parsed exactly once into this model before execution begins
 * (Invariant 3). The rest of the application operates on this model, never on raw
 * Markdown/YAML (Invariant 4).
 */
data class InvestigationDefinition(
    val version: Int,
    val name: String,
    val validation: ValidationSpec,
    val classification: ClassificationSpec,
    val failurePolicy: FailurePolicy,
    /** The Markdown body: human-readable regression context supplied to Claude during analysis. */
    val context: String,
)

/** How a candidate commit is validated. Every invocation is bounded by [timeoutSeconds]. */
data class ValidationSpec(
    val command: String,
    val attempts: Int = 1,
    val warmupAttempts: Int = 0,
    val timeoutSeconds: Long = 300,
)

/**
 * Deterministic strategy for turning a validation execution into GOOD/BAD.
 *
 * Sealed so additional deterministic strategies (regex output matching, numeric metric
 * thresholds, test-result parsing) can be added later without touching the bisect engine.
 */
sealed interface ClassificationSpec {
    /**
     * Exit code 0 (or any code in [goodExitCodes]) -> GOOD; any other exit code -> BAD.
     */
    data class ExitCode(val goodExitCodes: List<Int>) : ClassificationSpec

    /**
     * A human classifies each candidate after using the app (e.g. a UI journey that cannot be
     * scripted). The validation `command` is repurposed as an optional *setup* step (build,
     * install, launch); the exit code no longer classifies. A person does — preserving the rule
     * that AI never classifies (Invariant 2). Requires an interactive terminal.
     *
     * @param instructions shown to the tester before each verdict prompt (what journey to run).
     */
    data class Manual(val instructions: String? = null) : ClassificationSpec
}

/** What to do when BisectAI cannot execute validation (timeout, launch failure). */
enum class FailurePolicy {
    /** Stop the investigation immediately. */
    ABORT,

    /** Mark the commit UNKNOWN/SKIP and continue if Git can still determine the boundary. */
    SKIP,
}
