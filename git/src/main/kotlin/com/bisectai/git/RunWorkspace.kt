package com.bisectai.git

import java.io.File
import java.nio.file.Files

/**
 * An isolated, temporary workspace for one investigation run (Worktree Safety, §23).
 *
 * The worktree lives in an OS temp directory *outside* the user's repository, so the user's
 * active working tree, branch, index, and tracked `.gitignore` are never touched (Invariant 6),
 * and no runtime state needs to be excluded from source control.
 *
 * [close] resets bisect state, removes the worktree, and deletes the temp directory. It is
 * idempotent and safe to call from a `finally` block; a JVM shutdown hook is a backstop for
 * abrupt termination (Invariant 12).
 */
class RunWorkspace private constructor(
    private val git: GitClient,
    private val repoDir: File,
    val rootDir: File,
    val worktreeDir: File,
) : AutoCloseable {

    private val logsDir: File = File(rootDir, "logs").apply { mkdirs() }
    private var closed = false
    private val shutdownHook = Thread { cleanupQuietly() }

    init {
        Runtime.getRuntime().addShutdownHook(shutdownHook)
    }

    fun bisector(): Bisector = Bisector(git, worktreeDir)

    override fun close() {
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
        cleanupQuietly()
    }

    private fun cleanupQuietly() {
        if (closed) return
        closed = true
        runCatching { Bisector(git, worktreeDir).reset() }
        runCatching { git.removeWorktree(repoDir, worktreeDir) }
        runCatching { rootDir.deleteRecursively() }
    }

    companion object {
        /** Creates a temp workspace with a detached worktree checked out at [checkoutRef]. */
        fun create(git: GitClient, repoDir: File, checkoutRef: String): RunWorkspace {
            val root = Files.createTempDirectory("bisectai-run-").toFile()
            val worktree = File(root, "worktree")
            try {
                git.addWorktree(repoDir, worktree, checkoutRef)
            } catch (e: Exception) {
                root.deleteRecursively()
                throw e
            }
            return RunWorkspace(git, repoDir, root, worktree)
        }
    }
}
