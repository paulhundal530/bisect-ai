package com.bisectai.it

import com.bisectai.core.ProcessRequest
import com.bisectai.execution.DefaultProcessRunner
import java.io.File
import java.time.Duration

/**
 * Builds a real, temporary Git repository containing an intentionally introduced regression
 * (§43). Not a mock: BisectAI runs the real validation command against real Git history.
 *
 * History:  A --- B --- C --- D
 *          GOOD  GOOD  BAD   BAD
 *                       ^ expected culprit
 *
 * The regression models the spec's `calculateTotal` example: for input {20, 22} the good total
 * is 42; commit C changes the implementation to add one, making the total 43. The committed
 * `total.txt` carries the computed result so the validation command is a real external process.
 */
class Fixture(val dir: File) {
    private val runner = DefaultProcessRunner()

    data class History(val a: String, val b: String, val c: String, val d: String)

    fun build(): History {
        git("init", "-q", "-b", "main")
        git("config", "user.email", "test@bisectai.local")
        git("config", "user.name", "BisectAI Test")
        git("config", "commit.gpgsign", "false")

        // A: good implementation, total = 42.
        writeCalculator(addsOne = false)
        write("total.txt", "42")
        val a = commit("A: initial calculator")

        // B: unrelated change, still good.
        write("README.md", "Calculator project")
        val b = commit("B: add readme")

        // C: regression introduced — calculateTotal adds one, total = 43.
        writeCalculator(addsOne = true)
        write("total.txt", "43")
        val c = commit("C: change calculator total behavior")

        // D: unrelated change preserving the regression, still bad.
        write("README.md", "Calculator project (v2)")
        val d = commit("D: update readme")

        return History(a, b, c, d)
    }

    private fun writeCalculator(addsOne: Boolean) {
        val body = if (addsOne) "return values.sum() + 1" else "return values.sum()"
        write(
            "src/main/kotlin/Calculator.kt",
            """
            object Calculator {
                fun calculateTotal(values: List<Int>): Int {
                    $body
                }
            }
            """.trimIndent(),
        )
    }

    private fun write(path: String, content: String) {
        File(dir, path).apply { parentFile?.mkdirs() }.writeText(content)
    }

    private fun commit(message: String): String {
        git("add", "-A")
        git("commit", "-q", "--allow-empty", "-m", message)
        return git("rev-parse", "HEAD").trim()
    }

    fun head(): String = git("rev-parse", "HEAD").trim()

    fun worktreeCount(): Int =
        git("worktree", "list").trim().lines().count { it.isNotBlank() }

    private fun git(vararg args: String): String {
        val command = "git " + args.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }
        val result = runner.execute(ProcessRequest(command, dir, Duration.ofSeconds(60)))
        check(result.exitCode == 0) {
            "git ${args.joinToString(" ")} failed: ${result.stderr.ifBlank { result.stdout }}"
        }
        return result.stdout
    }

    companion object {
        /** The validation command: a real external process that checks the computed total. */
        const val VALIDATION_COMMAND = "test \"\$(cat total.txt)\" = \"42\""

        fun investigation(): String = """
            ---
            version: 1
            name: "calculator-regression"
            validation:
              command: '$VALIDATION_COMMAND'
              attempts: 1
              timeoutSeconds: 30
            classification:
              type: "exit-code"
              goodExitCodes:
                - 0
            failure:
              onExecutionFailure: "abort"
            ---

            # Regression Description

            calculateTotal(listOf(20, 22)) should return 42, but began returning 43.
        """.trimIndent()
    }
}
