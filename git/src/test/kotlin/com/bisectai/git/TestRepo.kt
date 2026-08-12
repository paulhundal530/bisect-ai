package com.bisectai.git

import com.bisectai.core.ProcessRequest
import com.bisectai.execution.DefaultProcessRunner
import java.io.File
import java.time.Duration

/** Minimal helper for building a real Git repository in tests. */
class TestRepo(val dir: File) {
    private val runner = DefaultProcessRunner()

    fun init() {
        git("init", "-q", "-b", "main")
        git("config", "user.email", "test@bisectai.local")
        git("config", "user.name", "BisectAI Test")
        git("config", "commit.gpgsign", "false")
    }

    /** Writes [content] to [path] and creates a commit, returning its full SHA. */
    fun commit(path: String, content: String, message: String): String {
        File(dir, path).apply { parentFile?.mkdirs() }.writeText(content)
        git("add", "-A")
        git("commit", "-q", "--allow-empty", "-m", message)
        return rev("HEAD")
    }

    fun rev(ref: String): String = git("rev-parse", ref).trim()

    private fun git(vararg args: String): String {
        val command = "git " + args.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }
        val result = runner.execute(ProcessRequest(command, dir, Duration.ofSeconds(60)))
        check(result.exitCode == 0) {
            "git ${args.joinToString(" ")} failed: ${result.stderr.ifBlank { result.stdout }}"
        }
        return result.stdout
    }
}
