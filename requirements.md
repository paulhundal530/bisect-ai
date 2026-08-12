# **BisectAI V1**

## **1. Project Overview**

BisectAI is a Kotlin/JVM command-line tool that automates Git regression investigation.

The user provides:

-   A Git repository.
-   A known-good commit.
-   A known-bad commit.
-   A reusable investigation definition describing how to determine whether a checked-out commit is GOOD or BAD.

BisectAI deterministically evaluates commits between the known-good and known-bad boundaries using Git bisect semantics.

Once the first bad commit has been identified, BisectAI uses Claude to analyze the transition between the last known-good commit and first bad commit and generate a root-cause analysis.

The fundamental architectural rule is:

Git and deterministic validation determine WHERE the regression was introduced. AI explains WHY the identified commit likely caused the regression.

AI MUST NOT determine which commit is GOOD or BAD in V1.

----------

# **2. Goals**

BisectAI V1 must:

1.  Be implemented in Kotlin/JVM.
2.  Use Picocli for the CLI.
3.  Be distributable as a runnable JAR.
4.  Be suitable for publication as a GitHub release artifact.
5.  Support reusable, repository-local investigation definitions.
6.  Deterministically validate commits.
7.  Use Git bisect semantics to locate the first bad commit.
8.  Support GOOD, BAD, and SKIP/UNKNOWN commit evaluations.
9.  Protect users from indefinitely running commands through timeouts.
10.  Provide visible progress during execution.
11.  Verify the supplied good and bad boundaries before beginning the bisect.
12.  Use Claude only after a culprit commit has been identified.
13.  Produce either machine-readable JSON or a human-readable Markdown report.
14.  Continue producing a valid bisect result even if Claude analysis fails.
15.  Include an end-to-end sample repository/test fixture proving that the system can identify an intentionally introduced regression.

----------

# **3. Non-Goals for V1**

Do NOT implement the following unless required to support the core design:

-   AI-driven GOOD/BAD classification.
-   AI-driven execution of validation steps.
-   Autonomous repository exploration by Claude.
-   Automatic discovery of the known-good commit.
-   GUI.
-   IDE plugin.
-   Web service.
-   Remote repository cloning.
-   GitHub API integration.
-   Pull request creation.
-   Automatic code modification.
-   Automatic regression fixes.
-   Docker/container execution.
-   Multiple AI providers.
-   Native binaries.
-   Homebrew distribution.
-   Parallel bisect execution.
-   Persistent run history UI.
-   Complex workflow scripting language.

Design interfaces so some of these capabilities could be introduced later without rewriting the core bisect engine.

----------

# **4. Terminology**

## **Investigation**

A reusable definition describing how a regression can be reproduced and classified.

Example:

`build-performance`

## **Investigation Definition**

A Markdown file with YAML front matter containing deterministic execution instructions.

Example:

`.bisectai/investigations/build-performance.md`

## **Evaluation**

Execution of an investigation against one specific Git commit.

An evaluation produces one of:

-   GOOD
-   BAD
-   UNKNOWN/SKIP
-   ERROR

## **Culprit**

The first bad commit identified by the deterministic bisect.

## **Parent / Last Good Commit**

The commit immediately preceding the culprit along the bisected history and forming the verified GOOD -> BAD transition.

## **Analysis**

The post-bisect Claude operation that attempts to explain why the culprit introduced the regression.

----------

# **5. CLI Name**

The CLI executable/project name is:

`bisectai`

Examples:

```bash
bisectai init build-performance
```

```bash
bisectai run \
  --investigation build-performance \
  --good abc123 \
  --bad def456
```

----------

# **6. Technology Requirements**

Use:

-   Kotlin/JVM
-   Gradle Kotlin DSL
-   Picocli
-   JUnit 5
-   Jackson for JSON serialization
-   Jackson YAML for YAML parsing
-   Anthropic’s official Java SDK
-   A Gradle fat-JAR solution such as Shadow for runnable distribution

Prefer modern stable versions compatible with the selected JVM target.

Do not hardcode dependency versions from this requirements document if newer stable compatible versions exist at implementation time.

The codebase should target a modern LTS JVM appropriate for a new Kotlin CLI application.

----------

# **7. Repository Structure**

