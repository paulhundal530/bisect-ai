package com.bisectai.cli

import com.bisectai.core.BisectAiException
import com.bisectai.core.EvaluationStatus
import com.bisectai.core.ExitCode
import com.bisectai.core.FileContent
import com.bisectai.core.FixProvider
import com.bisectai.core.FixRequest
import com.bisectai.core.FixResult
import com.bisectai.core.FixStatus
import com.bisectai.core.FixStrategy
import com.bisectai.core.InvestigationDefinition
import com.bisectai.evaluation.CommitEvaluator
import com.bisectai.git.GitClient
import com.bisectai.git.RunWorkspace
import com.bisectai.reporting.ResultReader
import com.bisectai.reporting.ResultSummary
import com.bisectai.spec.InvestigationParser
import java.io.File

/** Inputs for the fix command. */
data class FixConfig(
    val repository: File,
    val resultFile: File,
    val investigationFileOverride: File?,
    val strategy: FixStrategy,
    val branchName: String?,
    val maxAttempts: Int,
    val dryRun: Boolean,
)

/**
 * Produces a verified fix for a previously identified culprit. AI edits are confined to the
 * implicated files and are accepted only if the investigation's own validation now classifies the
 * patched code GOOD (or, for manual investigations, the tester confirms it). If the AI cannot
 * produce a verified fix, it falls back to a deterministic `git revert` of the culprit. A verified
 * fix is committed on a new branch off HEAD; the user's working tree is never touched.
 */
