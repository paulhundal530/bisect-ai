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

    /**
     * Manual (human-verified) template for regressions that can only be judged by using the app —
     * a UI journey with no scriptable test (§13 manual mode).
     */
    fun renderManual(name: String): String = """
        |---
        |version: 1
        |
        |name: "$name"
        |
        |validation:
        |  # OPTIONAL setup step, run before each prompt: build/install/launch the candidate.
        |  # It runs inside BisectAI's isolated worktree, so the app matches the commit under test.
        |  # Remove this line if you will prepare the app yourself.
        |  command: "./gradlew :app:installDebug"
        |  timeoutSeconds: 1200
        |
        |classification:
        |  type: "manual"
        |  instructions: "Describe the exact journey to run and what GOOD looks like."
        |
        |failure:
        |  onExecutionFailure: "abort"
        |---
        |
        |# Regression Description
        |
        |Describe the user journey to test, the expected (good) behavior, and the observed (bad)
        |behavior. This context, plus the note you type at each verdict, is supplied to Claude for
        |root-cause analysis.
        |""".trimMargin() + "\n"
}
