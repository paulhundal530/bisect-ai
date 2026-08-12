package com.bisectai.cli

import com.bisectai.core.ExitCode
import com.bisectai.execution.DefaultProcessRunner
import com.bisectai.git.GitClient
import com.bisectai.spec.InvestigationTemplate
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.io.File
import java.util.concurrent.Callable

@Command(
    name = "init",
    mixinStandardHelpOptions = true,
    description = ["Create a new investigation definition under .bisectai/investigations/."],
)
class InitCommand : Callable<Int> {

    @Parameters(index = "0", paramLabel = "<investigation-name>", description = ["Investigation name (e.g. build-performance)."])
    lateinit var name: String

    @Option(names = ["--repo"], description = ["Repository directory (default: current directory)."])
    var repo: File = File(System.getProperty("user.dir"))

    override fun call(): Int {
        val git = GitClient(DefaultProcessRunner())
        if (!git.isGitRepository(repo)) {
            System.err.println("Not a Git repository: ${repo.absolutePath}")
            return ExitCode.INVALID_REPOSITORY.code
        }

        val investigationsDir = File(repo, ".bisectai/investigations")
        val target = File(investigationsDir, "$name.md")

        if (target.exists()) {
            // Never silently overwrite an existing definition (§9).
            System.err.println(
                "Investigation already exists:\n\n${target.relativeToRepo(repo)}\n\n" +
                    "Refusing to overwrite. Choose a different name or edit the existing file.",
            )
            return ExitCode.GENERAL_FAILURE.code
        }

        if (!investigationsDir.exists() && !investigationsDir.mkdirs()) {
            System.err.println("Could not create directory: ${investigationsDir.absolutePath}")
            return ExitCode.GENERAL_FAILURE.code
        }

        target.writeText(InvestigationTemplate.render(name))
        println("Created:\n\n${target.relativeToRepo(repo)}")
        return ExitCode.COMPLETED.code
    }

    private fun File.relativeToRepo(repo: File): String =
        runCatching { this.relativeTo(repo).path }.getOrDefault(this.path)
}