Use clear module/package boundaries.

A reasonable logical structure is:

```text
bisectai/
├── cli/
├── spec/
├── git/
├── execution/
├── evaluation/
├── analysis/
├── reporting/
└── integration-tests/
```

These may be Gradle modules or packages. Prefer the simplest structure that maintains the architectural boundaries.

Responsibilities:

```text
cli
    CLI argument parsing and user interaction

spec
    Investigation definition parsing and validation

git
    Git operations, worktrees, commits and bisect

execution
    External process execution and timeouts

evaluation
    GOOD/BAD/UNKNOWN classification

analysis
    Claude integration and root-cause analysis

reporting
    Canonical result model and output rendering

integration-tests
    End-to-end regression scenarios
```

----------

# **8. Investigation Storage**

Investigations are first-class reusable resources belonging to a repository.

Store them under:

```text
<repository>/
└── .bisectai/
    └── investigations/
```

Example:

```text
my-project/
├── src/
├── build.gradle.kts
└── .bisectai/
    └── investigations/
        ├── build-performance.md
        ├── login-crash.md
        └── calculation-regression.md
```

Investigation definitions SHOULD be safe to commit to source control.

They MUST NOT contain:

-   API keys
-   Authentication tokens
-   Secrets
-   Temporary execution state
-   Generated logs

----------

# **9.**

**`init`** **Command**

Syntax:

```bash
bisectai init <investigation-name>
```

Example:

```bash
bisectai init build-performance
```

By default, the repository is the current working directory.

The command creates:

```text
.bisectai/investigations/build-performance.md
```

Support:

```bash
bisectai init build-performance --repo /path/to/repository
```

The tool must:

1.  Verify the target is a Git repository.
2.  Create `.bisectai/investigations/` if necessary.
3.  Generate a valid investigation template.
4.  Refuse to overwrite an existing investigation.
5.  Return a clear error if the investigation already exists.

Do NOT silently overwrite existing definitions.

----------

# **10. Investigation Definition Format**

Investigation files use:

-   YAML front matter for machine-readable configuration.
-   Markdown body for human-readable regression context.

The YAML front matter is authoritative for deterministic execution.

The Markdown body provides contextual information and SHOULD be supplied to Claude during post-bisect analysis.

Example:

```md
---
version: 1

name: "Calculation regression"

validation:
  command: "./gradlew test"
  attempts: 1
  warmupAttempts: 0
  timeoutSeconds: 300

classification:
  type: "exit-code"
  goodExitCodes:
    - 0

failure:
  onExecutionFailure: "abort"
---

# Regression Description

The calculator test suite began failing.

The expected behavior is that all calculator tests pass.
```

----------

# **11. Investigation In-Memory Model**

The Markdown file MUST be parsed exactly once into a typed normalized Kotlin representation before execution begins.

The rest of the application MUST NOT operate directly on YAML or Markdown.

Conceptually:

```kotlin
data class InvestigationDefinition(
    val version: Int,
    val name: String,
    val validation: ValidationSpec,
    val classification: ClassificationSpec,
    val failurePolicy: FailurePolicy,
    val context: String
)
```

`context` contains the Markdown body.

----------

# **12. Validation Specification**

Conceptually:

```kotlin
data class ValidationSpec(
    val command: String,
    val attempts: Int = 1,
    val warmupAttempts: Int = 0,
    val timeoutSeconds: Long = 300
)
```

Validation rules:

-   `command` is required.
-   `attempts` must be >= 1.
-   `warmupAttempts` must be >= 0.
-   `timeoutSeconds` must be > 0.
-   Warmup attempts do not contribute to classification.
-   Every command invocation must be bounded by the configured timeout.

----------

# **13. Classification Model**

Classification should use a sealed interface or equivalent typed model.

Conceptually:

```kotlin
sealed interface ClassificationSpec
```

V1 MUST support exit-code classification.

Example:

```yaml
classification:
  type: "exit-code"
  goodExitCodes:
    - 0
```

Meaning:

```text
exit code 0     -> GOOD
other exit code -> BAD
```

The design SHOULD allow additional deterministic classification strategies later, such as:

-   Regex output matching.
-   Numeric metric thresholds.
-   Test-result parsing.

