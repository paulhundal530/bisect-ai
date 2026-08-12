package com.bisectai.cli

import com.bisectai.analysis.ClaudeAnalysisProvider
import com.bisectai.core.BisectAiException
import com.bisectai.core.ExitCode
import com.bisectai.core.InvestigationResult
import com.bisectai.core.InvestigationStatus
import com.bisectai.core.OutputType
import com.bisectai.evaluation.CommitEvaluator
import com.bisectai.execution.DefaultProcessRunner
import com.bisectai.git.GitClient
import com.bisectai.reporting.JsonRenderer
import com.bisectai.reporting.MarkdownRenderer
import com.bisectai.spec.InvestigationParser
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.io.File
import java.util.concurrent.Callable

@Command(
    name = "run",
    mixinStandardHelpOptions = true,
    description = ["Run an investigation: verify boundaries, bisect, verify the culprit, and analyze."],
)
class RunCommand : Callable<Int> {

    @Option(names = ["--repo"], description = ["Repository directory (default: current directory)."])
    var repo: File = File(System.getProperty("user.dir"))

    @Option(names = ["--investigation"], description = ["Investigation name under .bisectai/investigations/."])
    var investigation: String? = null

    @Option(names = ["--requirements"], description = ["Path to an investigation file outside the repo (escape hatch)."])
    var requirements: File? = null

    @Option(names = ["--good"], required = true, description = ["Known-good commit."])
    lateinit var good: String

    @Option(names = ["--bad"], required = true, description = ["Known-bad commit."])
    lateinit var bad: String

    @Option(names = ["--output-type"], description = ["json or report (default: report)."])
    var outputType: OutputType = OutputType.REPORT

    @Option(names = ["--output"], description = ["Write the result to this path (default: stdout)."])
    var output: File? = null

    @Option(names = ["--model"], description = ["Claude model for analysis (default: ${ClaudeAnalysisProvider.DEFAULT_MODEL})."])
    var model: String = ClaudeAnalysisProvider.DEFAULT_MODEL

    private val progress = ProgressReporter(System.err)

    override fun call(): Int {
        val config = try {
            resolveConfig()
        } catch (e: BisectAiException) {
            System.err.println(e.message)
            return e.exitCode.code
        }

        val analysisProvider = ClaudeAnalysisProvider(model = model)
        progress.banner()
        progress.line()
        progress.step("Investigation: ${config.investigationLabel}")
        progress.step("Repository: ${config.repository.absolutePath}")
        progress.line()

        return try {
            val runner = InvestigationRunner(
                git = GitClient(DefaultProcessRunner()),
                evaluator = CommitEvaluator(DefaultProcessRunner()),
                parser = InvestigationParser(),
                analysisProvider = analysisProvider,
                progress = progress,
            )
            val result = runner.run(config)
            writeOutput(result)
            exitCodeFor(result)
        } catch (e: BisectAiException) {
            System.err.println()
            System.err.println(e.message)
            e.exitCode.code
        } catch (e: Exception) {
            System.err.println()
            System.err.println("Investigation failed: ${e.message}")
            ExitCode.GENERAL_FAILURE.code
        } finally {
            analysisProvider.close()
        }
    }

    private fun resolveConfig(): RunConfig {
        if ((investigation == null) == (requirements == null)) {
            throw BisectAiException(
                ExitCode.INVALID_ARGUMENTS,
                "Provide exactly one of --investigation or --requirements (they are mutually exclusive).",
            )
        }
        val (file, label) = when {
            investigation != null ->
                File(repo, ".bisectai/investigations/$investigation.md") to investigation!!
            else -> requirements!! to requirements!!.name
        }

        // Preflight: output location writable where determinable (§20.13).
        output?.let { out ->
            val parent = out.absoluteFile.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw BisectAiException(
                    ExitCode.OUTPUT_GENERATION_FAILURE,
                    "Output directory cannot be created: ${parent.absolutePath}",
                )
            }
            if (parent != null && !parent.canWrite()) {
                throw BisectAiException(
                    ExitCode.OUTPUT_GENERATION_FAILURE,
                    "Output directory is not writable: ${parent.absolutePath}",
                )
            }
        }

        return RunConfig(
            repository = repo,
            investigationFile = file,
            investigationLabel = label,
            good = good,
            bad = bad,
        )
    }

    private fun writeOutput(result: InvestigationResult) {
        val rendered = when (outputType) {
            OutputType.JSON -> JsonRenderer().render(result)
            OutputType.REPORT -> MarkdownRenderer().render(result)
        }
        val out = output
        if (out == null) {
            // Progress is on stderr, so JSON on stdout stays clean for `| jq`.
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
            progress.step("Report written to:")
            progress.line()
            progress.step(out.path)
        }
    }

    private fun exitCodeFor(result: InvestigationResult): Int = when (result.status) {
        // AI failure never changes the exit code when the bisect itself succeeded (§41, Invariant 9).
        InvestigationStatus.FOUND -> ExitCode.COMPLETED.code
        InvestigationStatus.INCONCLUSIVE -> ExitCode.BISECT_INCONCLUSIVE.code
        InvestigationStatus.ABORTED -> ExitCode.GENERAL_FAILURE.code
        InvestigationStatus.FAILED -> ExitCode.GENERAL_FAILURE.code
    }
}
