package com.bisectai.git

import com.bisectai.core.EvaluationStatus
import com.bisectai.execution.DefaultProcessRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class BisectorTest {

    private val git = GitClient(DefaultProcessRunner())

    @Test
    fun `git bisect identifies the first bad commit`(@TempDir dir: File) {
        val repo = TestRepo(dir).also { it.init() }
        val a = repo.commit("value.txt", "1", "A")
        repo.commit("value.txt", "1", "B")
        val c = repo.commit("value.txt", "2", "C") // regression introduced here
        val d = repo.commit("value.txt", "2", "D")

        val workspace = RunWorkspace.create(git, repo.dir, d)
        try {
            val bisector = workspace.bisector()
            // A commit is GOOD while value == 1.
            fun classify(): EvaluationStatus {
                val value = File(workspace.worktreeDir, "value.txt").readText().trim()
                return if (value == "1") EvaluationStatus.GOOD else EvaluationStatus.BAD
            }

            var step = bisector.start(good = a, bad = d)
            var culprit: String? = null
            var guard = 0
            while (guard++ < 20) {
                when (step) {
                    is BisectStep.Evaluate -> step = bisector.mark(classify())
                    is BisectStep.FirstBad -> {
                        culprit = step.commit
                        break
                    }
                    is BisectStep.Inconclusive ->
                        throw AssertionError("unexpected inconclusive: ${step.reason}")
                }
            }
            assertEquals(c, culprit)
        } finally {
            workspace.close()
        }
        assertTrue(!workspace.worktreeDir.exists())
    }
}
