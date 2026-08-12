package com.bisectai.spec

/** Generates the starter investigation definition written by `bisectai init` (§9/§10). */
object InvestigationTemplate {

    /** @param name human-readable investigation name embedded in the template. */
    fun render(name: String): String = """
        |---
        |version: 1
        |
        |name: "$name"
        |
        |validation:
        |  command: "./gradlew test"
        |  attempts: 1
        |  warmupAttempts: 0
        |  timeoutSeconds: 300
        |
        |classification:
        |  type: "exit-code"
        |  goodExitCodes:
        |    - 0
        |
        |failure:
        |  onExecutionFailure: "abort"
        |---
        |
        |# Regression Description
        |
        |Describe the regression here. This Markdown body is supplied to Claude as context
        |during post-bisect root-cause analysis, so include:
        |
        |- What the expected (good) behavior is.
        |- What the observed (bad) behavior is.
        |- Any relevant area of the codebase.
        |
        |The YAML front matter above is authoritative for deterministic execution; this body is
        |for human and AI context only.
        |""".trimMargin() + "\n"
}
