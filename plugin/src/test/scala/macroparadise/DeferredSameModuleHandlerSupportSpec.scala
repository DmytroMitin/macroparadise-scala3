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
