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
./gradlew :cli:executable  # produce a single self-executing ./bisect-ai launcher
./gradlew :cli:shadowJar   # (or) just the runnable fat JAR
```

### Run as `./bisect-ai` (recommended)

`:cli:executable` produces a single self-contained file at `cli/build/bisect-ai` — a shell
launcher with the fat JAR appended — that you run directly, no `java -jar` needed:

```bash
./cli/build/bisect-ai --help
```

Install it on your `PATH` so you can run `bisect-ai` from anywhere:

```bash
install -m 0755 cli/build/bisect-ai /usr/local/bin/bisect-ai
bisect-ai --version
```

It still uses your installed JVM (honoring `JAVA_HOME` if set, otherwise `java` on `PATH`), but
you never type `java -jar` yourself.

### Or run the plain fat JAR

The fat JAR lands at `cli/build/libs/bisectai-<version>.jar`:

```bash
java -jar cli/build/libs/bisectai-0.1.0.jar --help
```

## Usage

### 1. Create an investigation

```bash
cd ~/projects/my-app
bisect-ai init calculator-regression
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
bisect-ai run \
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
bisect-ai run ... --output-type json | jq .
```

BisectAI evaluates every candidate commit in an **isolated Git worktree** created in a temporary
directory. Your working tree, branch, index, and checkout are never touched, and all temporary
Git state is cleaned up on success and failure alike.

## Manual mode (regressions you verify by using the app)

Some regressions can only be judged by a person driving the app — a UI journey with no
`./gradlew test` to run (e.g. "open the calculator and check multiplication"). Scaffold one with:

```bash
bisect-ai init multiply-flow --manual   # writes a manual template; then edit command + instructions
```

which generates an investigation with the classification type set to `manual`:

```yaml
---
version: 1
name: "multiply-flow"
validation:
  command: "./gradlew :app:installDebug"   # OPTIONAL setup: build/install/launch the candidate
  timeoutSeconds: 900
classification:
  type: "manual"
  instructions: "Open the calculator, multiply 7 x 6, expect 42."
---
# Regression Description
Multiplication started returning the wrong result.
```

For each candidate commit BisectAI checks it out in the isolated worktree, runs the optional
setup command, then prompts you on the terminal:

```
Open the calculator, multiply 7 x 6, expect 42.
Commit under test: 8a71fe2...
Verdict — [g]ood / [b]ad / [s]kip / [a]bort: b
Optional note (what did you observe?): 7x6 showed 43
```

- Your verdict maps to `git bisect good/bad/skip`; **`abort`** stops cleanly (state is still cleaned up).
- In `manual` mode the `command` is a **setup** step, not the classifier — a *human* classifies, so AI still never does (Invariant 2). If setup fails, `failure.onExecutionFailure` applies.
- The optional **note** you type is fed to Claude as evidence, so the AI root-cause analysis is grounded in what you actually saw.
- `attempts`/`warmupAttempts` don't apply. Manual mode requires an interactive terminal (a TTY);
  in CI/piped runs it fails fast rather than hanging.

For flows that *can* be scripted, keep `type: exit-code` and make the command a UI-automation run
(Maestro/Espresso/Appium) that exits non-zero on failure — no manual mode needed.

## Fixing the regression (`bisectai fix`)

Once `run` has produced a JSON result, `fix` can propose and **verify** a repair, then commit it
on a new branch:

```bash
bisectai fix \
  --result ./multiply-flow-result.json \
  --investigation multiply-flow \
  --output-type report
```

How it works:
- **AI proposes, the validation decides.** Claude edits only the files the bisect implicated, and
  the fix is accepted only if the investigation's own validation command now classifies the patched
  code GOOD (for `manual` investigations, it builds/installs the fix and **prompts you** to confirm).
- **Automatic revert fallback.** If the AI can't produce a verified fix within `--max-attempts`, it
  falls back to a deterministic `git revert` of the culprit and verifies that instead.
- **Safe by construction.** All work happens in an isolated worktree; your working tree, branch, and
  index are untouched. A verified fix is committed on a **new branch** (`bisectai/fix-...`) — never
  pushed, never merged, never amending existing history.

Useful flags: `--strategy revert` (skip AI, just revert), `--dry-run` (show the verified diff, commit
nothing), `--yes` (skip the confirmation prompt), `--branch <name>`, `--output`/`--output-type`.

Exit code is `0` only when a verified fix was committed.

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
