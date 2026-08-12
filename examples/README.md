# Examples / Sandbox

A ready-made way to see BisectAI work against a real repository with a known regression.

## Quick start

```bash
# Create a sandbox repo AND run BisectAI against it in one shot:
examples/setup-sandbox.sh --run

# Or just create it, then run the printed command yourself:
examples/setup-sandbox.sh
```

By default the sandbox is created at `/tmp/bisectai-sandbox` (pass a different path as the last
argument). The directory is wiped and recreated on each run.

## What it sets up

A tiny, dependency-free program `calculate_total.sh` that asserts `calculateTotal({20, 22}) == 42`,
committed across four commits:

```
A  ----  B  ----  C  ----  D
GOOD     GOOD     BAD      BAD
                  ^ expected culprit (flips an OFFSET 0 -> 1, making the total 43)
```

The investigation (`.bisectai/investigations/demo.md`) validates each commit by running
`sh calculate_total.sh` and treating exit code `0` as GOOD.

## What you should see

BisectAI verifies the boundaries (A is GOOD, D is BAD), runs `git bisect` in an isolated
worktree, lands on **commit C** as the first bad commit, verifies the GOOD → BAD transition, and
prints a Markdown report (or JSON with `--output-type json`).

- Everything is deterministic: Git + the validation command decide the culprit.
- The **AI Root-Cause Analysis** section says "Unavailable" unless you export a key first:
  ```bash
  export ANTHROPIC_API_KEY=...
  ```
  With a key set, Claude adds a best-effort explanation of *why* commit C caused the regression.
  Without one, the deterministic result is still complete and the exit code is still `0`.

## Poke at it

```bash
git -C /tmp/bisectai-sandbox log --oneline          # see the A/B/C/D history
cat /tmp/bisectai-sandbox/.bisectai/investigations/demo.md
git -C /tmp/bisectai-sandbox show <culprit-sha>     # the one-line regression
```

Your sandbox working tree is never modified by a run, and no worktree/bisect state is left behind.
