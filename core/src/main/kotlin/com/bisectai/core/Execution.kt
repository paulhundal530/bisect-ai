package com.bisectai.core

import java.time.Duration

/** A request to run a single external command, bounded by a timeout. */
data class ProcessRequest(
    val command: String,
    val workingDir: java.io.File,
    val timeout: Duration,
    val env: Map<String, String> = emptyMap(),
)

/**
 * The result of a single external process execution.
 *
 * [exitCode] is null when the process did not exit normally (e.g. it timed out or could not
 * be launched); [timedOut] distinguishes a timeout from other launch failures.
 */
data class ExecutionResult(
    val command: String,
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val duration: Duration,
    val timedOut: Boolean,
) {
    val launched: Boolean get() = exitCode != null || timedOut
}
