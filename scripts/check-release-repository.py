#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import sys
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path


GROUP = "com.github.dmytromitin"
GROUP_PATH = Path("com/github/dmytromitin")
VERSION = "0.1.0"
SCALA_VERSION = "3.8.4"
PLUGIN_API = f"macroparadise-scala3-plugin-api_{SCALA_VERSION}"
PLUGIN = f"macroparadise-scala3-plugin_{SCALA_VERSION}"
MODULES = (PLUGIN_API, PLUGIN)
CLASSIFIERS = ("", "-sources", "-javadoc")
CHECKSUMS = ("md5", "sha1", "sha256", "sha512")
PROJECT_URL = "https://github.com/DmytroMitin/macroparadise-scala3"
LICENSE_NAME = "Apache-2.0"
LICENSE_URL = "https://www.apache.org/licenses/LICENSE-2.0"
PASS = "UNSIGNED_RELEASE_CANDIDATE_REHEARSAL_PASS_OWNER_SIGNING_REMAINS"
SOURCE_STATUS_PREPARED = "PREPARED_WORKTREE_AWAITING_PUBLICATION_COMMIT"


def digest(path: Path, algorithm: str) -> str:
    value = hashlib.new(algorithm)
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def children(element: ET.Element | None, name: str) -> list[ET.Element]:
    if element is None:
        return []
    return [child for child in element if child.tag.rsplit("}", 1)[-1] == name]


def one(element: ET.Element | None, name: str) -> ET.Element | None:
    values = children(element, name)
    return values[0] if len(values) == 1 else None


def text(element: ET.Element | None, name: str) -> str:
    value = one(element, name)
    return (value.text or "").strip() if value is not None else ""


def pom_dependencies(path: Path, module: str, errors: list[str]) -> list[dict[str, str]]:
    try:
        root = ET.parse(path).getroot()
    except (OSError, ET.ParseError) as error:
        errors.append(f"POM_INVALID:{module}:{error}")
        return []
    for field, expected in (("groupId", GROUP), ("artifactId", module), ("version", VERSION)):
        if text(root, field) != expected:
            errors.append(f"POM_IDENTITY_INVALID:{module}:{field}")
    for field in ("name", "description"):
        if not text(root, field):
            errors.append(f"POM_METADATA_MISSING:{module}:{field}")
    if text(root, "url") != PROJECT_URL:
        errors.append(f"POM_PROJECT_URL_INVALID:{module}")
    licenses = children(one(root, "licenses"), "license")
    if not (
        len(licenses) == 1
        and text(licenses[0], "name") == LICENSE_NAME
        and text(licenses[0], "url") == LICENSE_URL
        and text(licenses[0], "distribution") == "repo"
    ):
        errors.append(f"POM_LICENSE_INVALID:{module}")
    scm = one(root, "scm")
    if scm is None or text(scm, "url") != PROJECT_URL or not text(scm, "connection").startswith("scm:git:"):
        errors.append(f"POM_SCM_INVALID:{module}")
    developers = children(one(root, "developers"), "developer")
    if len(developers) != 1 or any(not text(developers[0], field) for field in ("id", "name", "email", "url")):
        errors.append(f"POM_DEVELOPER_INVALID:{module}")
    if children(root, "repositories") or children(root, "distributionManagement"):
        errors.append(f"POM_FORBIDDEN_REPOSITORY_METADATA:{module}")
    rendered = path.read_text(encoding="utf-8", errors="replace")
    forbidden_leaks = ("-SNAPSHOT", "/" + "home/", "/" + "tmp/")
    if any(token in rendered for token in forbidden_leaks):
        errors.append(f"POM_PRIVATE_OR_SNAPSHOT_LEAK:{module}")

    dependencies: list[dict[str, str]] = []
    for dependency in children(one(root, "dependencies"), "dependency"):
        dependencies.append(
            {
                "group": text(dependency, "groupId"),
                "artifact": text(dependency, "artifactId"),
                "version": text(dependency, "version"),
                "scope": text(dependency, "scope") or "compile",
            }
        )
    compile_coordinates = {
        (item["group"], item["artifact"], item["version"])
        for item in dependencies
        if item["scope"] != "test"
    }
    compiler = ("org.scala-lang", "scala3-compiler_3", SCALA_VERSION)
    if compiler not in compile_coordinates:
        errors.append(f"POM_COMPILER_DEPENDENCY_INVALID:{module}")
    if module == PLUGIN and (GROUP, PLUGIN_API, VERSION) in compile_coordinates:
        errors.append(f"POM_PLUGIN_API_DEPENDENCY_UNNECESSARY:{module}")
    return sorted(dependencies, key=lambda item: (item["scope"], item["group"], item["artifact"]))


