package com.bisectai.git

import com.bisectai.core.BisectAiException
import com.bisectai.core.ExecutionResult
import com.bisectai.core.ExitCode
import com.bisectai.core.ProcessRequest
import com.bisectai.execution.ProcessRunner
import java.io.File
import java.time.Duration

/** Metadata about a single commit, gathered for reporting and analysis. */
data class CommitInfo(
    val sha: String,
    val parentSha: String,
    val subject: String,
    val message: String,
    val author: String,
    val date: String,
)

/**
 * Thin wrapper over the real `git` CLI, executed through the shared [ProcessRunner] so every
 * invocation is timeout-bounded (Invariant 5). Git — not BisectAI — owns the actual bisect
 * semantics (§22); this class exposes the primitives the orchestrator drives.
 */
class GitClient(
    private val runner: ProcessRunner,
    private val timeout: Duration = Duration.ofSeconds(120),
) {

    fun isGitRepository(dir: File): Boolean {
        if (!dir.isDirectory) return false
        val result = run(dir, "rev-parse", "--is-inside-work-tree")
        return result.exitCode == 0 && result.stdout.trim() == "true"
    }

    /** Resolves a rev to a full commit SHA, or null if it does not name a commit. */
    fun resolveCommit(dir: File, rev: String): String? {
        val result = run(dir, "rev-parse", "--verify", "--quiet", "$rev^{commit}")
        return if (result.exitCode == 0) result.stdout.trim().ifBlank { null } else null
    }

    fun commitExists(dir: File, rev: String): Boolean = resolveCommit(dir, rev) != null

    /** True if [ancestor] is an ancestor of [descendant] (a valid good..bad range). */
    fun isAncestor(dir: File, ancestor: String, descendant: String): Boolean {
        val result = run(dir, "merge-base", "--is-ancestor", ancestor, descendant)
        return when (result.exitCode) {
            0 -> true
            1 -> false
            else -> throw gitError(
                "Failed to check ancestry between $ancestor and $descendant.", result,
            )
        }
    }

    /** Number of commits in the half-open range (good, bad]. Used for progress display. */
    fun countRange(dir: File, good: String, bad: String): Int {
        val result = run(dir, "rev-list", "--count", "$good..$bad")
        if (result.exitCode != 0) return 0
        return result.stdout.trim().toIntOrNull() ?: 0
    }

    fun currentCommit(dir: File): String {
        val result = run(dir, "rev-parse", "HEAD")
        if (result.exitCode != 0) throw gitError("Failed to resolve HEAD.", result)
        return result.stdout.trim()
    }

    fun parentOf(dir: File, sha: String): String {
        val result = run(dir, "rev-parse", "--verify", "$sha^")
        if (result.exitCode != 0) throw gitError("Failed to resolve parent of $sha.", result)
        return result.stdout.trim()
    }

    fun commitInfo(dir: File, sha: String): CommitInfo {
        // Record-separator-delimited fields so multi-line messages survive parsing.
        val format = listOf("%H", "%P", "%s", "%an", "%aI", "%B").joinToString(RECORD_SEP)
        val result = run(dir, "show", "-s", "--format=$format", sha)
        if (result.exitCode != 0) throw gitError("Failed to read commit $sha.", result)
        val parts = result.stdout.split(RECORD_SEP)
        val parents = parts.getOrElse(1) { "" }.trim()
            .split(Regex("\\s+")).filter { it.isNotBlank() }
        return CommitInfo(
            sha = parts.getOrElse(0) { sha }.trim(),
            parentSha = parents.firstOrNull() ?: "",
            subject = parts.getOrElse(2) { "" }.trim(),
            author = parts.getOrElse(3) { "" }.trim(),
            date = parts.getOrElse(4) { "" }.trim(),
            message = parts.getOrElse(5) { "" }.trim(),
        )
    }

    fun changedFiles(dir: File, from: String, to: String): List<String> {
        val result = run(dir, "diff", "--name-only", from, to)
        if (result.exitCode != 0) throw gitError("Failed to list changed files.", result)
        return result.stdout.lines().map { it.trim() }.filter { it.isNotBlank() }
    }

    fun diff(dir: File, from: String, to: String): String {
        val result = run(dir, "diff", from, to)
        if (result.exitCode != 0) throw gitError("Failed to compute diff.", result)
        return result.stdout
    }

    /** Adds a detached worktree at [path] checked out at [ref]. */
    fun addWorktree(repoDir: File, path: File, ref: String) {
        val result = run(repoDir, "worktree", "add", "--detach", path.absolutePath, ref)
        if (result.exitCode != 0) throw gitError("Failed to create worktree at $path.", result)
    }

    /** Removes a worktree. Best-effort: swallows failure so cleanup never masks the real error. */
    fun removeWorktree(repoDir: File, path: File) {
        runCatching { run(repoDir, "worktree", "remove", "--force", path.absolutePath) }
        runCatching { run(repoDir, "worktree", "prune") }
    }

    /** Checks out a specific commit (detached) in the given worktree. */
    fun checkoutDetached(dir: File, ref: String) {
        val result = run(dir, "checkout", "--detach", ref)
        if (result.exitCode != 0) throw gitError("Failed to check out $ref.", result)
    }

    /** Runs a raw git subcommand; also used by [Bisector]. */
    internal fun run(dir: File, vararg args: String): ExecutionResult {
        val command = buildString {
            append("git -C ")
            append(quote(dir.absolutePath))
            for (arg in args) {
                append(' ')
                append(quote(arg))
            }
        }
        return runner.execute(ProcessRequest(command, dir, timeout))
    }

    private fun gitError(message: String, result: ExecutionResult): BisectAiException {
        val detail = result.stderr.ifBlank { result.stdout }.trim().take(500)
        return BisectAiException(
            ExitCode.INVALID_REPOSITORY,
            if (detail.isBlank()) message else "$message\n$detail",
        )
    }

    private fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    companion object {
        /** ASCII record separator; safe delimiter for `git show --format`. */
        private const val RECORD_SEP = "\u001E"
    }
}
