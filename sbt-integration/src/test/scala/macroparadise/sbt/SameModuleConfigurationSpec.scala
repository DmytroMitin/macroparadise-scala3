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

  test("exactly two external bindings emit sorted repeated options and one aggregate identity") {
    withSources { root =>
      source(root, "demo/MarkerA.scala", "marker-a")
      source(root, "demo/HandlerA.scala", "handler-a")
      source(root, "demo/MarkerB.scala", "marker-b")
      source(root, "demo/HandlerB.scala", "handler-b")
      val output = Files.createDirectories(root.resolve("classes"))
      val bindingA = SameModuleHandlerBinding(
        "demo.markerA",
        "demo.HandlerA",
        LabelledSource("a-marker-source", "demo/MarkerA.scala"),
        LabelledSource("a-handler-source", "demo/HandlerA.scala")
      )
      val bindingB = SameModuleHandlerBinding(
        "demo.markerB",
        "demo.HandlerB",
        LabelledSource("b-marker-source", "demo/MarkerB.scala"),
        LabelledSource("b-handler-source", "demo/HandlerB.scala")
      )

      val derived = SameModuleConfiguration
        .deriveSelected(root.toFile, output.toFile, None, Seq(bindingB, bindingA), None)
        .toOption
        .get

      assertEquals(
        derived.compilerOptions,
        Vector(
          "-Xplugin-require:macroparadise",
          "-P:macroparadise:handlerClasspath=" + output.toRealPath().toString,
          "-P:macroparadise:sameModuleHandler=demo.markerA:demo.HandlerA:demo/MarkerA.scala:demo/HandlerA.scala",
          "-P:macroparadise:sameModuleHandler=demo.markerB:demo.HandlerB:demo/MarkerB.scala:demo/HandlerB.scala",
          "-P:macroparadise:sameModuleSourceIdentity=sha256:" + derived.sourceIdentity.identity
        )
      )
      assertEquals(
        derived.sourceIdentity.sources.map(_.label),
        Vector("a-handler-source", "a-marker-source", "b-handler-source", "b-marker-source")
      )
      assertEquals(
        SameModuleConfiguration
          .deriveSelected(root.toFile, output.toFile, None, Seq(bindingA, bindingB), None)
          .map(_.compilerOptions),
        Right(derived.compilerOptions)
      )
    }
  }

  test("pair selection rejects family conflicts before cardinality or member validation") {
    withSources { root =>
      source(root, "demo/Marker.scala", "marker")
      source(root, "demo/Handler.scala", "handler")
      source(root, "demo/Embedded.scala", "embedded")
      val output = Files.createDirectories(root.resolve("classes"))
      val external = SameModuleHandlerBinding(
        "demo.marker",
        "demo.Handler",
        LabelledSource("marker", "demo/Marker.scala"),
        LabelledSource("handler", "demo/Handler.scala")
      )
      val invalidMember = external.copy(annotationName = "")
      val embedded = SameModuleEmbeddedBinding(
        "demo.embedded",
        LabelledSource("embedded", "demo/Embedded.scala")
      )

      assertEquals(
        SameModuleConfiguration.deriveSelected(
          root.toFile,
          output.toFile,
          Some(external),
          Seq(invalidMember),
          None
        ),
        Left("macroParadiseSameModuleBinding and macroParadiseSameModuleBindings are mutually exclusive")
      )
      assertEquals(
        SameModuleConfiguration.deriveSelected(
          root.toFile,
          output.toFile,
          None,
          Seq(invalidMember),
          Some(embedded)
        ),
        Left("same-module external and embedded binding modes are mutually exclusive")
      )
    }
  }

  test("pair selection rejects every cardinality except exactly two") {
    withSources { root =>
      source(root, "demo/Marker.scala", "marker")
      source(root, "demo/Handler.scala", "handler")
      val output = Files.createDirectories(root.resolve("classes"))
      val binding = SameModuleHandlerBinding(
        "demo.marker",
        "demo.Handler",
        LabelledSource("marker", "demo/Marker.scala"),
        LabelledSource("handler", "demo/Handler.scala")
      )

      assertEquals(
        SameModuleConfiguration.deriveSelected(root.toFile, output.toFile, None, Seq(binding), None),
        Left("macroParadiseSameModuleBindings requires exactly two entries; use macroParadiseSameModuleBinding for one external relationship")
      )
      assertEquals(
        SameModuleConfiguration.deriveSelected(root.toFile, output.toFile, None, Seq(binding, binding, binding), None),
        Left("macroParadiseSameModuleBindings supports exactly two external relationships")
      )
    }
  }

  test("pair validation rejects unqualified and duplicate annotation or handler identities") {
    withSources { root =>
      Seq("A", "B").foreach { suffix =>
        source(root, s"demo/Marker$suffix.scala", "marker")
        source(root, s"demo/Handler$suffix.scala", "handler")
      }
      val output = Files.createDirectories(root.resolve("classes"))
      def binding(suffix: String) = SameModuleHandlerBinding(
        s"demo.marker$suffix",
        s"demo.Handler$suffix",
        LabelledSource(s"$suffix-marker", s"demo/Marker$suffix.scala"),
        LabelledSource(s"$suffix-handler", s"demo/Handler$suffix.scala")
      )
      val a = binding("A")
      val b = binding("B")

      assertEquals(
        SameModuleConfiguration.deriveSelected(root.toFile, output.toFile, None, Seq(a.copy(annotationName = "markerA"), b), None),
        Left("macroParadiseSameModuleBindings annotation names must be qualified canonical identifier chains: markerA")
      )
      assertEquals(
        SameModuleConfiguration.deriveSelected(root.toFile, output.toFile, None, Seq(a, b.copy(annotationName = a.annotationName)), None),
        Left("duplicate same-module annotation names: demo.markerA")
      )
      assertEquals(
        SameModuleConfiguration.deriveSelected(root.toFile, output.toFile, None, Seq(a, b.copy(handlerClassName = a.handlerClassName)), None),
        Left("duplicate same-module handler class names: demo.HandlerA")
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

  test("exactly two embedded bindings emit sorted repeated options in both namespaces and one aggregate identity") {
    withSources { root =>
      source(root, "demo/ProducerA.scala", "producer-a")
      source(root, "demo/ProducerB.scala", "producer-b")
      val output = Files.createDirectories(root.resolve("classes"))
      val bindingA = SameModuleEmbeddedBinding(
        "demo.markerA",
        LabelledSource("a-producer", "demo/ProducerA.scala")
      )
      val bindingB = SameModuleEmbeddedBinding(
        "demo.markerB",
        LabelledSource("b-producer", "demo/ProducerB.scala")
      )

      val derived = SameModuleConfiguration
        .deriveSelected(root.toFile, output.toFile, None, Seq.empty, None, Seq(bindingB, bindingA))
        .toOption
        .get

      assertEquals(
        derived.compilerOptions,
        Vector(
          "-Xplugin-require:macroparadise",
          "-Xplugin-require:macroparadise-embedded-producer",
          "-P:macroparadise:handlerClasspath=" + output.toRealPath().toString,
          "-P:macroparadise:sameModuleEmbedded=demo.markerA:demo/ProducerA.scala",
          "-P:macroparadise:sameModuleEmbedded=demo.markerB:demo/ProducerB.scala",
          "-P:macroparadise:sameModuleSourceIdentity=sha256:" + derived.sourceIdentity.identity,
          "-P:macroparadise-embedded-producer:sameModuleEmbedded=demo.markerA:demo/ProducerA.scala",
          "-P:macroparadise-embedded-producer:sameModuleEmbedded=demo.markerB:demo/ProducerB.scala"
        )
      )
      assertEquals(derived.sourceIdentity.manifest.linesIterator.size, 2)
      assertEquals(
        SameModuleConfiguration
          .deriveSelected(root.toFile, output.toFile, None, Seq.empty, None, Seq(bindingA, bindingB))
          .map(_.compilerOptions),
        Right(derived.compilerOptions)
      )
    }
  }

  test("all sixteen family-presence rows select exactly one family before validating members") {
    withSources { root =>
      Seq("MarkerA", "HandlerA", "MarkerB", "HandlerB", "ProducerA", "ProducerB")
        .foreach(name => source(root, s"demo/$name.scala", name))
      val output = Files.createDirectories(root.resolve("classes"))
      val externalA = SameModuleHandlerBinding(
        "demo.markerA",
        "demo.HandlerA",
        LabelledSource("a-marker", "demo/MarkerA.scala"),
        LabelledSource("a-handler", "demo/HandlerA.scala")
      )
      val externalB = SameModuleHandlerBinding(
        "demo.markerB",
        "demo.HandlerB",
        LabelledSource("b-marker", "demo/MarkerB.scala"),
        LabelledSource("b-handler", "demo/HandlerB.scala")
      )
      val embeddedA = SameModuleEmbeddedBinding(
        "demo.embeddedA",
        LabelledSource("a-producer", "demo/ProducerA.scala")
      )
      val embeddedB = SameModuleEmbeddedBinding(
        "demo.embeddedB",
        LabelledSource("b-producer", "demo/ProducerB.scala")
      )

      (0 until 16).foreach { mask =>
        val e1 = if ((mask & 1) != 0) Some(externalA) else None
        val e2 = if ((mask & 2) != 0) Seq(externalA, externalB) else Seq.empty
        val i1 = if ((mask & 4) != 0) Some(embeddedA) else None
        val i2 = if ((mask & 8) != 0) Seq(embeddedA, embeddedB) else Seq.empty
        val result = SameModuleConfiguration.deriveSelected(
          root.toFile,
          output.toFile,
          e1,
          e2,
          i1,
          i2
        )
        assertEquals(result.isRight, Integer.bitCount(mask) == 1, clues(mask, result))
      }
    }
  }

  test("embedded pair cardinality and duplicate canonical annotations fail deterministically") {
    withSources { root =>
      source(root, "demo/ProducerA.scala", "producer-a")
      source(root, "demo/ProducerB.scala", "producer-b")
      val output = Files.createDirectories(root.resolve("classes"))
      val a = SameModuleEmbeddedBinding(
        "demo.markerA",
        LabelledSource("a-producer", "demo/ProducerA.scala")
      )
      val b = SameModuleEmbeddedBinding(
        "demo.markerB",
        LabelledSource("b-producer", "demo/ProducerB.scala")
      )

      assertEquals(
        SameModuleConfiguration.deriveSelected(root.toFile, output.toFile, None, Seq.empty, None, Seq(a)),
        Left("macroParadiseSameModuleEmbeddedBindings requires exactly two entries; use macroParadiseSameModuleEmbeddedBinding for one embedded relationship")
      )
      assertEquals(
        SameModuleConfiguration.deriveSelected(root.toFile, output.toFile, None, Seq.empty, None, Seq(a, b, b)),
        Left("macroParadiseSameModuleEmbeddedBindings supports exactly two embedded relationships")
      )
      val expected = Left("duplicate same-module annotation names: demo.markerA")
      assertEquals(
        SameModuleConfiguration.deriveSelected(root.toFile, output.toFile, None, Seq.empty, None, Seq(a, b.copy(annotationName = a.annotationName))),
        expected
      )
      assertEquals(
        SameModuleConfiguration.deriveSelected(root.toFile, output.toFile, None, Seq.empty, None, Seq(b.copy(annotationName = a.annotationName), a)),
        expected
      )
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
