package macroparadise.sbt

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest

import munit.FunSuite

final class ArtifactIdentitySpec extends FunSuite {
  test("identity hashes every marker and the complete ordered handler classpath") {
    withArtifacts { root =>
      val marker = artifact(root, "marker.jar", "marker")
      val handler = artifact(root, "handler.jar", "handler")
      val dependency = artifact(root, "dependency.jar", "dependency-v1")

      val result = ArtifactIdentity.derive(
        Seq(LabelledArtifact("marker", marker.toFile)),
        Seq(
          LabelledArtifact("handler", handler.toFile),
          LabelledArtifact("runtime-dependency", dependency.toFile)
        )
      )

      val manifest =
        "marker\tmarker\t" + sha256("marker") + "\n" +
          "handler\t0000:handler\t" + sha256("handler") + "\n" +
          "handler\t0001:runtime-dependency\t" + sha256("dependency-v1") + "\n"
      assertEquals(result.manifest, manifest)
      assertEquals(result.identity, sha256(manifest))
      assertEquals(result.handlerClasspath.map(_.label), Vector("handler", "runtime-dependency"))
    }
  }

  test("dependency-only byte changes alter the supported identity but not the old primary-only control") {
    withArtifacts { root =>
      val marker = artifact(root, "marker.jar", "marker")
      val handler = artifact(root, "handler.jar", "handler")
      val dependency = artifact(root, "dependency.jar", "dependency-v1")
      val markerInput = Seq(LabelledArtifact("marker", marker.toFile))
      val handlerInput = Seq(
        LabelledArtifact("handler", handler.toFile),
        LabelledArtifact("runtime-dependency", dependency.toFile)
      )
      val before = ArtifactIdentity.derive(markerInput, handlerInput).identity
      val oldBefore = oldPrimaryOnlyIdentity(marker, handler)

      Files.write(dependency, "dependency-v2".getBytes(StandardCharsets.UTF_8))

      val after = ArtifactIdentity.derive(markerInput, handlerInput).identity
      val oldAfter = oldPrimaryOnlyIdentity(marker, handler)
      assertNotEquals(after, before)
      assertEquals(oldAfter, oldBefore)
    }
  }

  test("real paths are stable and duplicate paths collapse at first position within one role") {
    withArtifacts { root =>
      val marker = artifact(root, "marker.jar", "marker")
      val handler = artifact(root, "handler.jar", "handler")
      val alias = root.resolve("handler-alias.jar")
      Files.createSymbolicLink(alias, handler.getFileName)

      val result = ArtifactIdentity.derive(
        Seq(LabelledArtifact("marker", marker.toFile)),
        Seq(
          LabelledArtifact("primary", alias.toFile),
          LabelledArtifact("ignored-duplicate", handler.toFile)
        )
      )

      assertEquals(result.handlerClasspath.map(_.label), Vector("primary"))
      assertEquals(result.handlerClasspath.head.file.toPath, handler.toRealPath())
    }
  }

  test("conflicting labels, cross-role reuse, missing inputs, and non-JAR inputs fail closed") {
    withArtifacts { root =>
      val first = artifact(root, "first.jar", "first")
      val second = artifact(root, "second.jar", "second")
      val text = artifact(root, "not-a-jar.txt", "text")

      interceptMessage[IllegalArgumentException]("logical label 'same' resolves to conflicting files") {
        ArtifactIdentity.derive(
          Seq(LabelledArtifact("same", first.toFile), LabelledArtifact("same", second.toFile)),
          Seq(LabelledArtifact("handler", second.toFile))
        )
      }
      interceptMessage[IllegalArgumentException]("artifact is reused across marker and handler roles") {
        ArtifactIdentity.derive(
          Seq(LabelledArtifact("marker", first.toFile)),
          Seq(LabelledArtifact("handler", first.toFile))
        )
      }
      interceptMessage[IllegalArgumentException]("artifact does not exist") {
        ArtifactIdentity.derive(
          Seq(LabelledArtifact("marker", root.resolve("missing.jar").toFile)),
          Seq(LabelledArtifact("handler", second.toFile))
        )
      }
      interceptMessage[IllegalArgumentException]("artifact must be a regular .jar file") {
        ArtifactIdentity.derive(
          Seq(LabelledArtifact("marker", text.toFile)),
          Seq(LabelledArtifact("handler", second.toFile))
        )
      }
    }
  }

  test("empty marker and handler roles fail closed") {
    withArtifacts { root =>
      val marker = artifact(root, "marker.jar", "marker")
      val handler = artifact(root, "handler.jar", "handler")
      interceptMessage[IllegalArgumentException]("marker role is empty") {
        ArtifactIdentity.derive(Seq.empty, Seq(LabelledArtifact("handler", handler.toFile)))
      }
      interceptMessage[IllegalArgumentException]("handler role is empty") {
        ArtifactIdentity.derive(Seq(LabelledArtifact("marker", marker.toFile)), Seq.empty)
      }
    }
  }

  private def withArtifacts(body: Path => Unit): Unit = {
    val root = Files.createTempDirectory("macroparadise-artifact-identity")
    try body(root)
    finally {
      val entries = Files.walk(root)
      try entries.sorted(java.util.Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
      finally entries.close()
    }
  }

  private def artifact(root: Path, name: String, bytes: String): Path =
    Files.write(root.resolve(name), bytes.getBytes(StandardCharsets.UTF_8))

  private def oldPrimaryOnlyIdentity(marker: Path, handler: Path): String =
    sha256("marker=" + sha256(Files.readAllBytes(marker)) + "\nhandler=" + sha256(Files.readAllBytes(handler)) + "\n")

  private def sha256(value: String): String =
    sha256(value.getBytes(StandardCharsets.UTF_8))

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(value => f"${value & 0xff}%02x").mkString
}