Do not require those additional strategies for the initial implementation unless trivial.

----------

# **14. Evaluation Result**

A commit evaluation must produce a structured result.

Conceptually:

```kotlin
enum class EvaluationStatus {
    GOOD,
    BAD,
    UNKNOWN,
    ERROR
}
```

And:

```kotlin
data class CommitEvaluation(
    val commit: String,
    val status: EvaluationStatus,
    val attempts: List<ExecutionResult>,
    val duration: Duration,
    val evidence: List<String>
)
```

Semantics:

### **GOOD**

Validation successfully executed and the classification rule determined that the regression is absent.

### **BAD**

Validation successfully executed and the classification rule determined that the regression is present.

### **UNKNOWN**

The commit could not reliably be classified.

This maps conceptually to:

```bash
git bisect skip
```

### **ERROR**

BisectAI itself could not perform the evaluation.

This normally aborts the investigation.

----------

# **15. Failure Policy**

Investigation definitions should support:

```yaml
failure:
  onExecutionFailure: "abort"
```

V1 supported values:

```text
abort
skip
```

Default:

```text
abort
```

`abort` means the investigation stops immediately.

`skip` means the commit is marked UNKNOWN/SKIP and the bisect continues if Git can still determine the boundary.

----------

# **16. Process Execution**

All external processes MUST be executed through a common abstraction.

Example:

```kotlin
interface ProcessRunner {
    fun execute(request: ProcessRequest): ExecutionResult
}
```

`ExecutionResult` should include at minimum:

```kotlin
data class ExecutionResult(
    val command: String,
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val duration: Duration,
    val timedOut: Boolean
)
```

No external process may wait indefinitely.

If the timeout is exceeded:

1.  Attempt graceful termination.
2.  Force termination if necessary.
3.  Capture available stdout/stderr.
4.  Return a timed-out execution result.
5.  Apply the configured failure policy.

----------

# **17. Repository Selection**

For `run`, `--repo` is optional.

If omitted:

```text
repository = current working directory
```

Example common workflow:

```bash
cd ~/projects/my-app

bisectai run \
  --investigation build-performance \
  --good abc123 \
  --bad def456
```

Explicit repository:

```bash
bisectai run \
  --repo ~/projects/my-app \
  --investigation build-performance \
  --good abc123 \
  --bad def456
```

----------

# **18. Run Command**

Primary syntax:

```bash
bisectai run \
  --investigation <name> \
  --good <commit> \
  --bad <commit>
```

Full example:

```bash
bisectai run \
  --investigation build-performance \
  --good abc123 \
  --bad def456 \
  --output-type report \
  --output ./build-performance-result.md
```

Required:

```text
--investigation OR --requirements
--good
--bad
```

Optional:

```text
--repo
--output-type
--output
```

----------

# **19. Explicit Requirements File**

Support an escape hatch for investigation definitions located outside the repository-managed investigation directory.

Example:

```bash
bisectai run \
  --requirements ~/investigations/custom.md \
  --good abc123 \
  --bad def456
```

`--investigation` and `--requirements` MUST be mutually exclusive.

`--investigation foo` resolves to:

```text
<repo>/.bisectai/investigations/foo.md
```

----------

# **20. Preflight Validation**

Before beginning any bisect, fail as early as possible.

The following should be validated before running the expensive workflow:

1.  CLI arguments are valid.
2.  Repository exists.
3.  Repository is a Git repository.
4.  Investigation file exists.
5.  Investigation YAML parses.
6.  Investigation version is supported.
7.  Required investigation fields exist.
8.  Validation command is non-empty.
9.  Timeout values are valid.
10.  Good commit exists.
11.  Bad commit exists.
12.  Good commit is an ancestor of bad commit or otherwise represents a valid bisect range.
13.  Output location is writable where reasonably determinable.

Errors during these checks should generally be returned within milliseconds or seconds rather than after starting the bisect.

----------

# **21. Boundary Verification**

Before beginning binary search, BisectAI MUST verify the user’s assumptions.

Given:

```text
--good A
--bad Z
```

evaluate:

```text
A
Z
```

Expected:

```text
A -> GOOD
Z -> BAD
```

If A evaluates BAD:

Abort with a clear error:

