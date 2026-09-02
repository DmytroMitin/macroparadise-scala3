#!/usr/bin/env python3
from __future__ import annotations

import json
import importlib.util
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.dont_write_bytecode = True

ROOT = Path(__file__).resolve().parents[1]
CHECKER = ROOT / "scripts/check-release-repository.py"
REHEARSAL = ROOT / "scripts/rehearse-release-signing.py"
SPEC = importlib.util.spec_from_file_location("release_repository_fixture", ROOT / "scripts/test-check-release-repository.py")
assert SPEC is not None and SPEC.loader is not None
FIXTURE_MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(FIXTURE_MODULE)


class ReleaseSigningRehearsalTest(unittest.TestCase):
    def prepare(self, root: Path) -> tuple[Path, Path, Path]:
        repository = FIXTURE_MODULE.ReleaseRepositoryCheckerTest().fixture(root)
        manifest = root / "manifest.json"
        result = subprocess.run(
            [
                "python3",
                str(CHECKER),
                str(root / "project"),
                str(repository),
                "--source-identity",
                "test-source",
                "--json",
                str(manifest),
                "--markdown",
                str(root / "manifest.md"),
            ],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            check=False,
        )
        self.assertEqual(result.returncode, 0, result.stdout)
        return repository, manifest, root / "signing-output"

    def run_rehearsal(self, repository: Path, manifest: Path, output: Path) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            ["python3", str(REHEARSAL), str(repository), str(manifest), str(output)],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            check=False,
        )

    def test_ephemeral_signer_proves_all_slots_without_changing_primaries_or_retaining_secret_material(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            repository, manifest, output = self.prepare(root)
            result = self.run_rehearsal(repository, manifest, output)
            self.assertEqual(result.returncode, 0, result.stdout)
            self.assertIn("EPHEMERAL_RELEASE_SIGNING_DRY_RUN_PASS", result.stdout)

            proof = json.loads((output / "EPHEMERAL_SIGNING_PROOF.json").read_text(encoding="utf-8"))
            unsigned = json.loads(manifest.read_text(encoding="utf-8"))
            self.assertEqual(proof["schema"], "macroparadise-ephemeral-signing-proof-v1")
            self.assertEqual(proof["primary_manifest_sha256"], unsigned["primary_manifest_sha256"])
            self.assertEqual(proof["signer"]["kind"], "EPHEMERAL_TEST_ONLY")
            self.assertEqual(len(proof["signer"]["fingerprint"]), 40)
            self.assertEqual(proof["signed_primary_count"], 28)
            self.assertTrue(proof["all_signatures_verified"])
            self.assertFalse(proof["secret_key_material_retained"])
            self.assertEqual(len(list((output / "signed-repository").rglob("*.asc"))), 28)
            self.assertTrue((output / "macroparadise-central-bundle-EPHEMERAL-TEST-ONLY.zip").is_file())
            self.assertFalse(any(path.name in {"private-keys-v1.d", "secring.gpg"} for path in output.rglob("*")))

    def test_primary_byte_drift_fails_before_signing(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            repository, manifest, output = self.prepare(root)
            primary = next(repository.rglob("*.pom"))
            primary.write_bytes(primary.read_bytes() + b"drift")
            result = self.run_rehearsal(repository, manifest, output)
            self.assertEqual(result.returncode, 3, result.stdout)
            self.assertIn("PRIMARY_MANIFEST_MISMATCH", result.stdout)
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main(verbosity=2)
