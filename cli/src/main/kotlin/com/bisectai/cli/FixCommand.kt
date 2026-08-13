package com.bisectai.cli

import com.bisectai.analysis.ClaudeFixProvider
import com.bisectai.core.BisectAiException
import com.bisectai.core.ExitCode
import com.bisectai.core.FixResult
import com.bisectai.core.FixStatus
import com.bisectai.core.FixStrategy
import com.bisectai.core.OutputType
import com.bisectai.evaluation.CommitEvaluator
import com.bisectai.execution.DefaultProcessRunner
import com.bisectai.git.GitClient
import com.bisectai.reporting.FixJsonRenderer
import com.bisectai.reporting.FixReportRenderer
import com.bisectai.reporting.ResultReader
import com.bisectai.spec.InvestigationParser
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.PrintStream
import java.util.concurrent.Callable

@Command(
    name = "fix",
    mixinStandardHelpOptions = true,
    description = ["Propose and verify a fix for a previously identified culprit, on a new branch."],
)
class FixCommand : Callable<Int> {

    @Option(names = ["--result"], required = true, description = ["Investigation result JSON (from `run --output-type json`)."])
    lateinit var result: File

    @Option(names = ["--repo"], description = ["Repository directory (default: current directory)."])
    var repo: File = File(System.getProperty("user.dir"))

    @Option(names = ["--investigation"], description = ["Investigation name used for the run (to locate and re-run its validation)."])
    var investigation: String? = null

    @Option(names = ["--requirements"], description = ["Investigation file, if not under .bisectai/investigations/."])
    var requirements: File? = null

    @Option(names = ["--strategy"], description = ["ai (default) or revert."])
    var strategy: FixStrategy = FixStrategy.AI

    @Option(names = ["--branch"], description = ["Branch name for the fix commit (default: bisectai/fix-...)."])
    var branch: String? = null

    @Option(names = ["--max-attempts"], description = ["AI fix attempts before falling back to revert (default: 3)."])
    var maxAttempts: Int = 3

    @Option(names = ["--dry-run"], description = ["Show the verified fix diff but create no commit."])
    var dryRun: Boolean = false

    @Option(names = ["--yes"], description = ["Create the fix commit without an interactive confirmation."])
    var assumeYes: Boolean = false

    @Option(names = ["--output-type"], description = ["json or report (default: report)."])
    var outputType: OutputType = OutputType.REPORT

    @Option(names = ["--output"], description = ["Write the fix result to this path (default: stdout)."])
    var output: File? = null

    @Option(names = ["--model"], description = ["Claude model for fix generation."])
    var model: String? = null

    private val progress = ProgressReporter(System.err)

    override fun call(): Int {
        val fixProvider = model?.let { ClaudeFixProvider(model = it) } ?: ClaudeFixProvider()
        return try {
            val runner = FixRunner(
                git = GitClient(DefaultProcessRunner()),
                evaluator = CommitEvaluator(DefaultProcessRunner(), TtyVerdictReader()),
                parser = InvestigationParser(),
                fixProvider = fixProvider,
                resultReader = ResultReader(),
                progress = progress,
            )
            val investigationFile = requirements
                ?: investigation?.let { File(repo, ".bisectai/investigations/$it.md") }
            val config = FixConfig(
                repository = repo,
                resultFile = result,
                investigationFileOverride = investigationFile,
                strategy = strategy,
                branchName = branch,
                maxAttempts = maxAttempts,
                dryRun = dryRun,
            )
            val fix = runner.run(config, ::confirm)
            writeOutput(fix)
            exitCodeFor(fix)
        } catch (e: BisectAiException) {
            System.err.println()
            System.err.println(e.message)
            e.exitCode.code
        } catch (e: Exception) {
            System.err.println()
            System.err.println("Fix failed: ${e.message}")
            ExitCode.GENERAL_FAILURE.code
        } finally {
            fixProvider.close()
        }
    }

    /** Shows the proposed diff and asks for confirmation on the controlling terminal. */
    private fun confirm(diff: String): Boolean {
        if (assumeYes) return true
        return runCatching {
            FileOutputStream(TTY).use { rawOut ->
                val tty = PrintStream(rawOut, true)
                BufferedReader(InputStreamReader(FileInputStream(TTY))).use { reader ->
                    tty.println()
                    tty.println("Proposed fix:")
                    tty.println(diff.ifBlank { "(no diff)" })
                    tty.print("Create the fix commit on a new branch? [y/N]: ")
                    tty.flush()
                    reader.readLine()?.trim()?.lowercase() in setOf("y", "yes")
                }
            }
        }.getOrElse {
            System.err.println("No interactive terminal to confirm the fix. Re-run with --yes or --dry-run.")
            false
        }
    }

    private fun writeOutput(fix: FixResult) {
        val rendered = when (outputType) {
            OutputType.JSON -> FixJsonRenderer().render(fix)
            OutputType.REPORT -> FixReportRenderer().render(fix)
        }
        val out = output
        if (out == null) {
            println(rendered)
        } else {
            try {
                out.writeText(rendered)
            } catch (e: Exception) {
                throw BisectAiException(
                    ExitCode.OUTPUT_GENERATION_FAILURE,
                    "Failed to write output to ${out.absolutePath}: ${e.message}",
                )
            }
            progress.line()
            progress.step("Fix report written to: ${out.path}")
        }
    }

    private fun exitCodeFor(fix: FixResult): Int = when (fix.status) {
        FixStatus.FIXED -> ExitCode.COMPLETED.code
        FixStatus.ABORTED, FixStatus.UNVERIFIED, FixStatus.FAILED -> ExitCode.GENERAL_FAILURE.code
    }

    private companion object {
        const val TTY = "/dev/tty"
    }
}
