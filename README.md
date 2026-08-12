# BisectAI

BisectAI is a Kotlin/JVM command-line tool that automates Git regression investigation.

You give it a repository, a known-good commit, a known-bad commit, and a reusable
**investigation** describing how to tell whether a commit is GOOD or BAD. BisectAI uses Git's
own `bisect` to deterministically find the first bad commit, verifies the GOOD → BAD transition,
and then asks Claude to explain *why* that commit likely caused the regression.

> **Architectural rule:** Git and deterministic validation decide **where** the regression was
> introduced. AI only explains **why**. AI never chooses GOOD/BAD or the culprit.

## Requirements

- **JDK 21** (LTS). The build uses Gradle toolchains and will auto-provision Temurin 21 for
  compilation, but the Gradle daemon itself must run on a JDK it supports. If you have a newer
  non-LTS JDK on your `PATH` (e.g. Java 24/25/26), run Gradle with JDK 21, for example:
  ```bash
  JAVA_HOME=/path/to/jdk-21 ./gradlew build
  ```
  CI provisions JDK 21 directly, so this only matters for local builds.
- `git` on your `PATH` (2.30+; per-worktree bisect state recommended).

## Build

```bash
./gradlew build            # compile + unit tests + deterministic integration test
./gradlew :cli:shadowJar   # produce the runnable fat JAR
```

The fat JAR lands at `cli/build/libs/bisectai-<version>.jar`:

```bash
java -jar cli/build/libs/bisectai-0.1.0.jar --help
```

## Usage

### 1. Create an investigation

```bash
cd ~/projects/my-app
java -jar bisectai.jar init calculator-regression
# Created:
#
# .bisectai/investigations/calculator-regression.md
```

Edit the generated file. The YAML front matter is authoritative for execution; the Markdown body
is context handed to Claude.

```yaml
---
version: 1
name: "calculator-regression"
validation:
  command: "./gradlew test"   # exit 0 = GOOD, anything else = BAD
  attempts: 1
  warmupAttempts: 0
  timeoutSeconds: 300
classification:
  type: "exit-code"
  goodExitCodes:
    - 0
failure:
  onExecutionFailure: "abort"  # or "skip"
---

# Regression Description

Describe the expected vs. observed behavior here.
```

### 2. Run the investigation

```bash
java -jar bisectai.jar run \
  --investigation calculator-regression \
  --good <known-good-sha> \
  --bad  <known-bad-sha> \
  --output-type report \
  --output ./result.md
```

- `--repo <dir>` — repository to investigate (default: current directory).
- `--investigation <name>` resolves to `<repo>/.bisectai/investigations/<name>.md`.
- `--requirements <file>` — use an investigation file outside the repo (mutually exclusive with
  `--investigation`).
- `--output-type json|report` — `report` (Markdown, default) or `json`.
- `--output <path>` — write to a file; omit to print to stdout.
- `--model <id>` — Claude model for analysis (default `claude-sonnet-5`).

Progress is written to **stderr**, so JSON on stdout stays clean:

```bash
java -jar bisectai.jar run ... --output-type json | jq .
```

BisectAI evaluates every candidate commit in an **isolated Git worktree** created in a temporary
directory. Your working tree, branch, index, and checkout are never touched, and all temporary
Git state is cleaned up on success and failure alike.

## Claude authentication

BisectAI uses Anthropic's official Java SDK with standard environment-based authentication.
Set **one** of:

```bash
export ANTHROPIC_API_KEY=...      # or
export ANTHROPIC_AUTH_TOKEN=...
```

Secrets never belong in investigation files or CLI arguments. If analysis fails (missing
credentials, auth error, rate limit, network failure, invalid response, timeout), the
deterministic bisect result is still produced and reported — only the AI section is marked
unavailable, and the process exit code stays `0`.

## Exit codes

| Code | Meaning |
| ---- | ------- |
| 0 | Investigation completed |
| 1 | General failure |
| 2 | Invalid CLI arguments |
| 3 | Invalid investigation definition |
| 4 | Invalid Git repository/range |
| 5 | Validation execution failure |
| 6 | Bisect inconclusive |
| 7 | Output generation failure |

An AI analysis failure does **not** produce a non-zero exit code when the bisect succeeded.

## Testing

```bash
./gradlew test                                    # unit tests (per module)
./gradlew :integration-tests:integrationTest      # deterministic end-to-end (no Claude)
./gradlew :integration-tests:claudeIntegrationTest # live Claude test; auto-skips without creds
```

The deterministic integration test builds a real temporary Git repository with an intentionally
introduced regression and proves BisectAI finds the faulty commit — without calling Claude.

## Project layout

Multi-module Gradle build, one module per architectural boundary:

```
core         shared domain types
execution    ProcessRunner + timeouts
spec         investigation parsing/validation + init template
git          git CLI wrapper: worktree, bisect, diff, ancestry
evaluation   GOOD/BAD/UNKNOWN classification
analysis     AnalysisProvider + ClaudeAnalysisProvider + FakeAnalysisProvider
reporting    JSON + Markdown renderers (from one canonical model)
cli          Picocli commands + orchestrator (produces the fat JAR)
integration-tests  end-to-end regression scenarios
```
