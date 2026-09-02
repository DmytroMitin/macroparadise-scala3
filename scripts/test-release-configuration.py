#!/usr/bin/env python3
from __future__ import annotations

import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
GROUP = "com.github.dmytromitin"
RELEASE_VERSION = "0.1.0"
CANDIDATE_VERSION = "0.1.1"
DEVELOPMENT_VERSION = "0.1.1-SNAPSHOT"
RELEASE_SCALA_VERSION = "3.8.4"
SUPPORTED_SCALA_VERSIONS = ("3.3.8", "3.8.4", "3.9.0")
PLUGIN_ID = "macroparadise"


def project_section(build: str, project_id: str) -> str:
    start = build.index(f"lazy val {project_id} =")
    end = build.find("\nlazy val ", start + 1)
    return build[start:] if end < 0 else build[start:end]


class ReleaseConfigurationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.build = (ROOT / "build.sbt").read_text(encoding="utf-8")

    def test_development_coordinate_and_toolchain_are_exact(self) -> None:
        self.assertIn(f'ThisBuild / version := "{DEVELOPMENT_VERSION}"', self.build)
        self.assertIn(f'ThisBuild / organization := "{GROUP}"', self.build)
        self.assertIn(f'ThisBuild / organizationName := "{GROUP}"', self.build)
        self.assertIn('ThisBuild / versionScheme := Some("early-semver")', self.build)
        self.assertIn(f'ThisBuild / scalaVersion := "{RELEASE_SCALA_VERSION}"', self.build)
        self.assertIn(
            'ThisBuild / crossScalaVersions := Seq("3.3.8", "3.8.4", "3.9.0")',
            self.build,
        )
        self.assertNotIn("Resolver.scalaNightlyRepository", self.build)
        self.assertNotIn("io.github.dmytromitin", self.build)
        self.assertEqual(
            (ROOT / "project/build.properties").read_text(encoding="utf-8").strip(),
            "sbt.version=1.12.15",
        )

    def test_only_plugin_and_plugin_api_are_publishable(self) -> None:
        for project_id in ("plugin", "pluginApi"):
            section = project_section(self.build, project_id)
            self.assertIn(".settings(selectedPublicationSettings)", section, project_id)
            self.assertIn("crossVersion := CrossVersion.full", section, project_id)
        for project_id in (
            "root",
            "legacyMetadataMarkerFixture",
            "pluginTestMarkers",
            "pluginTestHandlers",
            "pluginTests",
        ):
            self.assertIn("publish / skip := true", project_section(self.build, project_id), project_id)
        self.assertIn("verifyPublicProductPublicationPolicy", self.build)
        self.assertNotIn("verifyPublicProductPublishingDisabled", self.build)

    def test_remote_publication_is_fail_closed(self) -> None:
        self.assertNotIn("ThisBuild / publishTo :=", self.build)
        self.assertNotIn("ThisBuild / credentials :=", self.build)
        self.assertIn("publishMavenStyle := true", self.build)
        self.assertIn("pomIncludeRepository := (_ => false)", self.build)

    def test_plugin_identity_is_product_facing_and_prototype_free(self) -> None:
        adapters = (
            ROOT / "plugin/src/main/scala-3.3.8/macroparadise/MacroParadisePlugin338.scala",
            ROOT / "plugin/src/main/scala-3.8.4/macroparadise/MacroParadisePlugin384.scala",
            ROOT / "plugin/src/main/scala-3.9.0/macroparadise/MacroParadisePlugin390.scala",
        )
        for source in adapters:
            self.assertTrue(source.is_file(), source)
            self.assertIn(f'val name: String = "{PLUGIN_ID}"', source.read_text(encoding="utf-8"))
        descriptor = (ROOT / "plugin/src/main/resources/plugin.properties").read_text(encoding="utf-8").strip()
        self.assertEqual(descriptor, "pluginClass=macroparadise.MacroParadisePlugin")
        prototype = "hello" + "World"
        scanned = [ROOT / "build.sbt", ROOT / "README.md"]
        scanned += list((ROOT / "docs").rglob("*.md"))
        scanned += list((ROOT / "examples").rglob("*"))
        scanned += list((ROOT / "plugin").rglob("*"))
        scanned += list((ROOT / "project").rglob("*.scala"))
        offenders = [
            str(path.relative_to(ROOT))
            for path in scanned
            if path.is_file()
            and "target" not in path.relative_to(ROOT).parts
            and prototype in path.read_text(encoding="utf-8", errors="replace")
        ]
        self.assertEqual(offenders, [])

    def test_public_docs_distinguish_immutable_release_from_unreleased_main(self) -> None:
        readme = (ROOT / "README.md").read_text(encoding="utf-8")
        getting_started = (ROOT / "docs/GETTING_STARTED.md").read_text(encoding="utf-8")
        authoring = (ROOT / "docs/EXTERNAL_HANDLER_AUTHORING.md").read_text(encoding="utf-8")
        for text in (readme, getting_started):
            self.assertIn(
                f'"{GROUP}" % "macroparadise-scala3-plugin" % "{RELEASE_VERSION}"',
                text,
            )
            self.assertIn(
                f'"{GROUP}" % "macroparadise-scala3-plugin" % "{DEVELOPMENT_VERSION}"',
                text,
            )
            self.assertIn("CrossVersion.full", text)
            self.assertIn("publishLocal", text)
            self.assertIn("available", text)
            self.assertIn("Maven Central", text)
        self.assertIn(
            f'"{GROUP}" % "macroparadise-scala3-plugin-api" % "{DEVELOPMENT_VERSION}"',
            authoring,
        )
        self.assertIn(".cross(CrossVersion.full)", authoring)
        self.assertIn(f"-P:{PLUGIN_ID}:handlerClasspath=", authoring)

    def test_release_rehearsal_targets_the_0_1_1_three_line_identity(self) -> None:
        rehearsal = (ROOT / "scripts/rehearse-local-release.sh").read_text(encoding="utf-8")
        self.assertIn(f'version="{CANDIDATE_VERSION}"', rehearsal)
        self.assertIn('scala_versions=("3.3.8" "3.8.4" "3.9.0")', rehearsal)
        self.assertIn('sbt_module="sbt-macroparadise_2.12_1.0"', rehearsal)
        for version in SUPPORTED_SCALA_VERSIONS:
            self.assertIn(version, self.build)

    def test_plugin_is_self_contained_without_a_plugin_api_pom_dependency(self) -> None:
        plugin = project_section(self.build, "plugin")
        self.assertIn('.dependsOn(pluginApi % "compile-internal"', plugin)
        self.assertIn('startsWith("paradise3/api/")', plugin)
        checker = (ROOT / "scripts/check-release-repository.py").read_text(encoding="utf-8")
        self.assertIn("POM_PLUGIN_API_DEPENDENCY_UNNECESSARY", checker)

    def test_release_rehearsal_is_task_local_and_unsigned(self) -> None:
        script_path = ROOT / "scripts/rehearse-local-release.sh"
        self.assertTrue(script_path.is_file())
        script = script_path.read_text(encoding="utf-8")
        self.assertIn("Resolver.file", script)
        self.assertIn('"pluginApi/clean"', script)
        self.assertIn('"plugin/clean"', script)
        self.assertIn("pluginApi/publish", script)
        self.assertIn("plugin/publish", script)
        self.assertIn("check-release-repository.py", script)
        for forbidden in ("publishSigned", "sonatype", "centralPortal", "git tag", "gh release"):
            self.assertNotIn(forbidden, script)

        signing_path = ROOT / "scripts/rehearse-release-signing.py"
        self.assertTrue(signing_path.is_file())
        signing = signing_path.read_text(encoding="utf-8")
        self.assertIn("EPHEMERAL_TEST_ONLY_NOT_FOR_UPLOAD", signing)
        self.assertIn("secret_key_material_retained", signing)
        self.assertNotIn("publishSigned", signing)

        for test_script in (
            "test-release-configuration.py",
            "test-check-release-repository.py",
            "test-rehearse-release-signing.py",
        ):
            self.assertIn(test_script, self.build)


if __name__ == "__main__":
    unittest.main(verbosity=2)
