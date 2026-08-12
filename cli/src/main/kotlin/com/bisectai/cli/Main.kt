package com.bisectai.cli

import picocli.CommandLine
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val exitCode = CommandLine(RootCommand())
        .setCaseInsensitiveEnumValuesAllowed(true)
        .execute(*args)
    exitProcess(exitCode)
}
