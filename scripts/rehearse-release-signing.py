#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path


PASS = "EPHEMERAL_RELEASE_SIGNING_DRY_RUN_PASS"
BLOCKED = "EPHEMERAL_RELEASE_SIGNING_DRY_RUN_BLOCKED"
EXPECTED_PRIMARY_COUNT = 28


def digest(path: Path, algorithm: str = "sha256") -> str:
    value = hashlib.new(algorithm)
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def run(command: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        command,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        check=False,
    )


def primary_records(manifest: dict[str, object]) -> list[dict[str, object]]:
    return sorted(
        (
            {
                "relative_path": item["relative_path"],
                "size": item["size"],
                "sha256": item["sha256"],
                "sha512": item["sha512"],
            }
            for coordinate in manifest["coordinates"]  # type: ignore[index]
            for item in coordinate["files"]
        ),
        key=lambda item: str(item["relative_path"]),
    )


def primary_manifest_digest(records: list[dict[str, object]]) -> str:
    rendered = json.dumps(records, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(rendered).hexdigest()


def verify_unsigned_primaries(repository: Path, manifest: dict[str, object]) -> list[Path]:
    records = primary_records(manifest)
    if primary_manifest_digest(records) != manifest.get("primary_manifest_sha256"):
        raise ValueError("PRIMARY_MANIFEST_MISMATCH:manifest-digest")
    primaries: list[Path] = []
    for record in records:
        relative = Path(str(record["relative_path"]))
        primary = repository / relative
        if (
            not primary.is_file()
            or primary.stat().st_size != record["size"]
            or digest(primary, "sha256") != record["sha256"]
            or digest(primary, "sha512") != record["sha512"]
        ):
            raise ValueError(f"PRIMARY_MANIFEST_MISMATCH:{relative.as_posix()}")
        primaries.append(primary)
    if len(primaries) != EXPECTED_PRIMARY_COUNT:
        raise ValueError(
            f"PRIMARY_MANIFEST_MISMATCH:expected-{EXPECTED_PRIMARY_COUNT}:actual-{len(primaries)}"
        )
    return primaries


def fingerprint(home: Path) -> str:
    result = run(["gpg", "--batch", "--homedir", str(home), "--with-colons", "--list-secret-keys"])
    if result.returncode != 0:
        raise RuntimeError("EPHEMERAL_KEY_LIST_FAILED")
    values = [line.split(":")[9] for line in result.stdout.splitlines() if line.startswith("fpr:")]
    if not values or len(values[0]) != 40:
        raise RuntimeError("EPHEMERAL_FINGERPRINT_MISSING")
    return values[0]


def write_deterministic_zip(source: Path, destination: Path) -> None:
    with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in sorted(item for item in source.rglob("*") if item.is_file()):
            relative = path.relative_to(source).as_posix()
            info = zipfile.ZipInfo(relative, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, path.read_bytes())


def rehearse(repository: Path, manifest_path: Path, output: Path) -> dict[str, object]:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    primaries = verify_unsigned_primaries(repository, manifest)
    if output.exists():
        shutil.rmtree(output)
    output.mkdir(parents=True)
    signed_repository = output / "signed-repository"
    shutil.copytree(repository, signed_repository)

    verified: list[str] = []
    test_fingerprint = ""
    with tempfile.TemporaryDirectory(prefix="macroparadise-p135-gnupg.") as temporary:
        home = Path(temporary)
        home.chmod(0o700)
        generated = run(
            [
                "gpg",
                "--batch",
                "--homedir",
                str(home),
                "--passphrase",
                "",
                "--quick-generate-key",
                "Macro Paradise Prompt 135 Ephemeral Test Signer <macroparadise-p135@example.invalid>",
                "ed25519",
                "sign",
                "0",
            ]
        )
        if generated.returncode != 0:
            raise RuntimeError("EPHEMERAL_KEY_GENERATION_FAILED")
        test_fingerprint = fingerprint(home)
        for primary in primaries:
            relative = primary.relative_to(repository)
            signed_primary = signed_repository / relative
            signature = signed_primary.with_name(signed_primary.name + ".asc")
            signed = run(
                [
                    "gpg",
                    "--batch",
                    "--yes",
                    "--homedir",
                    str(home),
                    "--local-user",
                    test_fingerprint,
                    "--armor",
                    "--detach-sign",
                    "--output",
                    str(signature),
                    str(signed_primary),
                ]
            )
            if signed.returncode != 0:
                raise RuntimeError(f"EPHEMERAL_SIGNING_FAILED:{relative.as_posix()}")
            checked = run(
                [
                    "gpg",
                    "--batch",
                    "--homedir",
                    str(home),
                    "--status-fd",
                    "1",
                    "--verify",
                    str(signature),
                    str(signed_primary),
                ]
            )
            if checked.returncode != 0 or f"[GNUPG:] VALIDSIG {test_fingerprint} " not in checked.stdout:
                raise RuntimeError(f"EPHEMERAL_SIGNATURE_VERIFY_FAILED:{relative.as_posix()}")
            if digest(signed_primary, "sha256") != digest(primary, "sha256"):
                raise RuntimeError(f"PRIMARY_CHANGED_DURING_SIGNING:{relative.as_posix()}")
            verified.append(relative.as_posix())
        run(["gpgconf", "--homedir", str(home), "--kill", "gpg-agent"])

    bundle = output / "macroparadise-central-bundle-EPHEMERAL-TEST-ONLY.zip"
    write_deterministic_zip(signed_repository, bundle)
    proof: dict[str, object] = {
        "schema": "macroparadise-ephemeral-signing-proof-v1",
        "source_identity": manifest["source_identity"],
        "primary_manifest_sha256": manifest["primary_manifest_sha256"],
        "signer": {
            "kind": "EPHEMERAL_TEST_ONLY",
            "fingerprint": test_fingerprint,
            "release_authority": False,
        },
        "signed_primary_count": len(verified),
        "signed_primary_paths": sorted(verified),
        "all_signatures_verified": len(verified) == EXPECTED_PRIMARY_COUNT,
        "primary_bytes_unchanged": True,
        "secret_key_material_retained": False,
        "bundle": {
            "filename": bundle.name,
            "sha256": digest(bundle, "sha256"),
            "classification": "EPHEMERAL_TEST_ONLY_NOT_FOR_UPLOAD",
        },
        "remote_actions": "NONE",
    }
    (output / "EPHEMERAL_SIGNING_PROOF.json").write_text(
        json.dumps(proof, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return proof


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("repository", type=Path)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    try:
        proof = rehearse(args.repository.resolve(), args.manifest.resolve(), args.output.resolve())
    except (OSError, ValueError, RuntimeError, json.JSONDecodeError) as error:
        if args.output.exists():
            shutil.rmtree(args.output)
        print(str(error), file=sys.stderr)
        print(BLOCKED, file=sys.stderr)
        return 3
    print(f"{PASS} signed_primary_count={proof['signed_primary_count']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
