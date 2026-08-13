package com.bisectai.it

import com.bisectai.analysis.FakeAnalysisProvider
import com.bisectai.cli.InvestigationRunner
import com.bisectai.cli.ProgressReporter
import com.bisectai.cli.RunConfig
import com.bisectai.core.EvaluationStatus
import com.bisectai.core.InvestigationStatus
import com.bisectai.core.ProcessRequest
import com.bisectai.evaluation.CommitEvaluator
import com.bisectai.evaluation.ManualVerdict
import com.bisectai.evaluation.VerdictReader
import com.bisectai.evaluation.VerdictResult
import com.bisectai.execution.DefaultProcessRunner
import com.bisectai.git.GitClient
import com.bisectai.spec.InvestigationParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration

@Tag("e2e")
class ManualModeE2ETest {

    /**
     * Stands in for a human tester: inspects the commit's `total.txt` (as a person would read the
     * app's screen) via `git show <commit>:total.txt` and returns GOOD when it shows 42, else BAD.
     * Proves the manual classification path drives a real git bisect to the culprit — no TTY, no
     * Claude — and that human notes are recorded as evidence.
     */
    private class TesterReader(private val repo: File) : VerdictReader {
        private val runner = DefaultProcessRunner()
        var prompts = 0
            private set

        override fun read(commit: String, instructions: String?): VerdictResult {
            prompts++
            val result = runner.execute(
                ProcessRequest("git show $commit:total.txt", repo, Duration.ofSeconds(30)),
            )
            val shown = result.stdout.trim()
            return if (shown == "42") VerdictResult(ManualVerdict.GOOD, "screen showed $shown")
            else VerdictResult(ManualVerdict.BAD, "screen showed $shown")
        }
    }

    @Test
    fun `manual verdicts drive a real bisect to the culprit`(@TempDir dir: File) {
        val fixture = Fixture(dir)
        val history = fixture.build()

        val investigationFile = File(dir, ".bisectai/investigations/manual.md")
        investigationFile.parentFile.mkdirs()
        investigationFile.writeText(
            """
            ---
            version: 1
            name: "manual-calculator"
            classification:
              type: "manual"
              instructions: "Check the displayed total; 42 is GOOD."
            ---
            # Manual check
            """.trimIndent(),
        )

        val runner = InvestigationRunner(
            git = GitClient(DefaultProcessRunner()),
            evaluator = CommitEvaluator(DefaultProcessRunner(), TesterReader(dir)),
            parser = InvestigationParser(),
            analysisProvider = FakeAnalysisProvider(),
            progress = ProgressReporter(System.err, enabled = false),
        )

        val result = runner.run(
            RunConfig(dir, investigationFile, "manual-calculator", history.a, history.d),
        )

        assertEquals(InvestigationStatus.FOUND, result.status)
        assertEquals(history.c, result.culprit!!.sha, "manual bisect should still find commit C")
        assertEquals(EvaluationStatus.GOOD, result.verification!!.parentStatus)
        assertEquals(EvaluationStatus.BAD, result.verification!!.culpritStatus)
        assertTrue(
            result.evaluations.any { e -> e.evidence.any { it.startsWith("Human verdict") } },
            "human verdicts should be recorded as evidence",
        )
    }
}
