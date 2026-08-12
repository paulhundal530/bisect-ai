package com.bisectai.evaluation

import com.bisectai.core.ClassificationSpec
import com.bisectai.core.EvaluationStatus
import com.bisectai.core.ExecutionResult
import com.bisectai.core.FailurePolicy
import com.bisectai.core.InvestigationDefinition
import com.bisectai.core.ProcessRequest
import com.bisectai.core.ValidationSpec
import com.bisectai.execution.ProcessRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Duration

class CommitEvaluatorTest {

    /** ProcessRunner that replays a fixed queue of results. */
    private class ScriptedRunner(results: List<ExecutionResult>) : ProcessRunner {
        private val queue = ArrayDeque(results)
        var executions = 0
            private set

        override fun execute(request: ProcessRequest): ExecutionResult {
            executions++
            return queue.removeFirst()
        }
    }

    private fun result(exit: Int?, timedOut: Boolean = false) = ExecutionResult(
        command = "cmd",
        exitCode = exit,
        stdout = "",
        stderr = if (timedOut) "killed" else "",
        duration = Duration.ofMillis(5),
        timedOut = timedOut,
    )

    private fun definition(
        attempts: Int = 1,
        warmupAttempts: Int = 0,
        failurePolicy: FailurePolicy = FailurePolicy.ABORT,
        goodExitCodes: List<Int> = listOf(0),
    ) = InvestigationDefinition(
        version = 1,
        name = "t",
        validation = ValidationSpec("cmd", attempts, warmupAttempts, 300),
        classification = ClassificationSpec.ExitCode(goodExitCodes),
        failurePolicy = failurePolicy,
        context = "",
    )

    @Test
    fun `good exit code classifies GOOD`() {
        val runner = ScriptedRunner(listOf(result(0)))
        val eval = CommitEvaluator(runner).evaluate("c", File("."), definition())
        assertEquals(EvaluationStatus.GOOD, eval.status)
    }

    @Test
    fun `bad exit code classifies BAD`() {
        val runner = ScriptedRunner(listOf(result(1)))
        val eval = CommitEvaluator(runner).evaluate("c", File("."), definition())
        assertEquals(EvaluationStatus.BAD, eval.status)
    }

    @Test
    fun `warmup attempts are discarded from classification`() {
        // 1 warmup (bad), then 1 measured (good) -> GOOD.
        val runner = ScriptedRunner(listOf(result(1), result(0)))
        val eval = CommitEvaluator(runner)
            .evaluate("c", File("."), definition(attempts = 1, warmupAttempts = 1))
        assertEquals(2, runner.executions)
        assertEquals(1, eval.attempts.size) // only measured attempts recorded
        assertEquals(EvaluationStatus.GOOD, eval.status)
    }

    @Test
    fun `disagreeing attempts are UNKNOWN`() {
        val runner = ScriptedRunner(listOf(result(0), result(1)))
        val eval = CommitEvaluator(runner).evaluate("c", File("."), definition(attempts = 2))
        assertEquals(EvaluationStatus.UNKNOWN, eval.status)
    }

    @Test
    fun `timeout with abort policy yields ERROR`() {
        val runner = ScriptedRunner(listOf(result(null, timedOut = true)))
        val eval = CommitEvaluator(runner)
            .evaluate("c", File("."), definition(failurePolicy = FailurePolicy.ABORT))
        assertEquals(EvaluationStatus.ERROR, eval.status)
    }

    @Test
    fun `timeout with skip policy yields UNKNOWN`() {
        val runner = ScriptedRunner(listOf(result(null, timedOut = true)))
        val eval = CommitEvaluator(runner)
            .evaluate("c", File("."), definition(failurePolicy = FailurePolicy.SKIP))
        assertEquals(EvaluationStatus.UNKNOWN, eval.status)
    }

    @Test
    fun `custom good exit codes are honored`() {
        val runner = ScriptedRunner(listOf(result(2)))
        val eval = CommitEvaluator(runner)
            .evaluate("c", File("."), definition(goodExitCodes = listOf(0, 2)))
        assertEquals(EvaluationStatus.GOOD, eval.status)
    }
}