```text
Expected --good abc123 to classify as GOOD,
but the investigation classified it as BAD.

Bisect was not started.
```

If Z evaluates GOOD:

Abort with:

```text
Expected --bad def456 to classify as BAD,
but the investigation classified it as GOOD.

Bisect was not started.
```

This verification is mandatory.

----------

# **22. Git Bisect**

The Git subsystem owns identification of the culprit.

Prefer using Git’s actual bisect semantics rather than reimplementing binary search manually.

Conceptually:

```bash
git bisect start
git bisect good <good>
git bisect bad <bad>
```

For each candidate:

```text
evaluate candidate
```

Then:

```text
GOOD    -> git bisect good
BAD     -> git bisect bad
UNKNOWN -> git bisect skip
```

Continue until Git identifies the first bad commit or reports that the result is ambiguous/inconclusive.

Always clean up/reset bisect state.

Cleanup MUST occur even when:

-   Evaluation fails.
-   The process is interrupted.
-   Analysis fails.
-   Output generation fails.

----------

# **23. Worktree Safety**

Do NOT mutate the user’s active working tree while evaluating commits.

Use isolated Git worktrees or another safe isolated Git mechanism.

The user’s current branch, uncommitted changes, staged changes, and active checkout MUST remain untouched.

Runtime worktrees may live under a temporary/run directory.

Example conceptual structure:

```text
.bisectai/
├── investigations/
└── runs/
    └── <run-id>/
        ├── worktree/
        ├── logs/
        └── state.json
```

Runtime state should be considered temporary and SHOULD be excluded from source control.

If necessary, `init` may create/update an appropriate `.gitignore` entry, but avoid unexpectedly rewriting user files.

Prefer runtime storage that does not require modifying the repository’s tracked `.gitignore` if possible.

----------

# **24. Runtime Progress**

The CLI MUST NOT remain silent during long-running operations.

Example:

```text
BisectAI

Validating investigation... OK
Validating repository... OK

Verifying boundaries...

[1/2] abc123  GOOD  (12.4s)
[2/2] def456  BAD   (13.1s)

Starting bisect across 47 commits...

[1] 8a71fe2  GOOD  (11.8s)
    Remaining range: approximately 23 commits

[2] c37219a  BAD   (12.2s)
    Remaining range: approximately 11 commits
```

While a command is executing, the user should have enough information to know what BisectAI is doing.

At minimum display:

-   Commit being evaluated.
-   Validation command.
-   Completion result.
-   Duration.
-   Timeout when one occurs.

Avoid excessive logging by default.

----------

# **25. Execution State Machine**

Model the high-level workflow explicitly.

States:

```text
INITIALIZING
PREFLIGHT
VERIFYING_BOUNDARIES
BISECTING
VERIFYING_CULPRIT
ANALYZING
WRITING_OUTPUT
COMPLETED
FAILED
```

This does not necessarily need to be implemented literally as an enum-driven state machine if that adds unnecessary complexity, but system behavior should conform to these stages.

----------

# **26. Culprit Verification**

Once Git identifies the first bad commit, verify the final GOOD -> BAD boundary before analysis.

At minimum ensure:

```text
parent/previous boundary commit -> GOOD
culprit                         -> BAD
```

If the transition cannot be verified, return an inconclusive result rather than pretending confidence.

The deterministic result is more important than producing an AI explanation.

----------

# **27. AI Boundary**

AI MUST NOT participate in:

-   Git bisect selection.
-   GOOD/BAD classification.
-   Boundary verification.
-   Requirements parsing.
-   Command execution decisions.

AI begins only AFTER BisectAI has established:

```text
GOOD COMMIT -> BAD COMMIT
                ^
              culprit
```

----------

# **28. Claude Analysis Input**

Once the culprit is identified, construct a bounded analysis request.

At minimum provide Claude:

1.  Investigation name.
2.  Markdown regression description/context.
3.  Last known-good commit SHA.
4.  Culprit commit SHA.
5.  Culprit commit metadata.
6.  Commit message.
7.  Diff between GOOD and culprit.
8.  Changed file list.
9.  Validation evidence from GOOD.
10.  Validation evidence from BAD.
11.  Relevant stdout/stderr.

Conceptually:

