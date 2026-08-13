package com.bisectai.cli

import com.bisectai.core.AnalysisProvider
import com.bisectai.core.AnalysisStatus
import com.bisectai.core.BisectAiException
import com.bisectai.core.ClassificationSpec
import com.bisectai.core.CommitEvaluation
import com.bisectai.core.CulpritCommit
import com.bisectai.core.EvaluationStatus
import com.bisectai.core.ExitCode
import com.bisectai.core.InvestigationDefinition
import com.bisectai.core.InvestigationResult
import com.bisectai.core.InvestigationStatus
import com.bisectai.core.RootCauseAnalysisRequest
import com.bisectai.core.VerificationResult
import com.bisectai.evaluation.CommitEvaluator
import com.bisectai.git.BisectStep
import com.bisectai.git.GitClient
import com.bisectai.git.RunWorkspace
import com.bisectai.spec.InvestigationParser
import java.io.File
import java.time.Duration

/** Everything the runner needs to execute one investigation. */
data class RunConfig(
    val repository: File,
    val investigationFile: File,
    val investigationLabel: String,
    val good: String,
    val bad: String,
)

/**
 * Orchestrates the full investigation workflow as the state machine of §25:
 * PREFLIGHT -> VERIFYING_BOUNDARIES -> BISECTING -> VERIFYING_CULPRIT -> ANALYZING ->
 * WRITING_OUTPUT (done by the caller). Deliberately Picocli-independent so integration tests can
 * drive it directly with a fake analysis provider.
 *
 * Temporary Git state is always cleaned up (Invariant 12) and the user's working tree is never
 * touched (Invariant 6): all evaluation happens in an isolated worktree.
 */
