package com.bisectai.cli

import picocli.CommandLine.Command
import java.util.concurrent.Callable

@Command(
    name = "bisectai",
    mixinStandardHelpOptions = true,
    versionProvider = VersionProvider::class,
    description = [
        "BisectAI automates Git regression investigation.",
        "Git and deterministic validation locate WHERE a regression was introduced;",
        "Claude explains WHY the identified commit likely caused it.",
    ],
    subcommands = [InitCommand::class, RunCommand::class, FixCommand::class],
)
class RootCommand : Callable<Int> {
    /** With no subcommand, print usage and signal invalid arguments. */
    override fun call(): Int {
        System.err.println("No subcommand given. Try 'bisectai --help'.")
        return com.bisectai.core.ExitCode.INVALID_ARGUMENTS.code
    }
}
