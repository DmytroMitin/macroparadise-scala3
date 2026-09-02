#!/usr/bin/env bash
set -euo pipefail

usage() {
  printf '%s\n' \
    'Usage: scripts/verify-public-product-fresh-copy.sh [--source PATH] [--scala-version 3.3.8|3.8.4|3.9.0] [--include-untracked PATH]...' \
    '' \
    'Create a disposable product-only copy with fresh dependency/output caches' \
    'and run the canonical verifyPublicProductBoundary gate inside it.' \
    '' \
    'Tracked files are included automatically. An exact, non-ignored untracked' \
    'source file may be included explicitly with a repeated --include-untracked.'
}

source_root=''
scala_version='3.8.4'
include_untracked=()
while (($# > 0)); do
  case "$1" in
    --source)
      (($# >= 2)) || { usage >&2; exit 2; }
      source_root="$2"
      shift 2
      ;;
    --include-untracked)
      (($# >= 2)) || { usage >&2; exit 2; }
      include_untracked+=("$2")
      shift 2
      ;;
    --scala-version)
      (($# >= 2)) || { usage >&2; exit 2; }
      scala_version="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      printf 'Unknown argument: %s\n' "$1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

case "$scala_version" in
  3.3.8|3.8.4|3.9.0) ;;
  *)
    printf 'Unsupported exact Scala version: %s\n' "$scala_version" >&2
    exit 2
    ;;
esac

if [[ -z "$source_root" ]]; then
  script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
  source_root="$script_dir/.."
fi
source_root="$(git -C "$source_root" rev-parse --show-toplevel)"

is_generated_path() {
  case "$1" in
    target/*|*/target/*|project/project/*|*/project/project/*|.bloop/*|*/.bloop/*|.bsp/*|*/.bsp/*|.metals/*|*/.metals/*|.scala-build/*|*/.scala-build/*|out/*|*/out/*|.sbt/*|*/.sbt/*|.ivy2/*|*/.ivy2/*|.coursier/*|*/.coursier/*|.idea/*|*/.idea/*|.vscode/*|*/.vscode/*|*.iml)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

validated_untracked=()
for relative in "${include_untracked[@]}"; do
  case "$relative" in
    ''|/*|.|..|../*|*/../*|*/..|*\\*)
      printf 'Unsafe explicitly included untracked path: %s\n' "$relative" >&2
      exit 2
      ;;
  esac
  if is_generated_path "$relative"; then
    printf 'Generated/cache path cannot be included as task-owned source: %s\n' "$relative" >&2
    exit 2
  fi
  if [[ "$relative" == ".git" || "$relative" == .git/* ]]; then
    printf 'Git metadata cannot be included as task-owned source: %s\n' "$relative" >&2
    exit 2
  fi
  source_path="$source_root/$relative"
  if [[ ! -f "$source_path" || -L "$source_path" ]]; then
    printf 'Explicitly included untracked source is not a regular file: %s\n' "$relative" >&2
    exit 2
  fi
  if git -C "$source_root" ls-files --error-unmatch -- "$relative" >/dev/null 2>&1; then
    printf 'Explicitly included path is already tracked: %s\n' "$relative" >&2
    exit 2
  fi
  if git -C "$source_root" check-ignore -q --no-index -- "$relative"; then
    printf 'Ignored path cannot be included as task-owned source: %s\n' "$relative" >&2
    exit 2
  fi
  for existing in "${validated_untracked[@]}"; do
    if [[ "$existing" == "$relative" ]]; then
      printf 'Duplicate explicitly included untracked path: %s\n' "$relative" >&2
      exit 2
    fi
  done
  validated_untracked+=("$relative")
done

task_root="$(mktemp -d "${TMPDIR:-/tmp}/macroparadise-public-copy.XXXXXX")"
trap 'rm -rf -- "$task_root"' EXIT
copy_root="$task_root/copy"
cache_root="$task_root/caches"
mkdir -p "$copy_root" "$cache_root/coursier" "$cache_root/ivy" "$cache_root/sbt-global" "$cache_root/sbt-boot"

candidate_paths() {
  git -C "$source_root" ls-files --cached -z
  if [[ -f "$source_root/.gitignore" && ! -L "$source_root/.gitignore" ]] &&
    ! git -C "$source_root" ls-files --error-unmatch -- .gitignore >/dev/null 2>&1
  then
    printf '.gitignore\0'
  fi
  for relative in "${validated_untracked[@]}"; do
    printf '%s\0' "$relative"
  done
}

copied=0
tracked=0
allowed_untracked=0
while IFS= read -r -d '' relative; do
  case "$relative" in
    /*|../*|*/../*)
      printf 'Unsafe repository path: %s\n' "$relative" >&2
      exit 1
      ;;
  esac
  if is_generated_path "$relative"; then
    printf 'Tracked generated/cache path is forbidden in the fresh source candidate: %s\n' "$relative" >&2
    exit 1
  fi
  source_path="$source_root/$relative"
  [[ -f "$source_path" && ! -L "$source_path" ]] || continue
  destination="$copy_root/$relative"
  mkdir -p "$(dirname "$destination")"
  cp -p -- "$source_path" "$destination"
  copied=$((copied + 1))
  if git -C "$source_root" ls-files --error-unmatch -- "$relative" >/dev/null 2>&1; then
    tracked=$((tracked + 1))
  else
    allowed_untracked=$((allowed_untracked + 1))
  fi
done < <(candidate_paths)

[[ -f "$copy_root/build.sbt" ]] || { printf 'Fresh copy lacks build.sbt\n' >&2; exit 1; }
[[ -f "$copy_root/project/build.properties" ]] || { printf 'Fresh copy lacks project/build.properties\n' >&2; exit 1; }

printf 'FRESH_PUBLIC_PRODUCT_COPY_PREPARED files=%s tracked=%s allowedUntracked=%s\n' \
  "$copied" "$tracked" "$allowed_untracked"
(
  cd "$copy_root"
  export COURSIER_CACHE="$cache_root/coursier"
  export IVY_HOME="$cache_root/ivy"
  export SBT_OPTS="${SBT_OPTS:-} -Dsbt.global.base=$cache_root/sbt-global -Dsbt.boot.directory=$cache_root/sbt-boot -Dsbt.ivy.home=$cache_root/ivy"
  sbt "-Dmacroparadise.exactScalaVersion=$scala_version" -batch "++$scala_version!" verifyPublicProductBoundary
)
printf 'FRESH_PUBLIC_PRODUCT_COPY_PASS files=%s tracked=%s allowedUntracked=%s\n' \
  "$copied" "$tracked" "$allowed_untracked"
