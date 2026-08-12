package com.bisectai.core

/**
 * An actionable, categorized failure. Carries the [ExitCode] the CLI should surface so error
 * handling stays consistent across subsystems (§40, §41).
 */
class BisectAiException(
    val exitCode: ExitCode,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