class FixRunner(
    private val git: GitClient,
    private val evaluator: CommitEvaluator,
    private val parser: InvestigationParser,
    private val fixProvider: FixProvider,
    private val resultReader: ResultReader,
    private val progress: ProgressReporter,
) {

    fun run(config: FixConfig, confirm: (diff: String) -> Boolean): FixResult {
        val summary = readSummary(config)
        if (summary.status != "FOUND") {
            throw BisectAiException(
                ExitCode.INVALID_ARGUMENTS,
                "The investigation result is '${summary.status}', not FOUND — there is no verified " +
                    "culprit to fix.",
            )
        }
        val culprit = summary.culpritSha
            ?: throw BisectAiException(ExitCode.INVALID_ARGUMENTS, "Result has no culprit SHA.")
        val parent = summary.parentSha
            ?: throw BisectAiException(ExitCode.INVALID_ARGUMENTS, "Result has no parent SHA.")

        if (!git.isGitRepository(config.repository)) {
            throw BisectAiException(ExitCode.INVALID_REPOSITORY, "Not a Git repository: ${config.repository}")
        }
        val definition = loadDefinition(config, summary)
        if (git.resolveCommit(config.repository, culprit) == null) {
            throw BisectAiException(ExitCode.INVALID_REPOSITORY, "Culprit commit not found: $culprit")
        }

        progress.banner()
        progress.line()
        progress.step("Fixing culprit $culprit in ${config.repository.absolutePath}")

        val workspace = RunWorkspace.create(git, config.repository, "HEAD")
        val worktree = workspace.worktreeDir
        try {
            var explanation: String? = null
            var strategyUsed: FixStrategy? = null

            // ---- AI strategy (default): propose edits confined to the implicated files. ----
            if (config.strategy == FixStrategy.AI) {
                progress.step("Asking Claude for a fix...")
                explanation = tryAiFix(config, summary, definition, culprit, parent, worktree)
                if (explanation != null) strategyUsed = FixStrategy.AI
                else progress.step("AI fix could not be verified; falling back to revert.")
            }

            // ---- REVERT strategy or fallback. ----
            if (strategyUsed == null) {
                git.resetWorktree(worktree)
                progress.step("Reverting the culprit commit...")
                val reverted = tryRevert(definition, culprit, worktree)
                if (reverted) {
                    strategyUsed = FixStrategy.REVERT
                    explanation = "Reverted the culprit commit $culprit."
                }
            }

            if (strategyUsed == null) {
                git.resetWorktree(worktree)
                return failure(summary, culprit, "No verified fix could be produced (AI and revert both failed).")
            }

            val diff = git.diffAgainstHead(worktree)
            val filesChanged = git.changedAgainstHead(worktree)

            if (config.dryRun) {
                return FixResult(
                    status = FixStatus.FIXED, investigation = summary.investigation,
                    repository = config.repository.absolutePath, culprit = culprit,
                    strategyUsed = strategyUsed, branch = null, filesChanged = filesChanged,
                    diff = diff, explanation = explanation, verified = true,
                    message = "--dry-run: fix verified but no commit was created.",
                )
            }

            if (!confirm(diff)) {
                return FixResult(
                    status = FixStatus.ABORTED, investigation = summary.investigation,
                    repository = config.repository.absolutePath, culprit = culprit,
                    strategyUsed = strategyUsed, branch = null, filesChanged = filesChanged,
                    diff = diff, explanation = explanation, verified = true,
                    message = "Fix was verified but you declined to create the commit.",
                )
            }

            val branch = config.branchName
                ?: "bisectai/fix-${sanitize(summary.investigation)}-${culprit.take(7)}"
            git.createBranch(worktree, branch)
            git.commitAll(worktree, commitMessage(summary.investigation, culprit, strategyUsed, explanation))
            progress.step("Committed fix on branch: $branch")

            return FixResult(
                status = FixStatus.FIXED, investigation = summary.investigation,
                repository = config.repository.absolutePath, culprit = culprit,
                strategyUsed = strategyUsed, branch = branch, filesChanged = filesChanged,
                diff = diff, explanation = explanation, verified = true,
            )
        } finally {
            workspace.close()
        }
    }

    /** Returns the explanation if a verified AI fix was produced, else null. */
    private fun tryAiFix(
        config: FixConfig,
        summary: ResultSummary,
        definition: InvestigationDefinition,
        culprit: String,
        parent: String,
        worktree: File,
    ): String? {
        val allowlist = summary.changedFiles.toSet()
        if (allowlist.isEmpty()) return null
        val culpritDiff = runCatching { git.diff(worktree, parent, culprit) }.getOrDefault("")
        var previousFailure: String? = null

        repeat(config.maxAttempts.coerceAtLeast(1)) { attempt ->
            git.resetWorktree(worktree)
            val implicated = allowlist.mapNotNull { path ->
                File(worktree, path).takeIf { it.isFile }?.let { FileContent(path, it.readText()) }
            }
            val proposal = try {
                fixProvider.propose(
                    FixRequest(
                        investigationName = summary.investigation,
                        regressionContext = definition.context,
                        culpritSha = culprit,
                        parentSha = parent,
                        culpritDiff = culpritDiff,
                        implicatedFiles = implicated,
                        analysisHint = summary.analysisHint,
                        previousFailure = previousFailure,
                    ),
                )
            } catch (e: Exception) {
                progress.step("  attempt ${attempt + 1}: fix generation failed (${e.message})")
                return null
            }

            val applied = applyEdits(worktree, allowlist, proposal.edits)
            if (applied != null) {
                previousFailure = "Edit could not be applied: $applied"
                progress.step("  attempt ${attempt + 1}: $applied")
                return@repeat
            }

            val eval = evaluator.evaluate("fix", worktree, definition)
            if (eval.status == EvaluationStatus.GOOD) {
                progress.step("  attempt ${attempt + 1}: verified GOOD")
                return proposal.explanation.ifBlank { "Applied a targeted fix to the implicated files." }
            }
            previousFailure = eval.evidence.joinToString("; ")
            progress.step("  attempt ${attempt + 1}: still ${eval.status}")
        }
        return null
    }

    /** Applies all edits; returns null on success or an error message describing the failure. */
    private fun applyEdits(worktree: File, allowlist: Set<String>, edits: List<com.bisectai.core.FixEdit>): String? {
        for (edit in edits) {
            if (edit.path !in allowlist) return "edit targets ${edit.path}, which is outside the implicated files"
            val file = File(worktree, edit.path)
            if (!file.isFile) return "file not found: ${edit.path}"
            val content = file.readText()
            val occurrences = content.split(edit.oldString).size - 1
            if (occurrences != 1) return "oldString for ${edit.path} matched $occurrences times (need exactly 1)"
            file.writeText(content.replaceFirst(edit.oldString, edit.newString))
        }
        return null
    }

    private fun tryRevert(definition: InvestigationDefinition, culprit: String, worktree: File): Boolean {
        val result = git.revertNoCommit(worktree, culprit)
        if (result.exitCode != 0) {
            git.resetWorktree(worktree)
            return false
        }
        val eval = evaluator.evaluate("fix", worktree, definition)
        if (eval.status != EvaluationStatus.GOOD) {
            git.resetWorktree(worktree)
            return false
        }
        return true
    }

    private fun readSummary(config: FixConfig): ResultSummary {
        if (!config.resultFile.isFile) {
            throw BisectAiException(
                ExitCode.INVALID_ARGUMENTS,
                "Result file not found: ${config.resultFile}",
            )
        }
        return try {
            resultReader.read(config.resultFile.readText())
        } catch (e: Exception) {
            throw BisectAiException(
                ExitCode.INVALID_ARGUMENTS,
                "Could not read the investigation result JSON: ${e.message}. " +
                    "Pass a JSON report produced by `bisectai run --output-type json`.",
            )
        }
    }

    private fun loadDefinition(config: FixConfig, summary: ResultSummary): InvestigationDefinition {
        val file = config.investigationFileOverride
            ?: File(config.repository, ".bisectai/investigations/${summary.investigation}.md")
        if (!file.isFile) {
            throw BisectAiException(
                ExitCode.INVALID_INVESTIGATION,
                "Investigation file not found: $file. It is needed to verify the fix. " +
                    "Pass --requirements to point at it explicitly.",
            )
        }
        return parser.parse(file.readText())
    }

    private fun failure(summary: ResultSummary, culprit: String, message: String) = FixResult(
        status = FixStatus.FAILED, investigation = summary.investigation,
        repository = summary.repository, culprit = culprit, strategyUsed = null, branch = null,
        filesChanged = emptyList(), diff = "", explanation = null, verified = false, message = message,
    )

    private fun commitMessage(
        investigation: String,
        culprit: String,
        strategy: FixStrategy,
        explanation: String?,
    ): String = buildString {
        appendLine("Fix $investigation regression introduced in ${culprit.take(7)}")
        appendLine()
        explanation?.takeIf { it.isNotBlank() }?.let { appendLine(it); appendLine() }
        appendLine("Culprit: $culprit")
        appendLine("Strategy: ${strategy.name.lowercase()}")
        appendLine("Verified against the investigation's validation.")
        append("Generated by BisectAI.")
    }

    private fun sanitize(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "investigation" }
}
