package macroparadise

import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.ast.untpd.Tree
import dotty.tools.dotc.core.Contexts.{Context, ContextBase}
import dotty.tools.dotc.parsing.Parsers

final class BoundedSameModuleSelectionSpec extends munit.FunSuite:
  import DeferredSameModuleHandlerSupport.SourceIdentity

  private val Configured = Vector("demo.markerA", "demo.markerB")

  test("bounded selection accepts only direct-qualified configured annotations") {
    val (qualified, qualifiedContext) = parsed(
      """package consumer
        |@demo.markerA class A
        |""".stripMargin
    )
    assertEquals(
      BoundedSameModuleSelection.select(qualified, Configured)(using qualifiedContext),
      Right(Some("demo.markerA"))
    )

    val (imported, importedContext) = parsed(
      """package consumer
        |import demo.markerA
        |@markerA class A
        |""".stripMargin
    )
    assertEquals(
      BoundedSameModuleSelection.select(imported, Configured)(using importedContext),
      Left(
        "bounded same-module external bindings require direct-qualified consumer annotations; unsupported configured identities: demo.markerA"
      )
    )
  }

  test("bounded selection rejects shorthand wildcard and aliased configured identities") {
    val cases = List(
      "shorthand" ->
        """package consumer
          |@markerA class A
          |""".stripMargin,
      "wildcard" ->
        """package consumer
          |import demo.*
          |@markerA class A
          |""".stripMargin,
      "alias" ->
        """package consumer
          |import demo.{markerA => aliasA}
          |@aliasA class A
          |""".stripMargin
    )

    cases.foreach: (label, source) =>
      val (tree, context) = parsed(source)
      assertEquals(
        BoundedSameModuleSelection.select(tree, Configured)(using context),
        Left(
          "bounded same-module external bindings require direct-qualified consumer annotations; unsupported configured identities: demo.markerA"
        ),
        label
      )
  }

  test("bounded selection rejects an imported second binding before a direct binding can load") {
    val (tree, context) = parsed(
      """package consumer
        |import demo.markerB
        |@demo.markerA
        |@markerB class A
        |""".stripMargin
    )

    assertEquals(
      BoundedSameModuleSelection.select(tree, Configured)(using context),
      Left(
        "bounded same-module external bindings require direct-qualified consumer annotations; unsupported configured identities: demo.markerB"
      )
    )
  }

  test("bounded selection ignores an explicitly imported unrelated annotation with a configured leaf") {
    val (tree, context) = parsed(
      """package consumer
        |import other.markerA
        |@markerA
        |@demo.markerB class A
        |""".stripMargin
    )

    assertEquals(
      BoundedSameModuleSelection.select(tree, Configured)(using context),
      Right(Some("demo.markerB"))
    )
  }

  test("embedded pair selection accepts direct-qualified and source-preceding explicit unaliased imports") {
    val (direct, directContext) = parsed("package consumer; @demo.markerA class A")
    assertEquals(
      EmbeddedSameModuleSelection.select(direct, Configured)(using directContext),
      Right(Some("demo.markerA"))
    )
    val (imported, importedContext) = parsed(
      """package consumer
        |import demo.markerB
        |@markerB class B
        |""".stripMargin
    )
    assertEquals(
      EmbeddedSameModuleSelection.select(imported, Configured)(using importedContext),
      Right(Some("demo.markerB"))
    )
  }

  test("embedded pair selection ignores unrelated same-leaf imports and rejects attributable unsupported spellings") {
    val cases = List(
      "alias" -> "package consumer; import demo.{markerA => aliasA}; @aliasA class A",
      "wildcard" -> "package consumer; import demo.*; @markerA class A",
      "ambiguous" -> "package consumer; import demo.markerA; import other.markerA; @markerA class A"
    )
    cases.foreach: (label, source) =>
      val (tree, context) = parsed(source)
      assert(EmbeddedSameModuleSelection.select(tree, Configured)(using context).isLeft, label)

    val (unrelated, unrelatedContext) = parsed(
      "package consumer; import other.markerA; @markerA class A; @demo.markerB class B"
    )
    assertEquals(
      EmbeddedSameModuleSelection.select(unrelated, Configured)(using unrelatedContext),
      Right(Some("demo.markerB"))
    )
  }

  test("embedded pair selection requires source-preceding package imports and rejects shadowed or nested use") {
    val rejected = List(
      "shadowed" ->
        "package consumer; import demo.markerA; class markerA; @markerA class A",
      "nested direct" ->
        "package consumer; object Holder { @demo.markerA class Nested }",
      "nested imported" ->
        "package consumer; import demo.markerA; object Holder { @markerA class Nested }",
      "local import" ->
        "package consumer; object Holder { import demo.markerA; @markerA class Nested }"
    )
    rejected.foreach: (label, source) =>
      val (tree, context) = parsed(source)
      assert(EmbeddedSameModuleSelection.select(tree, Configured)(using context).isLeft, label)

    val (lateImport, lateImportContext) = parsed(
      "package consumer; @markerA class A; import demo.markerA"
    )
    assertEquals(
      EmbeddedSameModuleSelection.select(lateImport, Configured)(using lateImportContext),
      Right(None)
    )
  }

  test("embedded pair selection rejects dual selection before either handler can load") {
    val (tree, context) = parsed(
      "package consumer; import demo.markerB; @demo.markerA @markerB class Both"
    )
    assertEquals(
      EmbeddedSameModuleSelection.select(tree, Configured.reverse)(using context),
      Left("MULTIPLE_CONFIGURED_SAME_MODULE_ANNOTATIONS: demo.markerA, demo.markerB")
    )
  }

  test("embedded pair selection detects a newly introduced cross-binding identity") {
    val (original, originalContext) = parsed("package consumer; @demo.markerA class A")
    val (rewritten, rewrittenContext) = parsed(
      "package consumer; import demo.markerB; @demo.markerA @markerB class A"
    )
    assertEquals(
      EmbeddedSameModuleSelection.introducedCrossBinding(
        original,
        rewritten,
        "demo.markerA",
        Configured
      )(using rewrittenContext),
      Right(Some("demo.markerB"))
    )
    assertEquals(
      EmbeddedSameModuleSelection.introducedCrossBinding(
        original,
        original,
        "demo.markerA",
        Configured
      )(using originalContext),
      Right(None)
    )
  }

  test("bounded selection rejects two configured identities in one physical unit deterministically") {
    val (tree, context) = parsed(
      """package consumer
        |@demo.markerB class B
        |@demo.markerA class A
        |""".stripMargin
    )

    assertEquals(
      BoundedSameModuleSelection.select(tree, Configured.reverse)(using context),
      Left("multiple configured same-module annotations select one source: demo.markerA, demo.markerB")
    )
  }

  test("bounded selection detects a second configured annotation introduced after one selection") {
    val (original, originalContext) = parsed(
      """package consumer
        |@demo.markerA class A
        |""".stripMargin
    )
    val (rewritten, rewrittenContext) = parsed(
      """package consumer
        |@demo.markerB class A
        |""".stripMargin
    )

    assertEquals(
      BoundedSameModuleSelection.introducedCrossBinding(
        original,
        rewritten,
        "demo.markerA",
        Configured
      )(using rewrittenContext),
      Some("demo.markerB")
    )
    assertEquals(
      BoundedSameModuleSelection.introducedCrossBinding(
        original,
        original,
        "demo.markerA",
        Configured
      )(using originalContext),
      None
    )
  }

  test("producer collision diagnostics cover both marker and handler sources for both bindings") {
    val identities = Vector(
      "demo/MarkerA.scala",
      "demo/HandlerA.scala",
      "demo/MarkerB.scala",
      "demo/HandlerB.scala"
    ).map(SourceIdentity.parse(_).toOption.get)

    identities.foreach: identity =>
      val path = s"workspace/src/main/scala/${identity.value}"
      assertEquals(
        BoundedSameModuleSelection.producerCollisionDiagnostic(path, identities),
        Some(
          s"configured same-module producer source cannot also be a consumer: $path; matched producer identities: ${identity.value}"
        )
      )
  }

  test("producer preflight reports direct edges and cycles in canonical order") {
    val (handlerA, contextA) = parsedUnit(
      "workspace/demo/HandlerA.scala",
      "package demo; @demo.markerB class HandlerA"
    )
    val (handlerB, _) = parsedUnit(
      "workspace/demo/HandlerB.scala",
      "package demo; @demo.markerA class HandlerB"
    )
    val producers = Vector(
      BoundedSameModulePreflight.Producer(
        "demo.markerB",
        "demo.HandlerB",
        SourceIdentity.parse("demo/HandlerB.scala").toOption.get
      ),
      BoundedSameModulePreflight.Producer(
        "demo.markerA",
        "demo.HandlerA",
        SourceIdentity.parse("demo/HandlerA.scala").toOption.get
      )
    )

    assertEquals(
      BoundedSameModulePreflight.validate(
        List(handlerB, handlerA),
        producers,
        Configured
      )(using contextA),
      Right(
        Vector(
          "UNSUPPORTED_SAME_MODULE_PRODUCER_EDGE producerSource=workspace/demo/HandlerA.scala producerBinding=demo.markerA/demo.HandlerA requiresAnnotation=demo.markerB detail=bounded same-module external bindings must be independent",
          "UNSUPPORTED_SAME_MODULE_PRODUCER_EDGE producerSource=workspace/demo/HandlerB.scala producerBinding=demo.markerB/demo.HandlerB requiresAnnotation=demo.markerA detail=bounded same-module external bindings must be independent"
        )
      )
    )
  }

  test("producer preflight rejects ambiguous current-run source matches and permits missing incremental producers") {
    val (first, context) = parsedUnit(
      "workspace/first/demo/HandlerA.scala",
      "package demo; class HandlerA"
    )
    val (second, _) = parsedUnit(
      "workspace/second/demo/HandlerA.scala",
      "package demo; class HandlerA"
    )
    val producer = BoundedSameModulePreflight.Producer(
      "demo.markerA",
      "demo.HandlerA",
      SourceIdentity.parse("demo/HandlerA.scala").toOption.get
    )

    assertEquals(
      BoundedSameModulePreflight.validate(List(first, second), Vector(producer), Configured)(using context),
      Left(
        "same-module producer source `demo/HandlerA.scala` is ambiguous in the current run; matched: workspace/first/demo/HandlerA.scala, workspace/second/demo/HandlerA.scala"
      )
    )
    assertEquals(
      BoundedSameModulePreflight.validate(Nil, Vector(producer), Configured)(using context),
      Right(Vector.empty)
    )
  }

  private def parsed(source: String): (Tree, Context) =
    val unit = CompilationUnit("BoundedSameModuleSelectionSpec.scala", source)
    val context = ContextBase().initialCtx.fresh.setCompilationUnit(unit)
    (new Parsers.Parser(unit.source)(using context).parse(), context)

  private def parsedUnit(path: String, source: String): (CompilationUnit, Context) =
    val unit = CompilationUnit(path, source)
    val context = ContextBase().initialCtx.fresh.setCompilationUnit(unit)
    unit.untpdTree = new Parsers.Parser(unit.source)(using context).parse()
    (unit, context)
