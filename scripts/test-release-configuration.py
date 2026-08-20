#!/usr/bin/env python3
from __future__ import annotations

import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
GROUP = "com.github.dmytromitin"
VERSION = "0.1.0"
SCALA_VERSION = "3.8.5-RC1-bin-20260405-9478256-NIGHTLY"
PLUGIN_ID = "macroparadise"


def project_section(build: str, project_id: str) -> str:
    start = build.index(f"lazy val {project_id} =")
    end = build.find("\nlazy val ", start + 1)
    return build[start:] if end < 0 else build[start:end]


class ReleaseConfigurationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.build = (ROOT / "build.sbt").read_text(encoding="utf-8")

    def test_candidate_coordinate_and_toolchain_are_exact(self) -> None:
        self.assertIn(f'ThisBuild / version := "{VERSION}"', self.build)
        self.assertIn(f'ThisBuild / organization := "{GROUP}"', self.build)
        self.assertIn(f'ThisBuild / organizationName := "{GROUP}"', self.build)
        self.assertIn('ThisBuild / versionScheme := Some("early-semver")', self.build)
        self.assertIn(f'ThisBuild / scalaVersion := "{SCALA_VERSION}"', self.build)
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
        source = ROOT / "plugin/src/main/scala/macroparadise/MacroParadisePlugin.scala"
        self.assertTrue(source.is_file())
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

    def test_public_docs_distinguish_local_candidate_from_central_release(self) -> None:
        readme = (ROOT / "README.md").read_text(encoding="utf-8")
        getting_started = (ROOT / "docs/GETTING_STARTED.md").read_text(encoding="utf-8")
        authoring = (ROOT / "docs/EXTERNAL_HANDLER_AUTHORING.md").read_text(encoding="utf-8")
        for text in (readme, getting_started):
            self.assertIn(f'"{GROUP}" % "macroparadise-scala3-plugin" % "{VERSION}"', text)
            self.assertIn("CrossVersion.full", text)
            self.assertIn("publishLocal", text)
            self.assertIn("not available from Maven Central", text)
        self.assertIn(f'"{GROUP}" % "macroparadise-scala3-plugin-api" % "{VERSION}"', authoring)
        self.assertIn(".cross(CrossVersion.full)", authoring)
        self.assertIn(f"-P:{PLUGIN_ID}:handlerClasspath=", authoring)

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
        self.assertIn("pluginApi/publish", script)
        self.assertIn("plugin/publish", script)
        self.assertIn("check-release-repository.py", script)
        for forbidden in ("publishSigned", "sonatype", "centralPortal", "git tag", "gh release"):
            self.assertNotIn(forbidden, script)


if __name__ == "__main__":
    unittest.main(verbosity=2)