```text
Investigation:
Calculator regression

Description:
All calculator tests should pass.

Last good:
abc123

First bad:
def456

GOOD validation:
./gradlew test
exit code 0

BAD validation:
./gradlew test
exit code 1

Failure:
Expected: 42
Actual: 43

Diff:
...
```

Claude’s responsibility is:

Analyze the verified GOOD -> BAD transition and explain the most likely root cause introduced by the culprit commit.

----------

# **29. Claude Repository Access**

For V1, Claude SHOULD NOT independently control Git or check out commits.

BisectAI should gather the necessary context and supply it to Claude.

Claude should not:

-   Change branches.
-   Modify files.
-   Execute arbitrary commands.
-   Continue the bisect.
-   Decide another commit is the actual culprit.

Future versions may provide Claude with bounded repository exploration tools, but this is outside V1.

----------

# **30. Analysis Provider Abstraction**

Do not couple the rest of the application directly to Anthropic.

Define an abstraction similar to:

```kotlin
interface AnalysisProvider {
    fun analyze(
        request: RootCauseAnalysisRequest
    ): RootCauseAnalysis
}
```

Implement:

```kotlin
class ClaudeAnalysisProvider : AnalysisProvider
```

This allows future providers without modifying Git or evaluation logic.

Only Claude needs to be implemented in V1.

----------

# **31. Claude Authentication**

Use Anthropic’s official Java SDK.

Support its standard environment-based authentication mechanisms.

Expected environment variables include:

```text
ANTHROPIC_API_KEY
ANTHROPIC_AUTH_TOKEN
```

Do NOT require users to place secrets inside investigation files.

Do NOT encourage:

```bash
bisectai run --api-key secret
```

Secrets should not be passed directly through CLI arguments because they can appear in shell history or process listings.

Authentication configuration belongs outside source-controlled investigation definitions.

----------

# **32. Claude Client Lifetime**

Create and reuse a single Anthropic client per BisectAI process.

Do not construct a new client for every request.

The analysis provider owns the client lifecycle.

----------

# **33. Structured AI Output**

Claude MUST be asked for structured output rather than unconstrained prose.

Conceptual model:

```kotlin
data class RootCauseAnalysis(
    val summary: String,
    val likelyCause: String,
    val relevantFiles: List<String>,
    val supportingEvidence: List<String>,
    val suggestedFix: String?,
    val confidence: Double
)
```

Validate Claude’s response before accepting it.

Confidence should be between:

```text
0.0 and 1.0
```

The report should clearly identify AI-generated conclusions as analysis rather than deterministic facts.

----------

# **34. AI Failure Semantics**

AI analysis is best-effort.

If bisect succeeds but Claude fails:

```text
bisect = SUCCESS
analysis = FAILED
```

The overall culprit result remains valid.

Examples of analysis failures:

-   Missing API credentials.
-   401 authentication error.
-   Rate limiting.
-   Network failure.
-   Invalid structured response.
-   Claude service failure.
-   Analysis timeout.

BisectAI MUST still generate the requested output.

Example:

```text
First bad commit:
c37219a

AI Analysis:
Unavailable

Reason:
Claude authentication failed.

The deterministic bisect result completed successfully.
```

Do not discard a successful bisect because AI failed.

----------

# **35. Canonical Investigation Result**

All output formats must render from one canonical result model.

Conceptually:

```kotlin
data class InvestigationResult(
    val status: InvestigationStatus,
    val investigation: String,
    val repository: String,
    val originalGoodCommit: String,
    val originalBadCommit: String,
    val culprit: CulpritCommit?,
    val evaluations: List<CommitEvaluation>,
    val verification: VerificationResult?,
    val analysis: AnalysisResult?,
    val duration: Duration
)
```

Do not build separate business models for JSON and Markdown output.

----------

# **36. Output Types**

Support:

```text
json
report
```

CLI:

```bash
--output-type json
```

or:

```bash
--output-type report
```

Model:

```kotlin
enum class OutputType {
    JSON,
    REPORT
}
```

`report` means Markdown.

----------

# **37. Output Destination**

Support:

```bash
--output <path>
```

Example:

```bash
bisectai run \
  --investigation calculator \
  --good abc123 \
  --bad def456 \
  --output-type report \
  --output ./calculator-regression.md
```

