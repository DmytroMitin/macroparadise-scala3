package macroparadise.sbt

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest

import munit.FunSuite

final class SourceIdentitySpec extends FunSuite {
  test("manifest is sorted by label and hashes exact source bytes") {
    withSources { root =>
      source(root, "demo/Marker.scala", "marker-v1")
      source(root, "demo/Handler.scala", "handler-v1")

      val result = SourceIdentity.derive(
        root.toFile,
        Seq(
          LabelledSource("marker-source", "demo/Marker.scala"),
          LabelledSource("handler-source", "demo/Handler.scala")
        )
      )

      val manifest =
        "handler-source\tdemo/Handler.scala\t" + sha256("handler-v1") + "\n" +
          "marker-source\tdemo/Marker.scala\t" + sha256("marker-v1") + "\n"
      assertEquals(result.manifest, manifest)
      assertEquals(result.identity, sha256(manifest))
      assertEquals(
        result.sources.map(source => source.label -> source.relativePath),
        Vector(
          "handler-source" -> "demo/Handler.scala",
          "marker-source" -> "demo/Marker.scala"
        )
      )
    }
  }

  test("duplicate labels fail closed") {
    withSources { root =>
      source(root, "demo/Marker.scala", "marker")
      source(root, "demo/Handler.scala", "handler")

      interceptMessage[IllegalArgumentException](
        "duplicate same-module source labels: source"
      ) {
        SourceIdentity.derive(
          root.toFile,
          Seq(
            LabelledSource("source", "demo/Marker.scala"),
            LabelledSource("source", "demo/Handler.scala")
          )
        )
      }
    }
  }

  test("duplicate normalized paths fail closed") {
    withSources { root =>
      source(root, "demo/Handler.scala", "handler")

      interceptMessage[IllegalArgumentException](
        "duplicate normalized same-module source paths: demo/Handler.scala"
      ) {
        SourceIdentity.derive(
          root.toFile,
          Seq(
            LabelledSource("first", "demo/Handler.scala"),
            LabelledSource("second", "demo/./Handler.scala")
          )
        )
      }
    }
  }

  test("empty configured source path fails closed") {
    withSources { root =>
      interceptMessage[IllegalArgumentException](
        "configured same-module source path must not be empty"
      ) {
        SourceIdentity.derive(
          root.toFile,
          Seq(LabelledSource("handler-source", ""))
        )
      }
    }
  }

  test("parent path escape fails before reading outside bytes") {
    withSources { root =>
      val outside = Files.createTempFile(root.getParent, "outside-source", ".scala")
      try {
        val relativeEscape = "../" + outside.getFileName.toString
        interceptMessage[IllegalArgumentException](
          "configured same-module source escapes the source root"
        ) {
          SourceIdentity.derive(
            root.toFile,
            Seq(LabelledSource("handler-source", relativeEscape))
          )
        }
      } finally Files.deleteIfExists(outside)
    }
  }

  test("absolute configured source path fails closed") {
    withSources { root =>
      val handler = source(root, "demo/Handler.scala", "handler")

      interceptMessage[IllegalArgumentException](
        "configured same-module source path must be relative"
      ) {
        SourceIdentity.derive(
          root.toFile,
          Seq(LabelledSource("handler-source", handler.toString))
        )
      }
    }
  }

  test("missing configured source fails closed") {
    withSources { root =>
      interceptMessage[IllegalArgumentException](
        "configured same-module source is missing: demo/Missing.scala"
      ) {
        SourceIdentity.derive(
          root.toFile,
          Seq(LabelledSource("handler-source", "demo/Missing.scala"))
        )
      }
    }
  }

  test("empty or manifest-breaking labels fail closed") {
    withSources { root =>
      source(root, "demo/Handler.scala", "handler")
      Seq("", "bad\tlabel", "bad\nlabel").foreach { label =>
        interceptMessage[IllegalArgumentException](
          "same-module source label must be nonempty and contain no tabs or line breaks"
        ) {
          SourceIdentity.derive(
            root.toFile,
            Seq(LabelledSource(label, "demo/Handler.scala"))
          )
        }
      }
    }
  }

  private def withSources(body: Path => Unit): Unit = {
    val root = Files.createTempDirectory("macroparadise-source-identity")
    try body(root)
    finally {
      val entries = Files.walk(root)
      try entries.sorted(java.util.Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
      finally entries.close()
    }
  }

  private def source(root: Path, relativePath: String, value: String): Path = {
    val path = root.resolve(relativePath)
    Files.createDirectories(path.getParent)
    Files.write(path, value.getBytes(StandardCharsets.UTF_8))
  }

  private def sha256(value: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(value.getBytes(StandardCharsets.UTF_8))
      .map(value => f"${value & 0xff}%02x")
      .mkString
}
