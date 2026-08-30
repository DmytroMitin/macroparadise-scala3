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
