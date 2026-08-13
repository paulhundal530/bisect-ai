package com.bisectai.evaluation

import com.bisectai.core.BisectAiException
import com.bisectai.core.ClassificationSpec
import com.bisectai.core.EvaluationStatus
import com.bisectai.core.ExecutionResult
import com.bisectai.core.FailurePolicy
import com.bisectai.core.InvestigationDefinition
import com.bisectai.core.ProcessRequest
import com.bisectai.core.ValidationSpec
import com.bisectai.execution.ProcessRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Duration

class ManualEvaluationTest {

    private class FixedRunner(private val exit: Int?, private val timedOut: Boolean = false) : ProcessRunner {
        var calls = 0
            private set
        override fun execute(request: ProcessRequest): ExecutionResult {
            calls++
            return ExecutionResult("cmd", exit, "", if (exit == null) "boom" else "", Duration.ofMillis(1), timedOut)
        }
    }

    private class ScriptedReader(private val result: VerdictResult) : VerdictReader {
        var reads = 0
            private set
        override fun read(commit: String, instructions: String?): VerdictResult {
            reads++
            return result
        }
    }

    private fun manualDefinition(
        command: String = "",
        failurePolicy: FailurePolicy = FailurePolicy.ABORT,
    ) = InvestigationDefinition(
        version = 1,
        name = "manual",
        validation = ValidationSpec(command, timeoutSeconds = 30),
        classification = ClassificationSpec.Manual("Test the flow"),
        failurePolicy = failurePolicy,
        context = "",
    )

    @Test
    fun `human GOOD verdict yields GOOD without any setup command`() {
        val runner = FixedRunner(0)
        val reader = ScriptedReader(VerdictResult(ManualVerdict.GOOD, "looked fine"))
        val eval = CommitEvaluator(runner, reader).evaluate("c", File("."), manualDefinition())
        assertEquals(EvaluationStatus.GOOD, eval.status)
        assertEquals(0, runner.calls, "no setup command should run when command is blank")
        assertEquals(1, reader.reads)
        assertTrue(eval.evidence.any { it.contains("Observation: looked fine") })
    }

    @Test
    fun `human BAD verdict yields BAD and runs the setup command`() {
        val runner = FixedRunner(0)
        val reader = ScriptedReader(VerdictResult(ManualVerdict.BAD, "7x6 showed 43"))
        val eval = CommitEvaluator(runner, reader)
            .evaluate("c", File("."), manualDefinition(command = "./gradlew installDebug"))
        assertEquals(EvaluationStatus.BAD, eval.status)
        assertEquals(1, runner.calls)
        assertTrue(eval.evidence.any { it.contains("Human verdict: BAD") })
    }

    @Test
    fun `SKIP verdict maps to UNKNOWN`() {
        val eval = CommitEvaluator(FixedRunner(0), ScriptedReader(VerdictResult(ManualVerdict.SKIP, null)))
            .evaluate("c", File("."), manualDefinition())
        assertEquals(EvaluationStatus.UNKNOWN, eval.status)
    }

    @Test
    fun `ABORT verdict throws`() {
        val evaluator = CommitEvaluator(FixedRunner(0), ScriptedReader(VerdictResult(ManualVerdict.ABORT, null)))
        assertThrows(BisectAiException::class.java) {
            evaluator.evaluate("c", File("."), manualDefinition())
        }
    }

    @Test
    fun `setup failure with abort policy yields ERROR and never prompts`() {
        val reader = ScriptedReader(VerdictResult(ManualVerdict.GOOD, null))
        val eval = CommitEvaluator(FixedRunner(1), reader)
            .evaluate("c", File("."), manualDefinition(command = "false", failurePolicy = FailurePolicy.ABORT))
        assertEquals(EvaluationStatus.ERROR, eval.status)
        assertEquals(0, reader.reads, "must not ask the human when setup failed")
    }

    @Test
    fun `setup failure with skip policy yields UNKNOWN`() {
        val eval = CommitEvaluator(FixedRunner(1), ScriptedReader(VerdictResult(ManualVerdict.GOOD, null)))
            .evaluate("c", File("."), manualDefinition(command = "false", failurePolicy = FailurePolicy.SKIP))
        assertEquals(EvaluationStatus.UNKNOWN, eval.status)
    }

    @Test
    fun `manual evaluation requires a verdict reader`() {
        val evaluator = CommitEvaluator(FixedRunner(0)) // no reader
        assertThrows(BisectAiException::class.java) {
            evaluator.evaluate("c", File("."), manualDefinition())
        }
    }
}
