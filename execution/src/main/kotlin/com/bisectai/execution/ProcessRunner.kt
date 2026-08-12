package com.bisectai.execution

import com.bisectai.core.ExecutionResult
import com.bisectai.core.ProcessRequest

/**
 * The single abstraction through which every external process is executed (§16).
 *
 * Implementations MUST bound every invocation by the request timeout (Invariant 5): no external
 * process may wait indefinitely.
 */
interface ProcessRunner {
    fun execute(request: ProcessRequest): ExecutionResult
}