class InvestigationRunner(
    private val git: GitClient,
    private val evaluator: CommitEvaluator,
    private val parser: InvestigationParser,
    private val analysisProvider: AnalysisProvider,
    private val progress: ProgressReporter,
) {

    fun run(config: RunConfig): InvestigationResult {
        val startNanos = System.nanoTime()
        val evaluations = mutableListOf<CommitEvaluation>()

        // ---- PREFLIGHT (§20): fail fast, before any expensive work. ----
        val definition = preflight(config)
        progress.step("Validating investigation... OK")

        val goodSha = git.resolveCommit(config.repository, config.good)
            ?: throw BisectAiException(ExitCode.INVALID_REPOSITORY, "Good commit not found: ${config.good}")
        val badSha = git.resolveCommit(config.repository, config.bad)
            ?: throw BisectAiException(ExitCode.INVALID_REPOSITORY, "Bad commit not found: ${config.bad}")
        if (goodSha == badSha) {
            throw BisectAiException(
                ExitCode.INVALID_REPOSITORY,
                "The good and bad commits are identical ($goodSha); nothing to bisect.",
            )
        }
        if (!git.isAncestor(config.repository, goodSha, badSha)) {
            throw BisectAiException(
                ExitCode.INVALID_REPOSITORY,
                "Invalid range: --good ${config.good} is not an ancestor of --bad ${config.bad}.",
            )
        }
        progress.step("Validating Git range... OK")

        val workspace = RunWorkspace.create(git, config.repository, badSha)
        val worktree = workspace.worktreeDir
        try {
            // ---- VERIFYING_BOUNDARIES (§21, Invariant 7). ----
            progress.line()
            progress.step("Verifying boundaries...")
            progress.line()
            val goodBoundary = evaluateCheckedOut(goodSha, worktree, definition)
            evaluations += goodBoundary
            progress.commitResult(goodSha, goodBoundary.status, goodBoundary.duration)
            verifyBoundary(goodBoundary, EvaluationStatus.GOOD, config.good, "good")

            val badBoundary = evaluateCheckedOut(badSha, worktree, definition)
            evaluations += badBoundary
            progress.commitResult(badSha, badBoundary.status, badBoundary.duration)
            verifyBoundary(badBoundary, EvaluationStatus.BAD, config.bad, "bad")

            // ---- BISECTING (§22). Git owns the binary search. ----
            val total = git.countRange(worktree, goodSha, badSha)
            progress.line()
            progress.step("Starting bisect across $total commits...")
            progress.line()

            val bisector = workspace.bisector()
            var step = bisector.start(goodSha, badSha)
            var culpritSha: String? = null
            var iteration = 0
            loop@ while (iteration++ < MAX_BISECT_STEPS) {
                when (val current = step) {
                    is BisectStep.Evaluate -> {
                        // Git has already checked out the candidate in the worktree.
                        val eval = evaluateCommit(current.commit, worktree, definition, checkout = false)
                        evaluations += eval
                        progress.commitResult(
                            current.commit, eval.status, eval.duration,
                            current.remainingApprox, indexLabel = iteration.toString(),
                        )
                        if (eval.status == EvaluationStatus.ERROR) {
                            throw BisectAiException(
                                ExitCode.VALIDATION_EXECUTION_FAILURE,
                                executionFailureMessage(current.commit, definition),
                            )
                        }
                        step = bisector.mark(eval.status)
                    }
                    is BisectStep.FirstBad -> {
                        culpritSha = current.commit
                        break@loop
                    }
                    is BisectStep.Inconclusive -> {
                        return inconclusive(
                            config, goodSha, badSha, evaluations, startNanos,
                            "Bisect was inconclusive: ${current.reason}",
                        )
                    }
                }
            }
            bisector.reset()

            if (culpritSha == null) {
                return inconclusive(
                    config, goodSha, badSha, evaluations, startNanos,
                    "Bisect did not converge on a single first bad commit.",
                )
            }

            progress.line()
            val culpritInfo = git.commitInfo(worktree, culpritSha)
            progress.step("Culprit identified:")
            progress.line()
            progress.step(culpritSha)
            progress.step(culpritInfo.subject)

            // ---- VERIFYING_CULPRIT (§26, Invariant 8). ----
            val parentSha = if (culpritInfo.parentSha.isNotBlank()) {
                culpritInfo.parentSha
            } else {
                git.parentOf(worktree, culpritSha)
            }
            progress.line()
            progress.step("Verifying transition...")
            progress.line()
            val parentEval = evaluateCheckedOut(parentSha, worktree, definition)
            evaluations += parentEval
            progress.commitResult(parentSha, parentEval.status, parentEval.duration)
            val culpritEval = evaluateCheckedOut(culpritSha, worktree, definition)
            evaluations += culpritEval
            progress.commitResult(culpritSha, culpritEval.status, culpritEval.duration)

            val verified = parentEval.status == EvaluationStatus.GOOD &&
                culpritEval.status == EvaluationStatus.BAD
            val verification = VerificationResult(parentEval.status, culpritEval.status, verified)
            if (!verified) {
                return InvestigationResult(
                    status = InvestigationStatus.INCONCLUSIVE,
                    investigation = definition.name,
                    repository = config.repository.absolutePath,
                    originalGoodCommit = goodSha,
                    originalBadCommit = badSha,
                    culprit = null,
                    evaluations = evaluations,
                    verification = verification,
                    analysis = null,
                    duration = elapsed(startNanos),
                    message = "Could not verify the GOOD -> BAD transition around $culpritSha; " +
                        "returning an inconclusive result rather than asserting false confidence.",
                )
            }
            progress.step("Transition verified.")

            // Gather bounded context for analysis.
            val changedFiles = git.changedFiles(worktree, parentSha, culpritSha)
            val diff = git.diff(worktree, parentSha, culpritSha)
            val culprit = CulpritCommit(
                sha = culpritSha,
                parentSha = parentSha,
                subject = culpritInfo.subject,
                message = culpritInfo.message,
                author = culpritInfo.author,
                date = culpritInfo.date,
                changedFiles = changedFiles,
                diff = diff,
            )

            // ---- ANALYZING (best-effort, §27–34, Invariant 9). ----
            progress.line()
            progress.step("Analyzing culprit with Claude...")
            val analysis = analysisProvider.analyze(
                RootCauseAnalysisRequest(
                    investigationName = definition.name,
                    regressionContext = definition.context,
                    lastGoodCommit = parentSha,
                    culpritCommit = culpritSha,
                    culpritSubject = culpritInfo.subject,
                    culpritMessage = culpritInfo.message,
                    culpritAuthor = culpritInfo.author,
                    culpritDate = culpritInfo.date,
                    changedFiles = changedFiles,
                    diff = diff,
                    goodEvidence = parentEval.evidence,
                    badEvidence = culpritEval.evidence,
                ),
            )
            progress.step(
                if (analysis.status == AnalysisStatus.SUCCESS) "Analysis complete."
                else "Analysis unavailable: ${analysis.failureReason}",
            )

            return InvestigationResult(
                status = InvestigationStatus.FOUND,
                investigation = definition.name,
                repository = config.repository.absolutePath,
                originalGoodCommit = goodSha,
                originalBadCommit = badSha,
                culprit = culprit,
                evaluations = evaluations,
                verification = verification,
                analysis = analysis,
                duration = elapsed(startNanos),
            )
        } finally {
            // Invariant 12: temporary Git state is cleaned up on success and failure alike.
            workspace.close()
        }
    }

    private fun preflight(config: RunConfig): InvestigationDefinition {
        if (!config.repository.isDirectory) {
            throw BisectAiException(
                ExitCode.INVALID_REPOSITORY,
                "Repository directory does not exist: ${config.repository}",
            )
        }
        if (!git.isGitRepository(config.repository)) {
            throw BisectAiException(
                ExitCode.INVALID_REPOSITORY,
                "Not a Git repository: ${config.repository}",
            )
        }
        if (!config.investigationFile.isFile) {
            throw BisectAiException(
                ExitCode.INVALID_INVESTIGATION,
                "Investigation file not found: ${config.investigationFile}",
            )
        }
        val definition = parser.parse(config.investigationFile.readText())
        // Manual classification needs an interactive terminal; fail fast before any worktree work.
        if (definition.classification is ClassificationSpec.Manual && !evaluator.canClassifyManually()) {
            throw BisectAiException(
                ExitCode.INVALID_ARGUMENTS,
                "This investigation uses manual classification, which requires an interactive " +
                    "terminal (a TTY). None is available (input is piped, or this is a CI/cron run).",
            )
        }
        return definition
    }

    /** Verdicts already collected in manual mode, so parent/culprit aren't re-prompted. */
    private val manualVerdicts = HashMap<String, CommitEvaluation>()

    /**
     * Evaluates [sha], optionally checking it out first. In manual mode a previously collected
     * verdict for the same commit is reused rather than re-prompting the tester.
     */
    private fun evaluateCommit(
        sha: String,
        worktree: File,
        definition: InvestigationDefinition,
        checkout: Boolean,
    ): CommitEvaluation {
        val manual = definition.classification is ClassificationSpec.Manual
        if (manual) manualVerdicts[sha]?.let { return it }
        if (checkout) git.checkoutDetached(worktree, sha)
        val eval = evaluator.evaluate(sha, worktree, definition)
        if (manual) manualVerdicts[sha] = eval
        return eval
    }

    private fun evaluateCheckedOut(
        sha: String,
        worktree: File,
        definition: InvestigationDefinition,
    ): CommitEvaluation = evaluateCommit(sha, worktree, definition, checkout = true)

    private fun verifyBoundary(
        eval: CommitEvaluation,
        expected: EvaluationStatus,
        ref: String,
        role: String,
    ) {
        when (eval.status) {
            EvaluationStatus.ERROR -> throw BisectAiException(
                ExitCode.VALIDATION_EXECUTION_FAILURE,
                "Validation could not be executed on the --$role commit $ref. Bisect was not started.",
            )
            EvaluationStatus.UNKNOWN -> throw BisectAiException(
                ExitCode.VALIDATION_EXECUTION_FAILURE,
                "The --$role commit $ref could not be reliably classified. Bisect was not started.",
            )
            expected -> Unit
            else -> throw BisectAiException(
                ExitCode.INVALID_REPOSITORY,
                "Expected --$role $ref to classify as $expected,\n" +
                    "but the investigation classified it as ${eval.status}.\n\n" +
                    "Bisect was not started.",
            )
        }
    }

    private fun inconclusive(
        config: RunConfig,
        goodSha: String,
        badSha: String,
        evaluations: List<CommitEvaluation>,
        startNanos: Long,
        message: String,
    ): InvestigationResult = InvestigationResult(
        status = InvestigationStatus.INCONCLUSIVE,
        investigation = config.investigationLabel,
        repository = config.repository.absolutePath,
        originalGoodCommit = goodSha,
        originalBadCommit = badSha,
        culprit = null,
        evaluations = evaluations,
        verification = null,
        analysis = null,
        duration = elapsed(startNanos),
        message = message,
    )

    private fun executionFailureMessage(commit: String, definition: InvestigationDefinition): String =
        "Validation command failed to execute on commit $commit.\n\n" +
            "Command:\n${definition.validation.command}\n\n" +
            "Timeout:\n${definition.validation.timeoutSeconds} seconds\n\n" +
            "Investigation aborted because:\nfailure.onExecutionFailure = abort"

    private fun elapsed(startNanos: Long): Duration =
        Duration.ofNanos(System.nanoTime() - startNanos)

    companion object {
        private const val MAX_BISECT_STEPS = 1000
    }
}
