#!/usr/bin/env bash

set -u
set -o pipefail

java_executable=$1
compiler_classpath=$2
consumer_classpath=$3
plugin_jar=$4
plugin_api_jar=$5
handler_jar=$6
marker_jar=$7
inspector_jar=$8
wrong_inspector_jar=$9
compiler_jar=${10}
malformed_jar=${11}
non_jar=${12}
unreadable_jar=${13}
empty_jar=${14}
legacy_source=${15}
unrelated_marker_source=${16}
unrelated_consumer_source=${17}
evidence_directory=${18}

path_separator=:
normal_plugin_path="${plugin_jar}${path_separator}${plugin_api_jar}${path_separator}${marker_jar}${path_separator}${inspector_jar}"
wrong_inspector_plugin_path="${plugin_jar}${path_separator}${plugin_api_jar}${path_separator}${marker_jar}${path_separator}${wrong_inspector_jar}"
inspector_absent_plugin_path="${plugin_jar}${path_separator}${plugin_api_jar}${path_separator}${marker_jar}"

run_case() {
  case_name=$1
  expected_exit=$2
  expected_text=$3
  plugin_path=$4
  source_kind=$5
  shift 5

  output_directory="${evidence_directory}/${case_name}-classes"
  log_file="${evidence_directory}/${case_name}.log"
  trace_file="${evidence_directory}/${case_name}.trace"
  mkdir -p "$output_directory"

  if [ "$source_kind" = "legacy" ]; then
    sources=("$legacy_source")
  else
    sources=("$unrelated_marker_source" "$unrelated_consumer_source")
  fi

  set +e
  "$java_executable" \
    -cp "$compiler_classpath" \
    dotty.tools.dotc.Main \
    -classpath "$consumer_classpath" \
    -d "$output_directory" \
    "-Xplugin:${plugin_path}" \
    -Xplugin-require:macroparadise \
    "-P:macroparadise:handlerClasspath=${handler_jar}" \
    "-P:macroparadise:metadataReaderTrace=${trace_file}" \
    "$@" \
    "${sources[@]}" \
    >"$log_file" 2>&1
  actual_exit=$?
  set -e

  if [ "$actual_exit" -ne "$expected_exit" ]; then
    echo "FAIL ${case_name}: expected exit ${expected_exit}, got ${actual_exit}"
    sed -n '1,160p' "$log_file"
    return 1
  fi
  if ! grep -Fq "$expected_text" "$log_file"; then
    echo "FAIL ${case_name}: missing diagnostic ${expected_text}"
    sed -n '1,160p' "$log_file"
    return 1
  fi
  echo "PASS ${case_name}: exit=${actual_exit} diagnostic=${expected_text}"
}

set -e

run_case \
  blank-path 1 \
  "empty experimental" \
  "$normal_plugin_path" legacy \
  "-P:macroparadise:structuredMetadataPath="

run_case \
  missing-path 1 \
  "does not exist" \
  "$normal_plugin_path" legacy \
  "-P:macroparadise:structuredMetadataPath=${evidence_directory}/absent-marker.jar"

run_case \
  duplicate-normalized-path 1 \
  "duplicate experimental structured metadata path" \
  "$normal_plugin_path" legacy \
  "-P:macroparadise:structuredMetadataPath=${marker_jar}" \
  "-P:macroparadise:structuredMetadataPath=${marker_jar}"

run_case \
  obsolete-carrier 1 \
  "exposes obsolete carrier" \
  "$normal_plugin_path" legacy \
  "-P:macroparadise:structuredMetadataPath=${plugin_api_jar}"

run_case \
  wrong-inspector-version 1 \
  "active inspector filename mismatch" \
  "$wrong_inspector_plugin_path" legacy \
  "-P:macroparadise:structuredMetadataPath=${marker_jar}"

run_case \
  inspector-absent-controlled-fallback 0 \
  "controlled string compatibility fallback remains enabled" \
  "$inspector_absent_plugin_path" legacy \
  "-P:macroparadise:structuredMetadataPath=${marker_jar}"
if ! grep -Fq "structured paradise3.legacyExternalDebug Failed" "${evidence_directory}/inspector-absent-controlled-fallback.trace"; then
  echo "FAIL inspector-absent-controlled-fallback: structured failure was not traced"
  exit 1
fi
if ! grep -Fq "string paradise3.legacyExternalDebug Found(demo.LegacyExternalDebugExpander)" "${evidence_directory}/inspector-absent-controlled-fallback.trace"; then
  echo "FAIL inspector-absent-controlled-fallback: string fallback did not recover"
  exit 1
fi
echo "PASS inspector-absent-controlled-fallback: structured=Failed -> string=Found"

run_case \
  unsafe-duplicate-identity 1 \
  "conflicting compiler/Scala/TASTy/API identity" \
  "$normal_plugin_path" legacy \
  "-P:macroparadise:structuredMetadataPath=${compiler_jar}"

run_case \
  malformed-jar 1 \
  "is malformed" \
  "$normal_plugin_path" legacy \
  "-P:macroparadise:structuredMetadataPath=${malformed_jar}"

run_case \
  non-jar-file 1 \
  "must be a readable JAR or directory" \
  "$normal_plugin_path" legacy \
  "-P:macroparadise:structuredMetadataPath=${non_jar}"

run_case \
  unreadable-jar 1 \
  "is not readable" \
  "$normal_plugin_path" legacy \
  "-P:macroparadise:structuredMetadataPath=${unreadable_jar}"

run_case \
  missing-marker-tasty 1 \
  "contains no" \
  "$normal_plugin_path" legacy \
  "-P:macroparadise:structuredMetadataPath=${empty_jar}"

run_case \
  unrelated-annotation-not-found 0 \
  "validated experimental distribution" \
  "$normal_plugin_path" unrelated \
  "-P:macroparadise:structuredMetadataPath=${marker_jar}"
if ! grep -Fq "runtime paradise3.unrelatedMarker NotFound" "${evidence_directory}/unrelated-annotation-not-found.trace"; then
  echo "FAIL unrelated-annotation-not-found: runtime NotFound was not traced"
  exit 1
fi
if ! grep -Fq "structured paradise3.unrelatedMarker NotFound" "${evidence_directory}/unrelated-annotation-not-found.trace"; then
  echo "FAIL unrelated-annotation-not-found: structured NotFound was not traced"
  exit 1
fi
if ! grep -Fq "string paradise3.unrelatedMarker NotFound" "${evidence_directory}/unrelated-annotation-not-found.trace"; then
  echo "FAIL unrelated-annotation-not-found: string NotFound was not traced"
  exit 1
fi
echo "PASS unrelated-annotation-not-found: runtime=NotFound -> structured=NotFound -> string=NotFound"
