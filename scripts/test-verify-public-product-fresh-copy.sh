#!/usr/bin/env bash
set -euo pipefail

test_root="$(mktemp -d "${TMPDIR:-/tmp}/macroparadise-fresh-copy-test.XXXXXX")"
trap 'rm -rf "$test_root"' EXIT

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
product_root="$(cd "$script_dir/.." && pwd -P)"
source_root="$test_root/source"
source_without_ignore="$test_root/source-without-ignore"
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
test -f "$product_root/.gitignore"
cp "$product_root/.gitignore" "$source_root/.gitignore"
git -C "$source_root" add .
git -C "$source_root" commit -q -m root
printf '# task-owned\n' > "$source_root/docs/task-owned.md"
printf 'unexpected\n' > "$source_root/unexpected.tmp"

printf '%s\n' '#!/usr/bin/env bash' 'set -euo pipefail' \
  'if [[ "$PWD" == "$FRESH_COPY_TEST_SOURCE" ]]; then' \
  '  test "$*" = "-batch verifyPublicProductBoundary"' \
  '  mkdir -p target/experimental-structured-metadata-negative-lanes' \
  '  printf "generated\n" > target/experimental-structured-metadata-negative-lanes/unreadable-marker.jar' \
  '  chmod 000 target/experimental-structured-metadata-negative-lanes/unreadable-marker.jar' \
  '  exit 0' \
  'fi' \
  'test ! -d .git' \
  'test ! -e target/experimental-structured-metadata-negative-lanes/unreadable-marker.jar' \
  'test ! -e unexpected.tmp' \
  'test -f README.md' \
  'if [[ "${FRESH_COPY_TEST_EXPECT_TASK_OWNED:-0}" == 1 ]]; then test -f docs/task-owned.md; else test ! -e docs/task-owned.md; fi' \
  'printf "%s\n" "$PWD" > "$FRESH_COPY_TEST_EVIDENCE/cwd"' \
  'printf "%s\n" "$COURSIER_CACHE" > "$FRESH_COPY_TEST_EVIDENCE/coursier"' \
  'printf "%s\n" "$*" > "$FRESH_COPY_TEST_EVIDENCE/args"' \
  > "$fake_bin/sbt"
chmod +x "$fake_bin/sbt"

(
  cd "$source_root"
  PATH="$fake_bin:$PATH" FRESH_COPY_TEST_SOURCE="$source_root" \
    sbt -batch verifyPublicProductBoundary
)

! grep -Ev '^[[:space:]]*(#|$)' "$source_root/.git/info/exclude"
PATH="$fake_bin:$PATH" FRESH_COPY_TEST_SOURCE="$source_root" \
  FRESH_COPY_TEST_EXPECT_TASK_OWNED=1 FRESH_COPY_TEST_EVIDENCE="$evidence" \
  "$script_dir/verify-public-product-fresh-copy.sh" \
    --source "$source_root" \
    --include-untracked docs/task-owned.md

grep -Fx -- '-batch verifyPublicProductBoundary' "$evidence/args"
grep -F -- '/caches/coursier' "$evidence/coursier"
grep -F -- '/copy' "$evidence/cwd"

if PATH="$fake_bin:$PATH" FRESH_COPY_TEST_SOURCE="$source_root" \
  FRESH_COPY_TEST_EVIDENCE="$evidence" \
  "$script_dir/verify-public-product-fresh-copy.sh" \
    --source "$source_root" \
    --include-untracked target/experimental-structured-metadata-negative-lanes/unreadable-marker.jar
then
  printf 'generated output was accepted as task-owned source\n' >&2
  exit 1
fi

mkdir -p "$source_without_ignore"
git -C "$source_without_ignore" init -q -b main
git -C "$source_without_ignore" config user.name "Fresh Copy Test"
git -C "$source_without_ignore" config user.email "fresh-copy@example.invalid"
printf 'ThisBuild / publish / skip := true\n' > "$source_without_ignore/build.sbt"
mkdir -p "$source_without_ignore/project/target"
printf 'sbt.version=1.12.8\n' > "$source_without_ignore/project/build.properties"
printf '# tracked\n' > "$source_without_ignore/README.md"
git -C "$source_without_ignore" add .
git -C "$source_without_ignore" commit -q -m root
printf 'generated\n' > "$source_without_ignore/project/target/unreadable-marker.jar"
chmod 000 "$source_without_ignore/project/target/unreadable-marker.jar"
printf 'unexpected\n' > "$source_without_ignore/unexpected.tmp"

! grep -Ev '^[[:space:]]*(#|$)' "$source_without_ignore/.git/info/exclude"
PATH="$fake_bin:$PATH" FRESH_COPY_TEST_SOURCE="$source_without_ignore" \
  FRESH_COPY_TEST_EXPECT_TASK_OWNED=0 FRESH_COPY_TEST_EVIDENCE="$evidence" \
  "$script_dir/verify-public-product-fresh-copy.sh" --source "$source_without_ignore"

printf 'FRESH_PUBLIC_PRODUCT_COPY_SCRIPT_TEST_PASS\n'
