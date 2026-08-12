#!/usr/bin/env bash
#
# Creates a throwaway Git repository containing a deliberately introduced regression, then
# (optionally) runs BisectAI against it so you can see exactly what the tool does.
#
# The "program" under test is a tiny, dependency-free shell script `calculate_total.sh` that
# computes calculateTotal({20, 22}) and asserts it equals 42. Commit C flips an OFFSET from 0 to
# 1 (total becomes 43) — the regression. History:
#
#     A  ----  B  ----  C  ----  D
#    GOOD     GOOD     BAD      BAD
#                       ^ expected culprit
#
# Usage:
#   examples/setup-sandbox.sh [SANDBOX_DIR]     # create the sandbox (default: /tmp/bisectai-sandbox)
#   examples/setup-sandbox.sh --run [SANDBOX_DIR]  # create it AND run BisectAI against it
#
# WARNING: SANDBOX_DIR is deleted and recreated each run.

set -euo pipefail

RUN=0
if [ "${1:-}" = "--run" ]; then RUN=1; shift; fi
SANDBOX="${1:-/tmp/bisectai-sandbox}"

# Resolve the BisectAI project root (this script lives in <root>/examples).
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

echo "==> Building the ./bisect-ai launcher (if needed)"
BIN="$ROOT/cli/build/bisect-ai"
if [ ! -x "$BIN" ]; then
  (cd "$ROOT" && ./gradlew --console=plain :cli:executable >/dev/null)
fi
echo "    $BIN"

echo "==> Creating sandbox repo at: $SANDBOX (this wipes any existing contents)"
rm -rf "$SANDBOX"
mkdir -p "$SANDBOX"
cd "$SANDBOX"

git init -q -b main
git config user.email "demo@bisectai.local"
git config user.name "BisectAI Demo"
git config commit.gpgsign false

write_program() {
  # $1 = OFFSET value (0 = good, 1 = regression)
  cat > calculate_total.sh <<EOF
#!/bin/sh
# calculateTotal({20, 22}) should equal 42.
OFFSET=$1
total=\$((20 + 22 + OFFSET))
if [ "\$total" -eq 42 ]; then
  echo "OK: total=\$total"
  exit 0
else
  echo "FAIL: expected 42 but got \$total" >&2
  exit 1
fi
EOF
  chmod +x calculate_total.sh
}

# A: correct implementation (GOOD)
write_program 0
echo "# Calculator" > README.md
git add -A && git commit -q -m "A: initial calculator"
A="$(git rev-parse HEAD)"

# B: unrelated change (still GOOD)
echo "A tiny calculator demo." >> README.md
git add -A && git commit -q -m "B: expand readme"

# C: regression introduced — OFFSET becomes 1, total becomes 43 (BAD)
write_program 1
git add -A && git commit -q -m "C: adjust total calculation"
C="$(git rev-parse HEAD)"

# D: unrelated change preserving the regression (still BAD)
echo "More docs." >> README.md
git add -A && git commit -q -m "D: more docs"
D="$(git rev-parse HEAD)"

# Investigation definition.
mkdir -p .bisectai/investigations
cat > .bisectai/investigations/demo.md <<'EOF'
---
version: 1
name: "calculator-demo"
validation:
  command: "sh calculate_total.sh"
  attempts: 1
  timeoutSeconds: 30
classification:
  type: "exit-code"
  goodExitCodes:
    - 0
failure:
  onExecutionFailure: "abort"
---

# Regression Description

`calculate_total.sh` should report the total for {20, 22} as 42. At some point it began
reporting 43. All commits where the total is 42 are GOOD; anything else is BAD.
EOF

cat <<EOF

==> Sandbox ready.

  Repository : $SANDBOX
  Good (A)   : $A   A: initial calculator
  Bad  (D)   : $D   D: more docs
  Culprit    : $C   C: adjust total calculation   <-- BisectAI should find THIS

Try it yourself:

  $BIN run \\
    --repo $SANDBOX \\
    --investigation demo \\
    --good $A \\
    --bad $D

  # machine-readable JSON (progress goes to stderr, so this pipes cleanly):
  $BIN run --repo $SANDBOX --investigation demo --good $A --bad $D --output-type json | jq .

Inspect the repo:  git -C $SANDBOX log --oneline
Set ANTHROPIC_API_KEY first if you want the AI root-cause section populated.
EOF

if [ "$RUN" -eq 1 ]; then
  echo
  echo "======================================================================"
  echo "Running BisectAI against the sandbox now..."
  echo "======================================================================"
  echo
  "$BIN" run --repo "$SANDBOX" --investigation demo --good "$A" --bad "$D"
fi
