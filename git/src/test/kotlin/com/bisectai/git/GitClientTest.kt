package com.bisectai.git

import com.bisectai.execution.DefaultProcessRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class GitClientTest {

    private val git = GitClient(DefaultProcessRunner())
    private lateinit var repo: TestRepo
    private lateinit var shaA: String
    private lateinit var shaB: String

    @BeforeEach
    fun setUp(@TempDir dir: File) {
        repo = TestRepo(dir).also { it.init() }
        shaA = repo.commit("f.txt", "a", "A")
        shaB = repo.commit("f.txt", "b", "B")
    }

    @Test
    fun `recognizes a git repository`() {
        assertTrue(git.isGitRepository(repo.dir))
    }

    @Test
    fun `non-repository directory is rejected`(@TempDir other: File) {
        assertFalse(git.isGitRepository(other))
    }

    @Test
    fun `resolves valid commits`() {
        assertNotNull(git.resolveCommit(repo.dir, shaA))
        assertTrue(git.commitExists(repo.dir, "HEAD"))
    }

    @Test
    fun `invalid commit resolves to null`() {
        assertNull(git.resolveCommit(repo.dir, "deadbeefdeadbeef"))
        assertFalse(git.commitExists(repo.dir, "nope"))
    }

    @Test
    fun `ancestry is detected in both directions`() {
        assertTrue(git.isAncestor(repo.dir, shaA, shaB))
        assertFalse(git.isAncestor(repo.dir, shaB, shaA))
    }

    @Test
    fun `commit metadata is read`() {
        val info = git.commitInfo(repo.dir, shaB)
        assertEquals(shaB, info.sha)
        assertEquals(shaA, info.parentSha)
        assertEquals("B", info.subject)
    }

    @Test
    fun `changed files and diff are computed`() {
        val files = git.changedFiles(repo.dir, shaA, shaB)
        assertEquals(listOf("f.txt"), files)
        assertTrue(git.diff(repo.dir, shaA, shaB).contains("f.txt"))
    }

    @Test
    fun `worktree is created and cleaned up without touching the main tree`() {
        val workspace = RunWorkspace.create(git, repo.dir, shaB)
        val worktree = workspace.worktreeDir
        assertTrue(worktree.isDirectory)
        assertTrue(File(worktree, "f.txt").exists())

        workspace.close()

        assertFalse(worktree.exists())
        // The user's main working tree is intact and not left in a worktree/bisect state.
        assertTrue(File(repo.dir, "f.txt").exists())
        assertEquals(shaB, git.currentCommit(repo.dir))
    }
}