def check(
    project: Path,
    repository: Path,
    source_identity: str,
    source_status: str = SOURCE_STATUS_PREPARED,
) -> tuple[dict[str, object], list[str]]:
    errors: list[str] = []
    group_root = repository / GROUP_PATH
    actual_modules = {path.name for path in group_root.iterdir() if path.is_dir()} if group_root.is_dir() else set()
    for module in sorted(set(MODULES) - actual_modules):
        errors.append(f"COORDINATE_MISSING:{module}")
    for module in sorted(actual_modules - set(MODULES)):
        errors.append(f"COORDINATE_UNEXPECTED:{module}")
    legacy_root = repository / "io/github/dmytromitin"
    if legacy_root.exists():
        errors.append("LEGACY_NAMESPACE_PRESENT")

    license_bytes = (project / "LICENSE").read_bytes()
    coordinates: list[dict[str, object]] = []
    for module in MODULES:
        directory = group_root / module / VERSION
        base = f"{module}-{VERSION}"
        deployables = [directory / f"{base}.pom"] + [directory / f"{base}{classifier}.jar" for classifier in CLASSIFIERS]
        expected_files: set[str] = set()
        files: list[dict[str, object]] = []
        for deployable in deployables:
            expected_files.add(deployable.name)
            expected_files.update(deployable.name + f".{algorithm}" for algorithm in CHECKSUMS)
            if not deployable.is_file():
                errors.append(f"DEPLOYABLE_MISSING:{module}:{deployable.name}")
                continue
            signature = deployable.with_name(deployable.name + ".asc")
            if signature.exists():
                errors.append(f"UNAUTHORIZED_SIGNATURE_PRESENT:{module}:{signature.name}")
            for algorithm in CHECKSUMS:
                checksum = deployable.with_name(deployable.name + f".{algorithm}")
                if not checksum.is_file() or checksum.read_text(encoding="ascii").strip().lower() != digest(deployable, algorithm):
                    errors.append(f"CHECKSUM_INVALID:{module}:{deployable.name}:{algorithm}")
            files.append(
                {
                    "relative_path": deployable.relative_to(repository).as_posix(),
                    "filename": deployable.name,
                    "size": deployable.stat().st_size,
                    "sha256": digest(deployable, "sha256"),
                    "sha512": digest(deployable, "sha512"),
                    "checksums": {algorithm: digest(deployable, algorithm) for algorithm in CHECKSUMS},
                    "detached_signature": {
                        "filename": deployable.name + ".asc",
                        "status": "OWNER_SIGNATURE_REQUIRED_NOT_PRESENT",
                    },
                    "signature": None,
                    "signature_status": "OWNER_GATED_NOT_SIGNED",
                }
            )
        if directory.is_dir():
            for extra in sorted(path.name for path in directory.iterdir() if path.is_file() and path.name not in expected_files):
                if extra.endswith(".asc"):
                    errors.append(f"UNAUTHORIZED_SIGNATURE_PRESENT:{module}:{extra}")
                else:
                    errors.append(f"FILE_UNEXPECTED:{module}:{extra}")
        pom = directory / f"{base}.pom"
        dependencies = pom_dependencies(pom, module, errors) if pom.is_file() else []
        for jar in [directory / f"{base}{classifier}.jar" for classifier in CLASSIFIERS]:
            if jar.is_file():
                try:
                    with zipfile.ZipFile(jar) as archive:
                        if archive.read("META-INF/LICENSE") != license_bytes:
                            errors.append(f"JAR_LICENSE_INVALID:{module}:{jar.name}")
                except (KeyError, OSError, zipfile.BadZipFile):
                    errors.append(f"JAR_LICENSE_INVALID:{module}:{jar.name}")
        coordinates.append(
            {
                "coordinate": f"{GROUP}:{module}:{VERSION}",
                "scala_compiler_line": SCALA_VERSION,
                "files": files,
                "pom_dependencies": dependencies,
            }
        )

    primary_records = sorted(
        (
            {
                "relative_path": item["relative_path"],
                "size": item["size"],
                "sha256": item["sha256"],
                "sha512": item["sha512"],
            }
            for coordinate in coordinates
            for item in coordinate["files"]  # type: ignore[index]
        ),
        key=lambda item: str(item["relative_path"]),
    )
    primary_manifest_bytes = json.dumps(
        primary_records,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")

    manifest: dict[str, object] = {
        "schema": "macroparadise-release-candidate-manifest-v2",
        "source_identity": source_identity,
        "source": {
            "identity": source_identity,
            "status": source_status,
        },
        "candidate_version": VERSION,
        "scala_compiler_line": SCALA_VERSION,
        "release_contract": {
            "organization": GROUP,
            "version": VERSION,
            "scala_full_version": SCALA_VERSION,
            "jdk_feature": 25,
            "sbt_version": "1.12.15",
            "plugin_name": "macroparadise",
            "future_tag_if_separately_authorized": "v0.1.0",
            "publication_allowlist": ["pluginApi", "plugin"],
            "nightly_resolver_required": False,
        },
        "license": {
            "name": LICENSE_NAME,
            "url": LICENSE_URL,
            "distribution": "repo",
        },
        "coordinates": coordinates,
        "primary_manifest_sha256": hashlib.sha256(primary_manifest_bytes).hexdigest(),
        "signing": {
            "required_for_remote_release": True,
            "status": "OWNER_GATED_NOT_SIGNED",
            "reason": "Owner signatures are a separate authorization and must cover these frozen primary bytes without rebuilding.",
        },
        "remote_state": "NOT_UPLOADED_NOT_PUBLISHED_NO_TAG_NO_GITHUB_RELEASE",
        "assertions": {
            "exact_coordinate_set": not any(error.startswith("COORDINATE_") for error in errors),
            "non_snapshot": "SNAPSHOT" not in VERSION,
            "all_checksums_verified": not any(error.startswith("CHECKSUM_") for error in errors),
            "no_remote_upload": True,
            "no_signature_performed": not any(error.startswith("UNAUTHORIZED_SIGNATURE_") for error in errors),
        },
    }
    return manifest, sorted(set(errors))


def markdown(manifest: dict[str, object]) -> str:
    lines = [
        "# Local unsigned release-candidate manifest",
        "",
        f"Source: `{manifest['source_identity']}`",
        f"Version: `{manifest['candidate_version']}`",
        f"Scala: `{manifest['scala_compiler_line']}`",
        "Signing: `OWNER_GATED_NOT_SIGNED`",
        "",
    ]
    for coordinate in manifest["coordinates"]:  # type: ignore[index]
        lines += [
            f"## {coordinate['coordinate']}",
            "",
            "| File | Size | SHA-256 | SHA-512 | Signature |",
            "|---|---:|---|---|---|",
        ]
        for item in coordinate["files"]:
            lines.append(
                f"| `{item['filename']}` | {item['size']} | `{item['sha256']}` | `{item['sha512']}` | `{item['signature_status']}` |"
            )
        lines += ["", "POM dependencies:", ""]
        for dependency in coordinate["pom_dependencies"]:
            lines.append(
                f"- `{dependency['group']}:{dependency['artifact']}:{dependency['version']}` ({dependency['scope']})"
            )
        lines.append("")
    lines += [
        "Detached signatures are required for a remote release but were not created in this rehearsal.",
        "No Central authentication, upload, tag, release, or package publication was performed.",
        "",
    ]
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("project", type=Path)
    parser.add_argument("repository", type=Path)
    parser.add_argument("--source-identity", required=True)
    parser.add_argument(
        "--source-status",
        default=SOURCE_STATUS_PREPARED,
        choices=(SOURCE_STATUS_PREPARED, "COMMITTED_RELEASE_CANDIDATE"),
    )
    parser.add_argument("--json", type=Path, required=True)
    parser.add_argument("--markdown", type=Path, required=True)
    args = parser.parse_args()
    manifest, errors = check(
        args.project.resolve(),
        args.repository.resolve(),
        args.source_identity,
        args.source_status,
    )
    if errors:
        for error in errors:
            print(error, file=sys.stderr)
        print("UNSIGNED_RELEASE_CANDIDATE_REHEARSAL_BLOCKED", file=sys.stderr)
        return 3
    args.json.parent.mkdir(parents=True, exist_ok=True)
    args.markdown.parent.mkdir(parents=True, exist_ok=True)
    args.json.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    args.markdown.write_text(markdown(manifest), encoding="utf-8")
    print(PASS)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