If `--output` is omitted, render the final result to stdout.

This enables:

```bash
bisectai run ... --output-type json
```

and Unix composition such as:

```bash
bisectai run ... --output-type json | jq .
```

Progress messages MUST NOT corrupt JSON stdout.

If JSON is written to stdout, send progress/status information to stderr.

----------

# **38. JSON Output**

Example shape:

```json
{
  "status": "FOUND",
  "investigation": "calculator-regression",
  "range": {
    "good": "abc123",
    "bad": "def456"
  },
  "culprit": {
    "sha": "c37219a",
    "parentSha": "a28120c",
    "subject": "Change calculator total behavior"
  },
  "verification": {
    "parent": "GOOD",
    "culprit": "BAD"
  },
  "analysis": {
    "status": "SUCCESS",
    "summary": "The calculation behavior was changed.",
    "likelyCause": "calculateTotal adds one to the sum.",
    "relevantFiles": [
      "src/main/kotlin/Calculator.kt"
    ],
    "supportingEvidence": [
      "The parent passes the calculator test.",
      "The culprit fails with expected 42 but actual 43."
    ],
    "suggestedFix": "Restore the previous summation behavior.",
    "confidence": 0.97
  }
}
```

----------

# **39. Markdown Report**

Human-readable reports should contain at minimum:

```text
# BisectAI Regression Investigation

## Investigation

## Result

## Regression Boundary

## Culprit Commit

## Validation Evidence

## AI Root-Cause Analysis

## Supporting Evidence

## Suggested Remediation

## Execution Summary
```

Example core presentation:

```text
Last Good Commit
abc123

First Bad Commit
c37219a

Transition
abc123 -> GOOD
c37219a -> BAD
```

Clearly distinguish deterministic evidence from AI-generated interpretation.

----------

# **40. Error Handling**

Errors must be actionable.

Avoid:

```text
Something went wrong.
```

Prefer:

```text
Validation command timed out.

Commit:
bb172ca

Command:
./gradlew test

Timeout:
300 seconds

Investigation aborted because:
failure.onExecutionFailure = abort
```

Include captured stderr where useful.

Do not dump enormous logs to the terminal by default.

Persist or include enough information to diagnose failures.

----------

# **41. Exit Codes**

Use stable CLI exit codes.

Recommended:

```text
0   Investigation completed
1   General failure
2   Invalid CLI arguments
3   Invalid investigation definition
4   Invalid Git repository/range
5   Validation execution failure
6   Bisect inconclusive
7   Output generation failure
```

An AI analysis failure SHOULD NOT produce a non-zero exit code when the deterministic bisect completed successfully.

The JSON/report should represent the analysis failure separately.

----------

# **42. Cleanup**

BisectAI MUST clean up temporary Git state.

Use `try/finally` or equivalent protection.

Cleanup includes:

-   Resetting bisect state.
-   Removing temporary worktrees.
-   Terminating spawned processes.
-   Closing resources.

A failed investigation must not leave the user’s repository in an unexpected checkout or bisect state.

----------

# **43. Integration Test Sandbox**

The project MUST contain an end-to-end test scenario proving BisectAI works against real Git history.

Do not merely mock Git for this test.

Create a temporary Git repository during the test.

Conceptual history:

```text
A ---- B ---- C ---- D
GOOD   GOOD   BAD    BAD
              ^
        expected culprit
```

The fixture may contain a very small Gradle/Kotlin project.

Example initial implementation:

```kotlin
fun calculateTotal(values: List<Int>): Int {
    return values.sum()
}
```

Test:

```kotlin
assertEquals(
    42,
    calculateTotal(listOf(20, 22))
)
```

Commits A and B should pass.

Commit C introduces:

```kotlin
fun calculateTotal(values: List<Int>): Int {
    return values.sum() + 1
}
```

Commits C and D should fail.

----------

# **44. End-to-End Acceptance Test**

The integration test should:

1.  Create a temporary Git repository.
2.  Create commit A.
3.  Create commit B.
4.  Introduce regression in commit C.
5.  Create commit D while preserving the regression.
6.  Generate/write an investigation definition.
7.  Run BisectAI using:
    -   good = A
    -   bad = D
