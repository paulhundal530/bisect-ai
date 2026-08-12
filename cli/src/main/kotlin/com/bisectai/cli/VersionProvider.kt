package com.bisectai.cli

import picocli.CommandLine

/** Supplies `--version` output from the build-generated version resource. */
class VersionProvider : CommandLine.IVersionProvider {
    override fun getVersion(): Array<String> {
        val version = VersionProvider::class.java.getResourceAsStream("/bisectai-version.txt")
            ?.bufferedReader()?.use { it.readText().trim() }
            ?: "unknown"
        return arrayOf("bisectai $version")
    }
}
