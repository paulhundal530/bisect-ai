package com.bisectai.core

/**
 * Stable CLI exit codes (§41).
 *
 * An AI analysis failure does NOT produce a non-zero exit code when the deterministic bisect
 * completed successfully; the analysis failure is represented separately in the output.
 */
enum class ExitCode(val code: Int) {
    COMPLETED(0),
    GENERAL_FAILURE(1),
    INVALID_ARGUMENTS(2),
    INVALID_INVESTIGATION(3),
    INVALID_REPOSITORY(4),
    VALIDATION_EXECUTION_FAILURE(5),
    BISECT_INCONCLUSIVE(6),
    OUTPUT_GENERATION_FAILURE(7),
}