8.  Allow BisectAI to execute the real validation command.
9.  Assert the culprit is C.
10.  Assert the parent/boundary commit is GOOD.
11.  Assert C is BAD.
12.  Assert output can be rendered.

This test exercises:

-   Git interaction.
-   Worktree handling.
-   Investigation parsing.
-   Process execution.
-   Timeout infrastructure.
-   Classification.
-   Bisect behavior.
-   Boundary verification.
-   Result construction.
-   Reporting.

----------

# **45. AI Testing**

Normal tests MUST NOT require a real Anthropic API key.

Provide a fake implementation:

```kotlin
class FakeAnalysisProvider : AnalysisProvider
```

The deterministic end-to-end bisect test should use the fake provider.

Optionally provide a separately invoked live integration test that runs only when Anthropic credentials are available.

For example:

```text
./gradlew integrationTest
```

must not call Claude.

A separate task may be provided:

```text
./gradlew claudeIntegrationTest
```

The live test should automatically skip when credentials are unavailable.

----------

# **46. Unit Testing Requirements**

Provide focused unit tests for at least:

### **Investigation parsing**

-   Valid definition.
-   Missing required field.
-   Unsupported version.
-   Invalid timeout.
-   Invalid attempts.
-   Invalid classification type.

### **Process execution**

-   Successful process.
-   Non-zero exit.
-   Timeout.
-   stdout capture.
-   stderr capture.

### **Classification**

-   Good exit code.
-   Bad exit code.

### **Failure policy**

-   Abort.
-   Skip.

### **Reporting**

-   JSON serialization.
-   Markdown rendering.
-   Analysis success.
-   Analysis failure.

### **Git**

-   Valid commits.
-   Invalid commit.
-   Valid ancestry.
-   Worktree cleanup.

----------

# **47. Distribution**

Produce a runnable fat JAR.

Expected usage:

```bash
java -jar bisectai-<version>.jar --help
```

The JAR should include the required runtime dependencies.

The Gradle build should expose a task that builds the distributable artifact.

Example:

```bash
./gradlew shadowJar
```

or equivalent.

----------

# **48. GitHub Release Readiness**

The repository should be structured so GitHub Actions can later:

1.  Build.
2.  Test.
3.  Run deterministic integration tests.
4.  Package the runnable JAR.
5.  Attach the JAR to a GitHub Release when a version tag is pushed.

A basic release workflow may be included if straightforward.

Do not require publication to Maven Central for V1.

----------

# **49. User Experience Example**

Expected workflow:

```bash
cd ~/projects/my-app
```

Create an investigation:

```bash
bisectai init calculator-regression
```

BisectAI:

```text
Created:

.bisectai/investigations/calculator-regression.md
```

User edits the investigation.

Then:

```bash
bisectai run \
  --investigation calculator-regression \
  --good abc123 \
  --bad def456 \
  --output-type report \
  --output ./calculator-regression-result.md
```

Expected console experience:

```text
BisectAI

Investigation: calculator-regression
Repository: /Users/example/projects/my-app

Validating investigation... OK
Validating Git range... OK

Verifying boundaries...

abc123  GOOD  12.4s
def456  BAD   13.1s

Starting bisect...

8a71fe2  GOOD  11.8s
c37219a  BAD   12.2s
9bb8211  GOOD  11.9s

Culprit identified:

c37219a
Change calculator total behavior

Verifying transition...

9bb8211  GOOD
c37219a  BAD

Transition verified.

Analyzing culprit with Claude...

Analysis complete.

Report written to:

./calculator-regression-result.md
```

----------

# **50. Architectural Invariants**

These rules are mandatory.

## **Invariant 1**

AI never determines the culprit commit in V1.

## **Invariant 2**

AI never determines GOOD/BAD classification in V1.

## **Invariant 3**

The investigation file is parsed into a typed model before execution.

## **Invariant 4**

Bisect execution operates on the typed model, not raw Markdown/YAML.

## **Invariant 5**

Every external process has a timeout.

## **Invariant 6**

The user’s active working tree must not be modified.

## **Invariant 7**

The supplied GOOD and BAD boundaries are verified before bisect begins.

## **Invariant 8**

