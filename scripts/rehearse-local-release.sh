#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
source_identity="${1:-}"
if [[ -z "$source_identity" ]]; then
  echo "usage: scripts/rehearse-local-release.sh <exact-source-identity>" >&2
  exit 2
fi

output_root="$repository_root/target/prompt128-local-release-rehearsal"
raw_repository="$output_root/raw-repository"
candidate_repository="$output_root/candidate-repository"
manifest_json="$output_root/CANDIDATE_MANIFEST.json"
manifest_markdown="$output_root/CANDIDATE_MANIFEST.md"

rm -rf -- "$output_root"
mkdir -p "$raw_repository" "$candidate_repository"

(
  cd "$repository_root"
  sbt -batch \
    "set ThisBuild / publishTo := Some(Resolver.file(\"prompt128-task-local\", file(\"$raw_repository\"))(Resolver.mavenStylePatterns))" \
    "set ThisBuild / credentials := Nil" \
    "pluginApi/publish" \
    "plugin/publish"
)

group_path="com/github/dmytromitin"
version="0.1.0"
scala_version="3.8.5-RC1-bin-20260405-9478256-NIGHTLY"
modules=(
  "macroparadise-scala3-plugin-api_${scala_version}"
  "macroparadise-scala3-plugin_${scala_version}"
)

for module in "${modules[@]}"; do
  raw_directory="$raw_repository/$group_path/$module/$version"
  candidate_directory="$candidate_repository/$group_path/$module/$version"
  mkdir -p "$candidate_directory"
  base="$module-$version"
  deployables=(
    "$base.pom"
    "$base.jar"
    "$base-sources.jar"
    "$base-javadoc.jar"
  )
  for filename in "${deployables[@]}"; do
    cp "$raw_directory/$filename" "$candidate_directory/$filename"
    md5sum "$candidate_directory/$filename" | awk '{print $1}' > "$candidate_directory/$filename.md5"
    sha1sum "$candidate_directory/$filename" | awk '{print $1}' > "$candidate_directory/$filename.sha1"
    sha256sum "$candidate_directory/$filename" | awk '{print $1}' > "$candidate_directory/$filename.sha256"
    sha512sum "$candidate_directory/$filename" | awk '{print $1}' > "$candidate_directory/$filename.sha512"
  done
done

python3 "$repository_root/scripts/check-release-repository.py" \
  "$repository_root" \
  "$candidate_repository" \
  --source-identity "$source_identity" \
  --json "$manifest_json" \
  --markdown "$manifest_markdown"

echo "PROMPT128_NO_REMOTE_RELEASE_ACTION_PERFORMED"
echo "PROMPT128_OWNER_SIGNING_AND_UPLOAD_NOT_AUTHORIZED"
echo "manifest_json=$manifest_json"
echo "manifest_markdown=$manifest_markdown"
