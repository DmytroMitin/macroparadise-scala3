#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
product_root="$(cd "$script_dir/.." && pwd -P)"
evidence_root="$(mktemp -d "${TMPDIR:-/tmp}/macroparadise-jdk25-bootstrap.XXXXXX")"
trap 'rm -rf -- "$evidence_root"' EXIT
log="$evidence_root/wrong-jdk.log"

set +e
(
  cd "$product_root"
  export SBT_OPTS="${SBT_OPTS:-} -Dmacroparadise.internal.testUnsupportedJdkFeature=8"
  sbt -batch reload
) >"$log" 2>&1
status=$?
set -e

if ((status == 0)); then
  printf 'synthetic unsupported JDK unexpectedly loaded the build\n' >&2
  cat "$log" >&2
  exit 1
fi

grep -F -- 'loading settings for project macroparadise-scala3-build from jdk25-bootstrap.sbt' "$log"
grep -F -- 'Unsupported JVM for macroparadise-scala3: detected JVM version `8 (test-only synthetic feature)` (feature 8); required major version is 25.' "$log"
grep -F -- 'Select JDK 25 before rerunning sbt.' "$log"

if grep -F -- 'loading project definition from' "$log" >/dev/null; then
  printf 'unsupported JDK reached ordinary project definition loading\n' >&2
  cat "$log" >&2
  exit 1
fi

if grep -E -- 'value (version|readAllBytes|setTimeLocal) is not a member' "$log" >/dev/null; then
  printf 'unsupported JDK reached post-Java-8 API compilation errors\n' >&2
  cat "$log" >&2
  exit 1
fi

printf 'JDK25_BOOTSTRAP_GUARD_PASS syntheticFeature=8 stage=meta-build-settings\n'
