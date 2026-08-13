package com.bisectai.it

import com.bisectai.analysis.FakeAnalysisProvider
import com.bisectai.cli.FixConfig
import com.bisectai.cli.FixRunner
import com.bisectai.cli.InvestigationRunner
import com.bisectai.cli.ProgressReporter
import com.bisectai.cli.RunConfig
import com.bisectai.core.FixEdit
import com.bisectai.core.FixProposal
import com.bisectai.core.FixProvider
import com.bisectai.core.FixRequest
import com.bisectai.core.FixStatus
import com.bisectai.core.FixStrategy
import com.bisectai.core.ProcessRequest
import com.bisectai.evaluation.CommitEvaluator
import com.bisectai.execution.DefaultProcessRunner
import com.bisectai.git.GitClient
import com.bisectai.reporting.JsonRenderer
import com.bisectai.reporting.ResultReader
import com.bisectai.spec.InvestigationParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration

@Tag("e2e")
class FixE2ETest {

    private val runner = DefaultProcessRunner()

    /** A "smart" fix provider that restores the total to 42. */
    private class GoodFixProvider : FixProvider {
        override fun propose(request: FixRequest): FixProposal = FixProposal(
            edits = listOf(FixEdit("total.txt", "43", "42", "restore the correct total")),
            explanation = "Restored the total for {20, 22} to 42.",
        )
    }

    /** A provider that never produces a usable fix, forcing the revert fallback. */
    private class HopelessFixProvider : FixProvider {
        override fun propose(request: FixRequest): FixProposal =
            throw RuntimeException("cannot generate a fix")
    }

    private fun git(dir: File, vararg args: String): String {
        val cmd = "git " + args.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }
        val r = runner.execute(ProcessRequest(cmd, dir, Duration.ofSeconds(30)))
        check(r.exitCode == 0) { "git ${args.joinToString(" ")}: ${r.stderr.ifBlank { r.stdout }}" }
        return r.stdout.trim()
    }

    /** Builds the fixture, runs the investigation, and returns (investigationFile, resultJsonFile). */
    private fun setUp(dir: File): Pair<File, File> {
        val fixture = Fixture(dir)
        val history = fixture.build()
        val investigationFile = File(dir, ".bisectai/investigations/calc.md")
        investigationFile.parentFile.mkdirs()
        investigationFile.writeText(Fixture.investigation())

        val result = InvestigationRunner(
            git = GitClient(DefaultProcessRunner()),
            evaluator = CommitEvaluator(DefaultProcessRunner()),
            parser = InvestigationParser(),
            analysisProvider = FakeAnalysisProvider(),
            progress = ProgressReporter(System.err, enabled = false),
        ).run(RunConfig(dir, investigationFile, "calc", history.a, history.d))

        assertEquals(history.c, result.culprit!!.sha)
        val resultJson = File(dir, "result.json").apply { writeText(JsonRenderer().render(result)) }
        return investigationFile to resultJson
    }

    private fun fixRunner(fixProvider: FixProvider) = FixRunner(
        git = GitClient(DefaultProcessRunner()),
        evaluator = CommitEvaluator(DefaultProcessRunner()),
        parser = InvestigationParser(),
        fixProvider = fixProvider,
        resultReader = ResultReader(),
        progress = ProgressReporter(System.err, enabled = false),
    )

    private fun config(dir: File, investigationFile: File, resultJson: File, strategy: FixStrategy) =
        FixConfig(
            repository = dir,
            resultFile = resultJson,
            investigationFileOverride = investigationFile,
            strategy = strategy,
            branchName = null,
            maxAttempts = 2,
            dryRun = false,
        )

    @Test
    fun `ai fix is verified and committed on a new branch`(@TempDir dir: File) {
        val (investigationFile, resultJson) = setUp(dir)
        val headBefore = git(dir, "rev-parse", "HEAD")

        val fix = fixRunner(GoodFixProvider())
            .run(config(dir, investigationFile, resultJson, FixStrategy.AI)) { true }

        assertEquals(FixStatus.FIXED, fix.status)
        assertEquals(FixStrategy.AI, fix.strategyUsed)
        assertTrue(fix.verified)
        assertNotNull(fix.branch)
        // The fix branch exists and its total.txt is corrected.
        assertTrue(git(dir, "branch", "--list", fix.branch!!).isNotBlank())
        assertEquals("42", git(dir, "show", "${fix.branch}:total.txt"))
        // The user's working tree is untouched: still on main at the same HEAD, and no worktrees left.
        assertEquals(headBefore, git(dir, "rev-parse", "HEAD"))
        assertEquals("main", git(dir, "rev-parse", "--abbrev-ref", "HEAD"))
        assertEquals(1, git(dir, "worktree", "list").lines().count { it.isNotBlank() })
    }

    @Test
    fun `falls back to a verified revert when the ai cannot fix it`(@TempDir dir: File) {
        val (investigationFile, resultJson) = setUp(dir)

        val fix = fixRunner(HopelessFixProvider())
            .run(config(dir, investigationFile, resultJson, FixStrategy.AI)) { true }

        assertEquals(FixStatus.FIXED, fix.status)
        assertEquals(FixStrategy.REVERT, fix.strategyUsed)
        assertTrue(fix.verified)
        assertEquals("42", git(dir, "show", "${fix.branch}:total.txt"))
    }
}
