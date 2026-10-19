package macroparadise

import DeferredSameModuleHandlerSupport.*

class DeferredSameModuleHandlerSupportSpec extends munit.FunSuite:
  test("normalizes separators and redundant path segments") {
    assertEquals(
      normalizePath("C:\\repo\\src\\.\\main\\scala\\demo\\..\\demo\\Handler.scala"),
      "C:/repo/src/main/scala/demo/Handler.scala"
    )
  }

  test("rejects basename-only source identity") {
    assert(SourceIdentity.parse("Handler.scala").isLeft)
  }

  test("normalizes an explicit relative source identity") {
    assertEquals(
      SourceIdentity.parse("demo/./Handler.scala").map(_.value),
      Right("demo/Handler.scala")
    )
  }

  test("matches an identity as an exact normalized suffix") {
    val identity = SourceIdentity.parse("demo/Handler.scala").toOption.get
    assertEquals(
      resolveDependency(
        identity,
        List(
          "/workspace/src/main/scala/demo/Handler.scala",
          "/workspace/src/test/scala/other/Handler.scala"
        )
      ),
      DependencyResolution.Unique("workspace/src/main/scala/demo/Handler.scala")
    )
  }

  test("does not accept a basename-only false positive") {
    val identity = SourceIdentity.parse("demo/Handler.scala").toOption.get
    assertEquals(
      resolveDependency(identity, List("/workspace/other/Handler.scala")),
      DependencyResolution.Missing
    )
  }

  test("reports zero source matches") {
    val identity = SourceIdentity.parse("demo/Missing.scala").toOption.get
    assertEquals(
      resolveDependency(identity, List("/workspace/demo/Other.scala")),
      DependencyResolution.Missing
    )
  }

  test("reports multiple normalized source matches") {
    val identity = SourceIdentity.parse("demo/Handler.scala").toOption.get
    assertEquals(
      resolveDependency(
        identity,
        List(
          "/workspace/first/demo/Handler.scala",
          "/workspace/second/demo/Handler.scala"
        )
      ),
      DependencyResolution.Ambiguous(
        List(
          "workspace/first/demo/Handler.scala",
          "workspace/second/demo/Handler.scala"
        )
      )
    )
  }

  test("initial run suspends for a different current-run dependency") {
    assertEquals(
      decide(
        RunKind.Initial,
        "workspace/demo/Consumer.scala",
        DependencyResolution.Unique("workspace/demo/Handler.scala")
      ),
      DeferredHandlerAction.SuspendForCurrentRunDependency(
        "workspace/demo/Handler.scala"
      )
    )
  }

  test("resumed run chooses output-aware loading") {
    assertEquals(
      decide(
        RunKind.Resumed,
        "workspace/demo/Consumer.scala",
        DependencyResolution.Missing
      ),
      DeferredHandlerAction.LoadCompiledHandler(LoadReason.ResumedRun)
    )
  }

  test("initial run without the dependency chooses incremental fallback loading") {
    assertEquals(
      decide(
        RunKind.Initial,
        "workspace/demo/Consumer.scala",
        DependencyResolution.Missing
      ),
      DeferredHandlerAction.LoadCompiledHandler(LoadReason.IncrementalFallback)
    )
  }

  test("initial same-file relation is rejected") {
    val path = "workspace/demo/Combined.scala"
    assertEquals(
      decide(RunKind.Initial, path, DependencyResolution.Unique(path)),
      DeferredHandlerAction.RejectSameFile(path)
    )
  }

  test("initial marker and consumer same-file topology is rejected") {
    val marker = SourceIdentity.parse("demo/MarkerAndConsumer.scala").toOption.get
    assertEquals(
      decide(
        RunKind.Initial,
        "workspace/demo/MarkerAndConsumer.scala",
        marker,
        DependencyResolution.Missing
      ),
      DeferredHandlerAction.RejectMarkerConsumerSameFile(
        "workspace/demo/MarkerAndConsumer.scala"
      )
    )
  }

  test("same-module source digest accepts only a sha256 token") {
    val digest = "a" * 64
    assertEquals(SourceDigest.parse("sha256:" + digest).map(_.value), Right("sha256:" + digest))
    assert(SourceDigest.parse(digest).isLeft)
    assert(SourceDigest.parse("sha256:" + "g" * 64).isLeft)
    assert(SourceDigest.parse("sha256:" + "a" * 63).isLeft)
  }

  test("same-module configuration requires one relationship and one distinct source digest") {
    val digest = "sha256:" + "a" * 64
    val relationship =
      "sameModuleHandler=demo.marker:demo.Handler:demo/Marker.scala:demo/Handler.scala"
    val parsed = parseConfiguration(
      List(relationship, "sameModuleSourceIdentity=" + digest)
    ).map(_.map(configuration => (
      configuration.annotationName,
      configuration.handlerClassName,
      configuration.markerSourceIdentity.value,
      configuration.handlerSourceIdentity.value,
      configuration.sourceDigest.value
    )))
    assertEquals(
      parsed,
      Right(
        Some((
          "demo.marker",
          "demo.Handler",
          "demo/Marker.scala",
          "demo/Handler.scala",
          digest
        ))
      )
    )
    assert(parseConfiguration(List(relationship)).isLeft)
    assert(parseConfiguration(List("sameModuleSourceIdentity=" + digest)).isLeft)
    assert(parseConfiguration(List(relationship, "sameModuleSourceIdentity=sha256:bad")).isLeft)
    assertEquals(parseConfiguration(Nil), Right(None))
  }

  test("exactly two external relationships parse into canonical order with one digest") {
    val digest = "sha256:" + "d" * 64
    val relationshipA =
      "sameModuleHandler=demo.markerA:demo.HandlerA:demo/MarkerA.scala:demo/HandlerA.scala"
    val relationshipB =
      "sameModuleHandler=demo.markerB:demo.HandlerB:demo/MarkerB.scala:demo/HandlerB.scala"

    val parsed = parseConfigurations(
      List(relationshipB, "sameModuleSourceIdentity=" + digest, relationshipA)
    ).map(_.map(configuration => (
      configuration.annotationName,
      configuration.handlerClassName,
      configuration.markerSourceIdentity.value,
      configuration.handlerSourceIdentity.value,
      configuration.sourceDigest.value
    )))

    assertEquals(
      parsed,
      Right(
        Vector(
          ("demo.markerA", "demo.HandlerA", "demo/MarkerA.scala", "demo/HandlerA.scala", digest),
          ("demo.markerB", "demo.HandlerB", "demo/MarkerB.scala", "demo/HandlerB.scala", digest)
        )
      )
    )
  }

  test("bounded external parsing rejects mixed mode and capacity before payload errors") {
    val digest = "sameModuleSourceIdentity=sha256:" + "e" * 64
    val malformed = "sameModuleHandler=bad"
    val embedded = "sameModuleEmbedded=demo.marker:demo/Embedded.scala"

    assertEquals(
      parseConfigurations(List(malformed, malformed, embedded, digest)),
      Left("`sameModuleHandler=` and `sameModuleEmbedded=` are mutually exclusive")
    )
    assertEquals(
      parseConfigurations(List(malformed, malformed, malformed, digest)),
      Left("the `sameModuleHandler=` option accepts at most two explicit relationships")
    )
  }

  test("bounded parsing preserves singleton legacy diagnostic precedence byte-for-byte") {
    val options = List(
      "sameModuleHandler=demo.marker:demo.Handler:demo/Marker.scala:demo/Handler.scala",
      "sameModuleEmbedded=demo.marker:demo/Marker.scala",
      "sameModuleSourceIdentity=not-a-digest",
      "sameModuleSourceIdentity=also-not-a-digest"
    )

    assertEquals(parseConfigurations(options), parseConfiguration(options).map(_.toVector))
  }

  test("bounded external parsing requires exactly one digest") {
    val relationshipA =
      "sameModuleHandler=demo.markerA:demo.HandlerA:demo/MarkerA.scala:demo/HandlerA.scala"
    val relationshipB =
      "sameModuleHandler=demo.markerB:demo.HandlerB:demo/MarkerB.scala:demo/HandlerB.scala"
    val digest = "sameModuleSourceIdentity=sha256:" + "f" * 64

    assertEquals(
      parseConfigurations(List(relationshipA, relationshipB)),
      Left("`sameModuleHandler=` requires one distinct `sameModuleSourceIdentity=` compiler input")
    )
    assertEquals(
      parseConfigurations(List(relationshipA, relationshipB, digest, digest)),
      Left("the `sameModuleSourceIdentity=` option accepts exactly one source digest")
    )
  }

  test("bounded external parsing rejects noncanonical and duplicate identities deterministically") {
    val digest = "sameModuleSourceIdentity=sha256:" + "a" * 64
    val validA =
      "sameModuleHandler=demo.markerA:demo.HandlerA:demo/MarkerA.scala:demo/HandlerA.scala"
    val unqualifiedB =
      "sameModuleHandler=markerB:demo.HandlerB:demo/MarkerB.scala:demo/HandlerB.scala"
    val duplicateAnnotation =
      "sameModuleHandler=demo.markerA:demo.HandlerB:demo/MarkerB.scala:demo/HandlerB.scala"
    val duplicateHandler =
      "sameModuleHandler=demo.markerB:demo.HandlerA:demo/MarkerB.scala:demo/HandlerB.scala"
    val duplicatePath =
      "sameModuleHandler=demo.markerB:demo.HandlerB:demo/MarkerA.scala:demo/HandlerB.scala"

    assertEquals(
      parseConfigurations(List(validA, unqualifiedB, digest)),
      Left("invalid annotation name in bounded `sameModuleHandler=` set: expected a qualified canonical annotation class name: markerB")
    )
    assertEquals(
      parseConfigurations(List(validA, duplicateAnnotation, digest)),
      Left("duplicate same-module annotation names: demo.markerA")
    )
    assertEquals(
      parseConfigurations(List(duplicateHandler, validA, digest)),
      Left("duplicate same-module handler class names: demo.HandlerA")
    )
    val expectedPath = Left("duplicate same-module producer source identities: demo/MarkerA.scala")
    assertEquals(parseConfigurations(List(validA, duplicatePath, digest)), expectedPath)
    assertEquals(parseConfigurations(List(duplicatePath, digest, validA)), expectedPath)
  }

  test("bounded external parsing rejects whitespace-padded relationship tokens") {
    val digest = "sameModuleSourceIdentity=sha256:" + "b" * 64
    val padded =
      "sameModuleHandler=demo.markerA: demo.HandlerA:demo/MarkerA.scala:demo/HandlerA.scala"
    val valid =
      "sameModuleHandler=demo.markerB:demo.HandlerB:demo/MarkerB.scala:demo/HandlerB.scala"

    assertEquals(
      parseConfigurations(List(valid, padded, digest)),
      Left("invalid `sameModuleHandler=` option; relationship tokens must not contain surrounding whitespace")
    )
  }

  test("embedded same-module configuration derives the private adapter from one canonical producer binding") {
    val digest = "sha256:" + "b" * 64
    val embedded = "sameModuleEmbedded=demo.sameModuleGreeting:demo/EmbeddedAnnotations.scala"

    val parsed = parseConfiguration(
      List(embedded, "sameModuleSourceIdentity=" + digest)
    ).map(_.map(configuration => (
      configuration.annotationName,
      configuration.handlerClassName,
      configuration.markerSourceIdentity.value,
      configuration.handlerSourceIdentity.value,
      configuration.sourceDigest.value
    )))

    assertEquals(
      parsed,
      Right(
        Some((
          "demo.sameModuleGreeting",
          "demo.sameModuleGreeting__MacroParadiseEmbeddedExpansionHandler",
          "demo/EmbeddedAnnotations.scala",
          "demo/EmbeddedAnnotations.scala",
          digest
        ))
      )
    )
  }

  test("embedded and external options are mutually exclusive and embedded input is singular and canonical") {
    val digest = "sameModuleSourceIdentity=sha256:" + "c" * 64
    val external =
      "sameModuleHandler=demo.marker:demo.Handler:demo/Marker.scala:demo/Handler.scala"
    val embedded = "sameModuleEmbedded=demo.marker:demo/Embedded.scala"

    assert(parseConfiguration(List(external, embedded, digest)).isLeft)
    assert(parseConfiguration(List(embedded, embedded, digest)).isLeft)
    assert(parseConfiguration(List("sameModuleEmbedded=marker:demo/Embedded.scala", digest)).isLeft)
    assert(parseConfiguration(List("sameModuleEmbedded=demo.marker:Embedded.scala", digest)).isLeft)
  }

  test("exactly two embedded relationships parse in canonical order with explicit embedded-pair mode") {
    val digest = "sameModuleSourceIdentity=sha256:" + "d" * 64
    val a = "sameModuleEmbedded=demo.markerA:demo/ProducerA.scala"
    val b = "sameModuleEmbedded=demo.markerB:demo/ProducerB.scala"

    val parsed = parseModeConfigurations(List(b, digest, a))
    assertEquals(parsed.map(_.mode), Right(SameModuleMode.EmbeddedPair))
    assertEquals(
      parsed.map(_.configurations.map(configuration =>
        (configuration.annotationName, configuration.handlerClassName, configuration.handlerSourceIdentity.value)
      )),
      Right(Vector(
        ("demo.markerA", "demo.markerA__MacroParadiseEmbeddedExpansionHandler", "demo/ProducerA.scala"),
        ("demo.markerB", "demo.markerB__MacroParadiseEmbeddedExpansionHandler", "demo/ProducerB.scala")
      ))
    )
    assertEquals(parseModeConfigurations(List(a, digest)).map(_.mode), Right(SameModuleMode.EmbeddedSingleton))
  }

  test("embedded pair parsing rejects mixed mode, capacity, duplicates, and padded tokens deterministically") {
    val digest = "sameModuleSourceIdentity=sha256:" + "e" * 64
    val a = "sameModuleEmbedded=demo.markerA:demo/ProducerA.scala"
    val b = "sameModuleEmbedded=demo.markerB:demo/ProducerB.scala"
    val external = "sameModuleHandler=demo.marker:demo.Handler:demo/Marker.scala:demo/Handler.scala"

    assertEquals(
      parseModeConfigurations(List(a, b, external, digest)),
      Left("`sameModuleHandler=` and `sameModuleEmbedded=` are mutually exclusive")
    )
    assertEquals(
      parseModeConfigurations(List(a, b, b, digest)),
      Left("the `sameModuleEmbedded=` option accepts at most two explicit relationships")
    )
    assertEquals(
      parseModeConfigurations(List(a, a, digest)),
      Left("duplicate same-module annotation names: demo.markerA")
    )
    assertEquals(
      parseModeConfigurations(List(a, "sameModuleEmbedded=demo.markerB:demo/ProducerA.scala", digest)),
      Left("duplicate same-module producer source identities: demo/ProducerA.scala")
    )
    assertEquals(
      parseModeConfigurations(List(a, "sameModuleEmbedded=demo.markerB: demo/ProducerB.scala", digest)),
      Left("invalid `sameModuleEmbedded=` option; relationship tokens must not contain surrounding whitespace")
    )
  }
