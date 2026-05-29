#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

fail() {
  echo "check-consistency: $*" >&2
  exit 1
}

[[ -f AGENTS.md ]] || fail "missing AGENTS.md"
[[ -f ARCHITECTURE.md ]] || fail "missing ARCHITECTURE.md"
[[ -f docs/architecture/LAYERS.md ]] || fail "missing docs/architecture/LAYERS.md"
[[ -f docs/SECURITY.md ]] || fail "missing docs/SECURITY.md"

gp_count="$(find docs/golden-principles -maxdepth 1 -type f -name '*.md' 2>/dev/null | wc -l | tr -d ' ')"
[[ "${gp_count}" -ge 3 ]] || fail "expected at least 3 docs/golden-principles/*.md, found ${gp_count}"

[[ -f server/src/test/java/com/am/server/architecture/BoundaryTest.java ]] \
  || fail "missing ArchUnit BoundaryTest"

if ! grep -q 'archunit-junit5' server/build.gradle; then
  fail "server/build.gradle missing archunit-junit5 test dependency"
fi

echo "check-consistency: OK (harness files present; run 'cd server && gradle test' locally for ArchUnit)."
