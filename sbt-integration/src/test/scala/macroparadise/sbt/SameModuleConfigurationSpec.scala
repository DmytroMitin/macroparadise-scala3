package macroparadise.sbt

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import munit.FunSuite

final class SameModuleConfigurationSpec extends FunSuite {
  test("one explicit different-file binding emits separate source identity and relationship options") {
    withSources { root =>
      source(root, "demo/Marker.scala", "marker")
      source(root, "demo/Handler.scala", "handler")
      val output = Files.createDirectories(root.resolve("classes"))
      val binding = SameModuleHandlerBinding(
        annotationName = "demo.sameModuleDebug",
        handlerClassName = "demo.SameModuleDebugExpander",
        markerSource = LabelledSource("marker-source", "demo/Marker.scala"),
        handlerSource = LabelledSource("handler-source", "demo/Handler.scala")
      )

      val derived = SameModuleConfiguration.derive(root.toFile, output.toFile, binding)

      assertEquals(
        derived.compilerOptions,
        Vector(
          "-Xplugin-require:macroparadise",
          "-P:macroparadise:handlerClasspath=" + output.toRealPath().toString,
          "-P:macroparadise:sameModuleHandler=demo.sameModuleDebug:demo.SameModuleDebugExpander:demo/Marker.scala:demo/Handler.scala",
          "-P:macroparadise:sameModuleSourceIdentity=sha256:" + derived.sourceIdentity.identity
        )
      )
      assertEquals(
        derived.sourceIdentity.sources.map(_.label),
        Vector("handler-source", "marker-source")
      )
    }
  }

  test("one embedded binding emits one producer source identity and both private mode options") {
    withSources { root =>
      source(root, "demo/EmbeddedAnnotations.scala", "producer-v1")
      val output = Files.createDirectories(root.resolve("classes"))
      val binding = SameModuleEmbeddedBinding(
        annotationName = "demo.sameModuleGreeting",
        producerSource = LabelledSource(
          "embedded-producer-source",
          "demo/EmbeddedAnnotations.scala"
        )
      )

      val derived = SameModuleConfiguration.deriveEmbedded(root.toFile, output.toFile, binding)

      assertEquals(
        derived.compilerOptions,
        Vector(
          "-Xplugin-require:macroparadise",
          "-Xplugin-require:macroparadise-embedded-producer",
          "-P:macroparadise:handlerClasspath=" + output.toRealPath().toString,
          "-P:macroparadise:sameModuleEmbedded=demo.sameModuleGreeting:demo/EmbeddedAnnotations.scala",
          "-P:macroparadise:sameModuleSourceIdentity=sha256:" + derived.sourceIdentity.identity,
          "-P:macroparadise-embedded-producer:sameModuleEmbedded=demo.sameModuleGreeting:demo/EmbeddedAnnotations.scala"
        )
      )
      assertEquals(
        derived.sourceIdentity.sources,
        Vector(LabelledSource("embedded-producer-source", "demo/EmbeddedAnnotations.scala"))
      )
      assertEquals(derived.sourceIdentity.manifest.linesIterator.size, 1)
    }
  }

  test("exactly one external or embedded binding mode is required") {
    withSources { root =>
      source(root, "demo/Marker.scala", "marker")
      source(root, "demo/Handler.scala", "handler")
      source(root, "demo/EmbeddedAnnotations.scala", "producer")
      val output = Files.createDirectories(root.resolve("classes"))
      val external = SameModuleHandlerBinding(
        "demo.sameModuleDebug",
        "demo.SameModuleDebugExpander",
        LabelledSource("marker-source", "demo/Marker.scala"),
        LabelledSource("handler-source", "demo/Handler.scala")
      )
      val embedded = SameModuleEmbeddedBinding(
        "demo.sameModuleGreeting",
        LabelledSource("embedded-producer-source", "demo/EmbeddedAnnotations.scala")
      )

      assert(SameModuleConfiguration.deriveSelected(root.toFile, output.toFile, None, None).isLeft)
      assert(SameModuleConfiguration.deriveSelected(root.toFile, output.toFile, Some(external), Some(embedded)).isLeft)
      assertEquals(
        SameModuleConfiguration
          .deriveSelected(root.toFile, output.toFile, Some(external), None)
          .map(_.compilerOptions),
        Right(SameModuleConfiguration.derive(root.toFile, output.toFile, external).compilerOptions)
      )
      assertEquals(
        SameModuleConfiguration
          .deriveSelected(root.toFile, output.toFile, None, Some(embedded))
          .map(_.compilerOptions),
        Right(SameModuleConfiguration.deriveEmbedded(root.toFile, output.toFile, embedded).compilerOptions)
      )
    }
  }

  private def withSources(body: Path => Unit): Unit = {
    val root = Files.createTempDirectory("macroparadise-same-module-configuration")
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
}
