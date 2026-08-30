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