The culprit transition is verified before Claude analysis begins.

## **Invariant 9**

Claude analysis failure does not invalidate a successful deterministic bisect.

## **Invariant 10**

JSON and Markdown output derive from the same canonical result model.

## **Invariant 11**

Secrets never belong in source-controlled investigation definitions.

## **Invariant 12**

Temporary Git state must be cleaned up on both success and failure.

----------

# **51. Implementation Priority**

Implement in this order.

### **Phase 1 - CLI and Specification**

Implement:

-   Picocli root command.
-   `init`.
-   `run`.
-   Investigation models.
-   Markdown/YAML parsing.
-   Investigation validation.

### **Phase 2 - Execution**

Implement:

-   ProcessRunner.
-   Timeouts.
-   stdout/stderr capture.
-   Exit-code classification.
-   Failure policy.

### **Phase 3 - Git**

Implement:

-   Repository validation.
-   Commit validation.
-   Ancestry validation.
-   Worktree isolation.
-   Bisect lifecycle.
-   GOOD/BAD/SKIP handling.
-   Cleanup.

### **Phase 4 - Investigation**

Implement:

-   Boundary verification.
-   Bisect execution.
-   Culprit identification.
-   Culprit verification.
-   Canonical InvestigationResult.

### **Phase 5 - Reporting**

Implement:

-   JSON renderer.
-   Markdown report renderer.
-   `--output-type`.
-   `--output`.
-   stdout behavior.

### **Phase 6 - Claude**

Implement:

-   AnalysisProvider.
-   ClaudeAnalysisProvider.
-   Authentication.
-   Structured analysis request.
-   Structured response parsing.
-   AI error handling.

### **Phase 7 - Integration Testing**

Implement:

-   Temporary sample Git repository.
-   GOOD commits.
-   Intentionally introduced BAD commit.
-   End-to-end bisect.
-   Fake analysis provider.
-   Optional live Claude integration test.

### **Phase 8 - Distribution**

Implement:

-   Fat JAR.
-   Version metadata.
-   GitHub Actions build/test workflow.
-   Release-ready artifact generation.

----------

# **52. Definition of Done**

BisectAI V1 is complete when the following scenario works end-to-end:

A developer has a repository containing a regression.

They create:

```bash
bisectai init calculator-regression
```

They define:

```yaml
validation:
  command: "./gradlew test"

classification:
  type: "exit-code"
  goodExitCodes:
    - 0
```

They run:

```bash
bisectai run \
  --investigation calculator-regression \
  --good <known-good-sha> \
  --bad <known-bad-sha> \
  --output-type report \
  --output result.md
```

BisectAI:

1.  Validates the repository.
2.  Validates the investigation.
3.  Verifies the known-good commit is GOOD.
4.  Verifies the known-bad commit is BAD.
5.  Uses isolated Git state.
6.  Bisects the commit range.
7.  Runs the supplied deterministic validation command on candidate commits.
8.  Finds the first bad commit.
9.  Verifies the final GOOD -> BAD transition.
10.  Collects the commit diff and validation evidence.
11.  Sends that bounded evidence to Claude.
12.  Receives structured root-cause analysis.
13.  Generates `result.md`.
14.  Cleans up all temporary Git state.
15.  Leaves the developer’s active working tree untouched.

The included deterministic integration test must independently demonstrate that BisectAI correctly finds an intentionally introduced faulty commit without requiring Claude.

----------

# **53. Implementation Guidance for Claude**

Treat this document as the authoritative V1 requirements specification.

Before implementation:

1.  Review the requirements.
2.  Identify any genuine technical contradictions or blockers.
3.  Do not redesign the product unless necessary.
4.  Prefer simple abstractions over speculative extensibility.
5.  Do not add features merely because they may be useful later.
6.  Maintain the architectural boundary between deterministic regression discovery and AI analysis.
7.  Build the system incrementally according to the implementation priority above.
8.  Add tests alongside each subsystem rather than postponing testing until the end.
9.  Ensure the end-to-end deterministic integration test passes before integrating the live Claude provider.
10.  Ensure the final project can be built and exercised from a clean checkout using documented Gradle commands.

When an implementation detail is unspecified, choose the simplest solution consistent with the architectural invariants and V1 goals.
