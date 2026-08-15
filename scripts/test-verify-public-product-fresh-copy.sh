#!/usr/bin/env bash
set -euo pipefail

test_root="$(mktemp -d "${TMPDIR:-/tmp}/macroparadise-fresh-copy-test.XXXXXX")"
trap 'rm -rf "$test_root"' EXIT

source_root="$test_root/source"
fake_bin="$test_root/bin"
evidence="$test_root/evidence"
mkdir -p "$source_root" "$fake_bin" "$evidence"

git -C "$source_root" init -q -b main
git -C "$source_root" config user.name "Fresh Copy Test"
git -C "$source_root" config user.email "fresh-copy@example.invalid"
printf 'ThisBuild / publish / skip := true\n' > "$source_root/build.sbt"
mkdir -p "$source_root/project" "$source_root/docs" "$source_root/target"
printf 'sbt.version=1.12.8\n' > "$source_root/project/build.properties"
printf '# tracked\n' > "$source_root/README.md"
printf 'ignored\n' > "$source_root/target/ignored.txt"
printf 'target/\n' > "$source_root/.gitignore"
git -C "$source_root" add .
git -C "$source_root" commit -q -m root
printf '# task-owned\n' > "$source_root/docs/task-owned.md"

printf '%s\n' '#!/usr/bin/env bash' 'set -euo pipefail' \
  'test ! -d .git' \
  'test ! -e target/ignored.txt' \
  'test -f README.md' \
  'test -f docs/task-owned.md' \
  'printf "%s\n" "$PWD" > "$FRESH_COPY_TEST_EVIDENCE/cwd"' \
  'printf "%s\n" "$COURSIER_CACHE" > "$FRESH_COPY_TEST_EVIDENCE/coursier"' \
  'printf "%s\n" "$*" > "$FRESH_COPY_TEST_EVIDENCE/args"' \
  > "$fake_bin/sbt"
chmod +x "$fake_bin/sbt"

PATH="$fake_bin:$PATH" FRESH_COPY_TEST_EVIDENCE="$evidence" \
  scripts/verify-public-product-fresh-copy.sh --source "$source_root"

grep -Fx -- '-batch verifyPublicProductBoundary' "$evidence/args"
grep -F -- '/caches/coursier' "$evidence/coursier"
grep -F -- '/copy' "$evidence/cwd"
printf 'FRESH_PUBLIC_PRODUCT_COPY_SCRIPT_TEST_PASS\n'
