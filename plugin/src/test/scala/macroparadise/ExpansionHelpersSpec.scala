package macroparadise

import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.ast.Trees
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Contexts.{Context, ContextBase}
import dotty.tools.dotc.parsing.Parsers
import paradise3.api.{AnnotatedClassView, ExpansionInput, ExpansionOutcome, StructuredExpansionOutput}
import paradise3.api.helpers.ExpansionHelpers

class ExpansionHelpersSpec extends munit.FunSuite:
  test("successful decode invokes the callback exactly once") {
    val (input, context) = ordinaryInput()
    given Context = context
    var calls = 0

    ExpansionHelpers.withAnnotatedClassView(input): _ =>
      calls += 1
      ExpansionOutcome.NotApplicable

    assertEquals(calls, 1)
  }

  test("callback receives class name and constructor evidence") {
    val (input, context) = ordinaryInput()
    given Context = context
    var received: Option[AnnotatedClassView] = None

    ExpansionHelpers.withAnnotatedClassView(input): view =>
      received = Some(view)
      ExpansionOutcome.NotApplicable

    val view = received.getOrElse(fail("callback was not invoked"))
    assertEquals(view.className, "AuthoringUser")
    assertEquals(
      view.constructorClauses.map(_.parameters.map(_.name)),
      List(List("value"), List("ordering"))
    )
    assert(view.constructorClauses.last.isContextual)
  }

  test("successful decode returns the exact callback outcome object") {
    val (input, context) = ordinaryInput()
    given Context = context
    val expected = ExpansionOutcome.Expanded(List(input.annotatedClass))

    val result = ExpansionHelpers.withAnnotatedClassView(input)(_ => expected)

    assert(result.asInstanceOf[AnyRef] eq expected.asInstanceOf[AnyRef])
  }

  test("decode failure does not invoke the callback") {
    val (input, context) = unsupportedInput()
    given Context = context
    var called = false

    ExpansionHelpers.withAnnotatedClassView(input): _ =>
      called = true
      ExpansionOutcome.NotApplicable

    assert(!called)
  }

  test("decode failure preserves the exact diagnostic message") {
    val (input, context) = unsupportedInput()
    given Context = context
    val expected = input.annotatedClassView.left.toOption.get
    val rejected = rejectedOutcome(ExpansionHelpers.withAnnotatedClassView(input)(_ => fail("unexpected callback")))

    assertEquals(rejected._1.head.message, expected.message)
  }

  test("decode failure preserves the exact diagnostic position") {
    val (input, context) = unsupportedInput()
    given Context = context
    val expected = input.annotatedClassView.left.toOption.get
    val rejected = rejectedOutcome(ExpansionHelpers.withAnnotatedClassView(input)(_ => fail("unexpected callback")))

    assertEquals(rejected._1.head.pos, expected.pos)
  }

  test("decode failure returns exactly one diagnostic") {
    val (input, context) = unsupportedInput()
    given Context = context
    val rejected = rejectedOutcome(ExpansionHelpers.withAnnotatedClassView(input)(_ => fail("unexpected callback")))

    assertEquals(rejected._1.size, 1)
  }

  test("decode failure uses the exact annotated class fallback") {
    val (input, context) = unsupportedInput()
    given Context = context
    val rejected = rejectedOutcome(ExpansionHelpers.withAnnotatedClassView(input)(_ => fail("unexpected callback")))

    assert(rejected._2 eq input.annotatedClass)
  }

  test("view adaptation does not mutate raw input or contextual evidence") {
    val (stats, context) = parsedStats("@deprecated class Stable(value: String)\nobject Stable")
    given Context = context
    val annotated = typeDefNamed(stats, "Stable")
    val companion = stats.collectFirst { case value: ModuleDef => value }.getOrElse(fail("missing companion"))
    val annotations = Trees.mods(annotated).annotations
    val modifiers = Trees.mods(annotated)
    val names = Set("Stable", "Neighbor")
    val input = ExpansionInput("externalDebug", annotated, Some(companion), names, annotations.headOption)
    val classSpan = annotated.sourcePos.span
    val companionSpan = companion.sourcePos.span

    ExpansionHelpers.withAnnotatedClassView(input)(_ => ExpansionOutcome.NotApplicable)

    assert(input.annotatedClass eq annotated)
    assertEquals(Trees.mods(annotated), modifiers)
    assertEquals(Trees.mods(annotated).annotations, annotations)
    assertEquals(input.existingCompanion, Some(companion))
    assertEquals(input.topLevelNames, names)
    assertEquals(input.currentAnnotation, annotations.headOption)
    assertEquals(annotated.sourcePos.span, classSpan)
    assertEquals(companion.sourcePos.span, companionSpan)
  }

  test("callback exception is not swallowed") {
    val (input, context) = ordinaryInput()
    given Context = context
    val expected = new IllegalStateException("callback failure")

    val actual = intercept[IllegalStateException]:
      ExpansionHelpers.withAnnotatedClassView(input)(_ => throw expected)

    assert(actual eq expected)
  }

  test("callback null outcome passes through unchanged") {
    val (input, context) = ordinaryInput()
    given Context = context

    val result = ExpansionHelpers.withAnnotatedClassView(input)(_ => null)

    assert(result == null)
  }

  test("callback NotApplicable passes through unchanged") {
    val (input, context) = ordinaryInput()
    given Context = context

    val result = ExpansionHelpers.withAnnotatedClassView(input)(_ => ExpansionOutcome.NotApplicable)

    assert(result eq ExpansionOutcome.NotApplicable)
  }

  test("malformed structured raw and rejected callback outcomes are not normalized") {
    val (input, context) = ordinaryInput()
    given Context = context
    val malformed = List[ExpansionOutcome](
      ExpansionOutcome.Structured(null),
      ExpansionOutcome.Expanded(null),
      ExpansionOutcome.Rejected(null, null)
    )

    malformed.foreach: expected =>
      val result = ExpansionHelpers.withAnnotatedClassView(input)(_ => expected)
      assert(result.asInstanceOf[AnyRef] eq expected.asInstanceOf[AnyRef])
  }

  test("null helper input fails with a focused argument error") {
    val (_, context) = ordinaryInput()
    given Context = context

    val error = intercept[IllegalArgumentException]:
      ExpansionHelpers.withAnnotatedClassView(null)(_ => ExpansionOutcome.NotApplicable)

    assert(error.getMessage.contains("non-null ExpansionInput"))
  }

  test("null callback fails with a focused argument error after successful decoding") {
    val (input, context) = ordinaryInput()
    given Context = context
    val callback = null.asInstanceOf[AnnotatedClassView => ExpansionOutcome]

    val error = intercept[IllegalArgumentException]:
      ExpansionHelpers.withAnnotatedClassView(input)(callback)

    assert(error.getMessage.contains("non-null callback"))
  }

  test("null annotated class preserves decoder failure then rejects missing fallback deterministically") {
    val (_, context) = ordinaryInput()
    given Context = context
    val input = ExpansionInput("externalDebug", null, None, Set.empty)

    val error = intercept[IllegalArgumentException]:
      ExpansionHelpers.withAnnotatedClassView(input)(_ => ExpansionOutcome.NotApplicable)

    assert(error.getMessage.contains("without a fallback annotated class"))
    assert(error.getMessage.contains("null annotated class"))
  }

  private def ordinaryInput(): (ExpansionInput, Context) =
    val (stats, context) =
      parsedStats(
        "class AuthoringUser(value: String)(using ordering: Ordering[String])"
      )
    val annotated = typeDefNamed(stats, "AuthoringUser")
    (
      ExpansionInput(
        "externalDebug",
        annotated,
        None,
        Set("AuthoringUser", "Neighbor"),
        Trees.mods(annotated).annotations.headOption
      ),
      context
    )

  private def unsupportedInput(): (ExpansionInput, Context) =
    val (stats, context) = parsedStats("type Unsupported = String")
    val annotated = typeDefNamed(stats, "Unsupported")
    (
      ExpansionInput("externalDebug", annotated, None, Set("Unsupported")),
      context
    )

  private def rejectedOutcome(
      outcome: ExpansionOutcome
  ): (List[paradise3.api.ExpansionDiagnostic], TypeDef) =
    outcome match
      case ExpansionOutcome.Rejected(diagnostics, fallback) =>
        (diagnostics, fallback)
      case other => fail(s"expected Rejected, found $other")

  private def typeDefNamed(stats: List[Tree], name: String): TypeDef =
    stats.collectFirst { case value: TypeDef if value.name.toString == name => value }
      .getOrElse(fail(s"missing TypeDef $name in $stats"))

  private def parsedStats(code: String): (List[Tree], Context) =
    val unit = CompilationUnit("ExpansionHelpersSpec.scala", code)
    val context = ContextBase().initialCtx.fresh.setCompilationUnit(unit)
    val parsed = new Parsers.Parser(unit.source)(using context).parse()
    val stats = parsed match
      case PackageDef(_, values) => values
      case tree => List(tree)
    (stats, context)
