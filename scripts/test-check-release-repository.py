#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import json
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CHECKER = ROOT / "scripts/check-release-repository.py"
GROUP_PATH = Path("com/github/dmytromitin")
VERSION = "0.1.0"
SCALA_VERSION = "3.8.5-RC1-bin-20260405-9478256-NIGHTLY"
MODULES = (
    f"macroparadise-scala3-plugin-api_{SCALA_VERSION}",
    f"macroparadise-scala3-plugin_{SCALA_VERSION}",
)


def digest(path: Path, algorithm: str) -> str:
    value = hashlib.new(algorithm)
    value.update(path.read_bytes())
    return value.hexdigest()


class ReleaseRepositoryCheckerTest(unittest.TestCase):
    def fixture(self, root: Path) -> Path:
        project = root / "project"
        repository = root / "repository"
        project.mkdir()
        (project / "LICENSE").write_text("Apache fixture\n", encoding="utf-8")
        for module in MODULES:
            directory = repository / GROUP_PATH / module / VERSION
            directory.mkdir(parents=True)
            base = f"{module}-{VERSION}"
            pom = directory / f"{base}.pom"
            pom.write_text(
                f"""<project><modelVersion>4.0.0</modelVersion>
  <groupId>com.github.dmytromitin</groupId><artifactId>{module}</artifactId><version>{VERSION}</version>
  <name>Macro Paradise</name><description>Exact compiler plugin</description><url>https://github.com/DmytroMitin/macroparadise-scala3</url>
  <licenses><license><name>Apache-2.0</name><url>https://www.apache.org/licenses/LICENSE-2.0</url><distribution>repo</distribution></license></licenses>
  <scm><url>https://github.com/DmytroMitin/macroparadise-scala3</url><connection>scm:git:https://github.com/DmytroMitin/macroparadise-scala3.git</connection></scm>
  <developers><developer><id>DmytroMitin</id><name>Dmytro Mitin</name><email>dmitin3@gmail.com</email><url>https://github.com/DmytroMitin</url></developer></developers>
  <dependencies><dependency><groupId>org.scala-lang</groupId><artifactId>scala3-compiler_3</artifactId><version>{SCALA_VERSION}</version></dependency></dependencies>
</project>\n""",
                encoding="utf-8",
            )
            for suffix in (".jar", "-sources.jar", "-javadoc.jar"):
                with zipfile.ZipFile(directory / f"{base}{suffix}", "w") as archive:
                    archive.writestr("META-INF/LICENSE", (project / "LICENSE").read_bytes())
                    archive.writestr("fixture.txt", suffix)
            for deployable in (pom, *(directory / f"{base}{suffix}" for suffix in (".jar", "-sources.jar", "-javadoc.jar"))):
                for algorithm in ("md5", "sha1", "sha256", "sha512"):
                    deployable.with_name(deployable.name + f".{algorithm}").write_text(
                        digest(deployable, algorithm) + "\n", encoding="ascii"
                    )
        return repository

    def run_checker(self, project: Path, repository: Path) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [
                "python3",
                str(CHECKER),
                str(project),
                str(repository),
                "--source-identity",
                "test-source",
                "--json",
                str(repository.parent / "manifest.json"),
                "--markdown",
                str(repository.parent / "manifest.md"),
            ],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            check=False,
        )

    def test_exact_unsigned_candidate_is_accepted_and_manifested(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            repository = self.fixture(root)
            result = self.run_checker(root / "project", repository)
            self.assertEqual(result.returncode, 0, result.stdout)
            manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
            self.assertEqual(manifest["candidate_version"], VERSION)
            self.assertEqual(len(manifest["coordinates"]), 2)
            self.assertEqual(manifest["signing"]["status"], "OWNER_GATED_NOT_SIGNED")
            self.assertTrue(manifest["assertions"]["all_checksums_verified"])

    def test_missing_documentation_jar_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            repository = self.fixture(root)
            module = MODULES[0]
            (repository / GROUP_PATH / module / VERSION / f"{module}-{VERSION}-javadoc.jar").unlink()
            result = self.run_checker(root / "project", repository)
            self.assertEqual(result.returncode, 3)
            self.assertIn("DEPLOYABLE_MISSING", result.stdout)

    def test_unexpected_signature_is_rejected_without_release_authorization(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            repository = self.fixture(root)
            module = MODULES[0]
            directory = repository / GROUP_PATH / module / VERSION
            (directory / f"{module}-{VERSION}.jar.asc").write_text("not authorized\n", encoding="utf-8")
            result = self.run_checker(root / "project", repository)
            self.assertEqual(result.returncode, 3)
            self.assertIn("UNAUTHORIZED_SIGNATURE_PRESENT", result.stdout)

    def test_self_contained_plugin_rejects_plugin_api_pom_dependency(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            repository = self.fixture(root)
            module = MODULES[1]
            pom = repository / GROUP_PATH / module / VERSION / f"{module}-{VERSION}.pom"
            rendered = pom.read_text(encoding="utf-8").replace(
                "</dependencies>",
                f"<dependency><groupId>com.github.dmytromitin</groupId><artifactId>{MODULES[0]}</artifactId><version>{VERSION}</version></dependency></dependencies>",
            )
            pom.write_text(rendered, encoding="utf-8")
            for algorithm in ("md5", "sha1", "sha256", "sha512"):
                pom.with_name(pom.name + f".{algorithm}").write_text(
                    digest(pom, algorithm) + "\n", encoding="ascii"
                )
            result = self.run_checker(root / "project", repository)
            self.assertEqual(result.returncode, 3)
            self.assertIn("POM_PLUGIN_API_DEPENDENCY_UNNECESSARY", result.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
