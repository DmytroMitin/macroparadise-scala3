#!/usr/bin/env bash
set -euo pipefail

usage() {
  printf '%s\n' \
    'Usage: scripts/verify-public-product-fresh-copy.sh [--source PATH]' \
    '' \
    'Create a disposable product-only copy with fresh dependency/output caches' \
    'and run the canonical verifyPublicProductBoundary gate inside it.'
}

source_root=''
while (($# > 0)); do
  case "$1" in
    --source)
      (($# >= 2)) || { usage >&2; exit 2; }
      source_root="$2"
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

if [[ -z "$source_root" ]]; then
  script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
  source_root="$script_dir/.."
fi
source_root="$(git -C "$source_root" rev-parse --show-toplevel)"

task_root="$(mktemp -d "${TMPDIR:-/tmp}/macroparadise-public-copy.XXXXXX")"
trap 'rm -rf -- "$task_root"' EXIT
copy_root="$task_root/copy"
cache_root="$task_root/caches"
mkdir -p "$copy_root" "$cache_root/coursier" "$cache_root/ivy" "$cache_root/sbt-global" "$cache_root/sbt-boot"

copied=0
while IFS= read -r -d '' relative; do
  case "$relative" in
    /*|../*|*/../*)
      printf 'Unsafe repository path: %s\n' "$relative" >&2
      exit 1
      ;;
  esac
  source_path="$source_root/$relative"
  [[ -f "$source_path" || -L "$source_path" ]] || continue
  destination="$copy_root/$relative"
  mkdir -p "$(dirname "$destination")"
  cp -p -- "$source_path" "$destination"
  copied=$((copied + 1))
done < <(git -C "$source_root" ls-files --cached --others --exclude-standard -z)

[[ -f "$copy_root/build.sbt" ]] || { printf 'Fresh copy lacks build.sbt\n' >&2; exit 1; }
[[ -f "$copy_root/project/build.properties" ]] || { printf 'Fresh copy lacks project/build.properties\n' >&2; exit 1; }

printf 'FRESH_PUBLIC_PRODUCT_COPY_PREPARED files=%s\n' "$copied"
(
  cd "$copy_root"
  export COURSIER_CACHE="$cache_root/coursier"
  export IVY_HOME="$cache_root/ivy"
  export SBT_OPTS="${SBT_OPTS:-} -Dsbt.global.base=$cache_root/sbt-global -Dsbt.boot.directory=$cache_root/sbt-boot -Dsbt.ivy.home=$cache_root/ivy"
  sbt -batch verifyPublicProductBoundary
)
printf 'FRESH_PUBLIC_PRODUCT_COPY_PASS files=%s\n' "$copied"
