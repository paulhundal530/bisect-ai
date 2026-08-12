package com.bisectai.it

import com.bisectai.analysis.FakeAnalysisProvider
import com.bisectai.cli.InvestigationRunner
import com.bisectai.cli.ProgressReporter
import com.bisectai.cli.RunConfig
import com.bisectai.core.EvaluationStatus
import com.bisectai.core.InvestigationStatus
import com.bisectai.evaluation.CommitEvaluator
import com.bisectai.execution.DefaultProcessRunner
import com.bisectai.git.GitClient
import com.bisectai.reporting.JsonRenderer
import com.bisectai.reporting.MarkdownRenderer
import com.bisectai.spec.InvestigationParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@Tag("e2e")
class EndToEndBisectTest {

    /** Deterministic end-to-end proof: finds the intentionally introduced culprit without Claude. */
    @Test
    fun `bisects a real repository to the intentionally introduced culprit`(@TempDir dir: File) {
        val fixture = Fixture(dir)
        val history = fixture.build()

        // Write the investigation into the repo (read from disk; need not be committed).
        val investigationFile = File(dir, ".bisectai/investigations/calculator-regression.md")
        investigationFile.parentFile.mkdirs()
        investigationFile.writeText(Fixture.investigation())

        val runner = InvestigationRunner(
            git = GitClient(DefaultProcessRunner()),
            evaluator = CommitEvaluator(DefaultProcessRunner()),
            parser = InvestigationParser(),
            analysisProvider = FakeAnalysisProvider(), // §45: no Claude in the deterministic test
            progress = ProgressReporter(System.err, enabled = false),
        )

        val result = runner.run(
            RunConfig(
                repository = dir,
                investigationFile = investigationFile,
                investigationLabel = "calculator-regression",
                good = history.a,
                bad = history.d,
            ),
        )

        // The deterministic bisect must identify C.
        assertEquals(InvestigationStatus.FOUND, result.status)
        assertNotNull(result.culprit)
        assertEquals(history.c, result.culprit!!.sha, "culprit should be commit C")
        assertEquals(history.b, result.culprit!!.parentSha, "last good boundary should be commit B")

        // The GOOD -> BAD transition must be verified: parent GOOD, culprit BAD.
        val verification = result.verification!!
        assertTrue(verification.verified)
        assertEquals(EvaluationStatus.GOOD, verification.parentStatus)
        assertEquals(EvaluationStatus.BAD, verification.culpritStatus)

        // Output can be rendered in both forms.
        val json = JsonRenderer().render(result)
        val markdown = MarkdownRenderer().render(result)
        assertTrue(json.contains("\"status\" : \"FOUND\""))
        assertTrue(json.contains(history.c))
        assertTrue(markdown.contains("First Bad Commit"))
        assertTrue(markdown.contains(history.c.substring(0, 12)))

        // The user's working tree and Git state are untouched (Invariants 6 & 12).
        assertEquals(history.d, fixture.head(), "main working tree HEAD must be unchanged")
        assertEquals(1, fixture.worktreeCount(), "no leftover worktrees")
    }
}
