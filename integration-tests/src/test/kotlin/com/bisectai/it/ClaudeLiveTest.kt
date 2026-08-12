package com.bisectai.it

import com.bisectai.analysis.ClaudeAnalysisProvider
import com.bisectai.cli.InvestigationRunner
import com.bisectai.cli.ProgressReporter
import com.bisectai.cli.RunConfig
import com.bisectai.core.AnalysisStatus
import com.bisectai.core.InvestigationStatus
import com.bisectai.evaluation.CommitEvaluator
import com.bisectai.execution.DefaultProcessRunner
import com.bisectai.git.GitClient
import com.bisectai.spec.InvestigationParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Live Claude analysis test (§45). Runs only via `./gradlew claudeIntegrationTest` and auto-skips
 * when no Anthropic credentials are present, so normal builds never call Claude.
 */
@Tag("claude")
class ClaudeLiveTest {

    @Test
    fun `real Claude analysis runs on the verified transition`(@TempDir dir: File) {
        val hasCredentials = !System.getenv("ANTHROPIC_API_KEY").isNullOrBlank() ||
            !System.getenv("ANTHROPIC_AUTH_TOKEN").isNullOrBlank()
        assumeTrue(hasCredentials, "No Anthropic credentials; skipping live Claude test.")

        val fixture = Fixture(dir)
        val history = fixture.build()
        val investigationFile = File(dir, ".bisectai/investigations/calculator-regression.md")
        investigationFile.parentFile.mkdirs()
        investigationFile.writeText(Fixture.investigation())

        val provider = ClaudeAnalysisProvider()
        try {
            val runner = InvestigationRunner(
                git = GitClient(DefaultProcessRunner()),
                evaluator = CommitEvaluator(DefaultProcessRunner()),
                parser = InvestigationParser(),
                analysisProvider = provider,
                progress = ProgressReporter(System.err, enabled = false),
            )
            val result = runner.run(
                RunConfig(dir, investigationFile, "calculator-regression", history.a, history.d),
            )

            // Deterministic result is unaffected by AI; analysis is best-effort.
            assertEquals(InvestigationStatus.FOUND, result.status)
            assertEquals(history.c, result.culprit!!.sha)
            // If credentials are valid, analysis should succeed; otherwise the run still succeeds.
            val analysis = result.analysis!!
            assertEquals(AnalysisStatus.SUCCESS, analysis.status, analysis.failureReason ?: "")
        } finally {
            provider.close()
        }
    }
}
