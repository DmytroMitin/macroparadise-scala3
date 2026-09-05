package macroparadise

import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.ast.Trees
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Contexts.{Context, ContextBase}
import dotty.tools.dotc.parsing.Parsers
import paradise3.api.{ExpansionInput, ExpansionOutcome, StructuredExpansionOutput}
import paradise3.api.helpers.ExpansionHelpers

class GeneratedMemberPlacementHelperSpec extends munit.FunSuite:
  test("primary placement preserves existing order and appends the exact supplied DefDef") {
    val fixture = parsedFixture("class PlacementSubject", "val before: Int = 1")
    given Context = fixture.context
    val originalTemplate = fixture.primary.rhs.asInstanceOf[Template]
    val originalBody = originalTemplate.body

    val output = structured:
      ExpansionHelpers.placeMembersInPrimary(
        fixture.input(None),
        List(fixture.generatedMethod)
      )

    val rewrittenTemplate = output.primary.rhs.asInstanceOf[Template]
    assertEquals(rewrittenTemplate.body.size, originalBody.size + 1)
    assert(rewrittenTemplate.body.head.eq(originalBody.head), clue(rewrittenTemplate.body))
    assert(rewrittenTemplate.body.last.eq(fixture.generatedMethod), clue(rewrittenTemplate.body))
    assert(rewrittenTemplate.constr.eq(originalTemplate.constr), clue(rewrittenTemplate.constr))
    assertEquals(rewrittenTemplate.parentsOrDerived, originalTemplate.parentsOrDerived)
    assertEquals(rewrittenTemplate.derived, originalTemplate.derived)
    assert(rewrittenTemplate.self.eq(originalTemplate.self), clue(rewrittenTemplate.self))
  }

  test("primary placement rejects a generated term name that conflicts with an existing direct member") {
    val fixture = parsedFixture("class PlacementSubject", "val answer: Int = 1")
    given Context = fixture.context

    ExpansionHelpers.placeMembersInPrimary(
      fixture.input(None),
      List(fixture.generatedValue)
    ) match
      case ExpansionOutcome.Rejected(diagnostics, fallback) =>
        assert(fallback.eq(fixture.primary), clue(fallback))
        assertEquals(diagnostics.size, 1)
        assertEquals(
          diagnostics.head.message,
          "generated primary member `answer` conflicts with existing direct primary term member `answer` for `PlacementSubject`"
        )
        assertEquals(diagnostics.head.pos, fixture.currentAnnotation.sourcePos)
      case other => fail(s"expected Rejected, found $other")

    assertEquals(
      fixture.primary.rhs.asInstanceOf[Template].body.collect {
        case member: MemberDef if member.name.toString == "answer" => member
      }.size,
      1
    )
  }

  test("companion placement creates a class companion containing the exact DefDef and ValDef batch") {
    val fixture = parsedFixture("class PlacementSubject")
    given Context = fixture.context

    val output = structured:
      ExpansionHelpers.placeMembersInCompanion(
        fixture.input(None),
        List(fixture.generatedMethod, fixture.generatedValue)
      )

    val companion = output.companion.getOrElse(fail("missing generated companion"))
    assertEquals(companion.name.toString, "PlacementSubject")
    val body = companion.impl.body
    assertEquals(body.size, 2)
    assert(body.head.eq(fixture.generatedMethod), clue(body))
    assert(body.last.eq(fixture.generatedValue), clue(body))
  }

  test("an empty generated batch rejects without consuming the current annotation") {
    val fixture = parsedFixture("class PlacementSubject")
    given Context = fixture.context

    ExpansionHelpers.placeMembersInPrimary(fixture.input(None), Nil) match
      case ExpansionOutcome.Rejected(diagnostics, fallback) =>
        assert(fallback.eq(fixture.primary), clue(fallback))
        assertEquals(diagnostics.map(_.message), List(
          "generated member batch for `PlacementSubject` must contain at least one untpd.DefDef or untpd.ValDef"
        ))
      case other => fail(s"expected Rejected, found $other")

    assert(Trees.mods(fixture.primary).annotations.contains(fixture.currentAnnotation))
  }

  test("primary placement appends an exact DefDef and ValDef batch to a plain zero-parameter trait") {
    val fixture = parsedFixture("trait PlacementSubject", "val before: Int = 1")
    given Context = fixture.context
    val original = fixture.primary.rhs.asInstanceOf[Template]
    val originalBody = original.body

    val output = structured:
      ExpansionHelpers.placeMembersInPrimary(
        fixture.input(None),
        List(fixture.generatedMethod, fixture.generatedValue)
      )

    val rewritten = output.primary.rhs.asInstanceOf[Template]
    assertEquals(rewritten.body.size, originalBody.size + 2)
    assert(rewritten.body.head.eq(originalBody.head), clue(rewritten.body))
    assert(rewritten.body.takeRight(2).head.eq(fixture.generatedMethod), clue(rewritten.body))
    assert(rewritten.body.last.eq(fixture.generatedValue), clue(rewritten.body))
    assert(rewritten.constr.eq(original.constr), clue(rewritten.constr))
    assertEquals(rewritten.parentsOrDerived, original.parentsOrDerived)
    assert(rewritten.self.eq(original.self), clue(rewritten.self))
    val originalAnnotations = Trees.mods(fixture.primary).annotations
    val remainingAnnotations = Trees.mods(output.primary).annotations
    assertEquals(originalAnnotations.size, 3)
    assertEquals(remainingAnnotations.size, 2)
    assert(!remainingAnnotations.exists(_ eq fixture.currentAnnotation))
    assert(remainingAnnotations.zip(originalAnnotations.tail).forall((actual, expected) => actual.eq(expected)))
  }

  test("a later unsupported primary batch member rejects before any earlier member is inserted") {
    val fixture = parsedFixture("class PlacementSubject", "val before: Int = 1")
    given Context = fixture.context
    val originalBody = fixture.primary.rhs.asInstanceOf[Template].body

    ExpansionHelpers.placeMembersInPrimary(
      fixture.input(None),
      List(fixture.generatedMethod, fixture.generatedType)
    ) match
      case ExpansionOutcome.Rejected(diagnostics, fallback) =>
        assert(fallback.eq(fixture.primary), clue(fallback))
        assertEquals(diagnostics.size, 1)
        assert(diagnostics.head.message.contains("entry 1"), clue(diagnostics))
        assert(diagnostics.head.message.contains("only untpd.DefDef and untpd.ValDef"), clue(diagnostics))
      case other => fail(s"expected Rejected, found $other")

    assertEquals(fixture.primary.rhs.asInstanceOf[Template].body, originalBody)
    assert(Trees.mods(fixture.primary).annotations.contains(fixture.currentAnnotation))
  }

  test("duplicate generated method names are rejected instead of treated as pre-typer overloads") {
    val fixture = parsedFixture("class PlacementSubject")
    given Context = fixture.context

    ExpansionHelpers.placeMembersInPrimary(
      fixture.input(None),
      List(fixture.generatedMethod, fixture.generatedMethod)
    ) match
      case ExpansionOutcome.Rejected(diagnostics, fallback) =>
        assert(fallback.eq(fixture.primary), clue(fallback))
        assertEquals(diagnostics.size, 1)
        assertEquals(
          diagnostics.head.message,
          "generated member batch for `PlacementSubject` contains duplicate direct term name `foo`; pre-typer overload resolution is not attempted"
        )
      case other => fail(s"expected Rejected, found $other")
  }

  test("an existing same-name method is a direct primary conflict regardless of raw signature") {
    val fixture = parsedFixture(
      "class PlacementSubject",
      "def foo(value: String): String = value"
    )
    given Context = fixture.context

    val outcome = ExpansionHelpers.placeMembersInPrimary(
      fixture.input(None),
      List(fixture.generatedMethod)
    )

    outcome match
      case ExpansionOutcome.Rejected(diagnostics, fallback) =>
        assert(fallback.eq(fixture.primary), clue(fallback))
        assertEquals(diagnostics.size, 1)
        assert(diagnostics.head.message.contains("conflicts with existing direct primary term member `foo`"))
      case other => fail(s"expected Rejected, found $other")
  }

  test("companion placement merges a batch while preserving the existing class companion Template") {
    val fixture = parsedFixture(
      "class PlacementSubject",
      companionBody = "val before: Int = 1\nval after: Int = 2"
    )
    given Context = fixture.context
    val existing = fixture.companion.getOrElse(fail("missing existing companion"))
    val originalTemplate = existing.impl
    val originalBody = originalTemplate.body
    val originalMods = Trees.mods(existing)

    val output = structured:
      ExpansionHelpers.placeMembersInCompanion(
        fixture.input(Some(existing)),
        List(fixture.generatedMethod, fixture.generatedValue)
      )

    val merged = output.companion.getOrElse(fail("missing merged companion"))
    val mergedTemplate = merged.impl
    assert(!merged.eq(existing), clue(merged))
    assertEquals(mergedTemplate.body.size, originalBody.size + 2)
    assert(mergedTemplate.body.take(originalBody.size).zip(originalBody).forall((actual, expected) => actual.eq(expected)))
    assert(mergedTemplate.body.takeRight(2).head.eq(fixture.generatedMethod), clue(mergedTemplate.body))
    assert(mergedTemplate.body.last.eq(fixture.generatedValue), clue(mergedTemplate.body))
    assert(mergedTemplate.constr.eq(originalTemplate.constr), clue(mergedTemplate.constr))
    assertEquals(mergedTemplate.parentsOrDerived, originalTemplate.parentsOrDerived)
    assertEquals(mergedTemplate.derived, originalTemplate.derived)
    assert(mergedTemplate.self.eq(originalTemplate.self), clue(mergedTemplate.self))
    assertEquals(Trees.mods(merged), originalMods)
    assertEquals(merged.sourcePos, existing.sourcePos)
  }

  test("companion placement creates a plain-trait companion containing both generated member kinds") {
    val fixture = parsedFixture("trait PlacementSubject")
    given Context = fixture.context

    val output = structured:
      ExpansionHelpers.placeMembersInCompanion(
        fixture.input(None),
        List(fixture.generatedMethod, fixture.generatedValue)
      )

    val companion = output.companion.getOrElse(fail("missing generated trait companion"))
    assertEquals(companion.impl.body.size, 2)
    assert(companion.impl.body.head.eq(fixture.generatedMethod), clue(companion.impl.body))
    assert(companion.impl.body.last.eq(fixture.generatedValue), clue(companion.impl.body))
  }

  test("companion placement merges both generated member kinds into an existing plain-trait companion") {
    val fixture = parsedFixture(
      "trait PlacementSubject",
      companionBody = "val before: Int = 1"
    )
    given Context = fixture.context
    val existing = fixture.companion.getOrElse(fail("missing existing trait companion"))

    val output = structured:
      ExpansionHelpers.placeMembersInCompanion(
        fixture.input(Some(existing)),
        List(fixture.generatedMethod, fixture.generatedValue)
      )

    val body = output.companion.getOrElse(fail("missing merged trait companion")).impl.body
    assertEquals(body.size, 3)
    assert(body.head.eq(existing.impl.body.head), clue(body))
    assert(body(1).eq(fixture.generatedMethod), clue(body))
    assert(body(2).eq(fixture.generatedValue), clue(body))
  }

  test("a direct companion name conflict rejects the complete generated batch") {
    val fixture = parsedFixture(
      "class PlacementSubject",
      companionBody = "def answer: Int = 1"
    )
    given Context = fixture.context
    val existing = fixture.companion.getOrElse(fail("missing existing companion"))
    val originalBody = existing.impl.body

    ExpansionHelpers.placeMembersInCompanion(
      fixture.input(Some(existing)),
      List(fixture.generatedMethod, fixture.generatedValue)
    ) match
      case ExpansionOutcome.Rejected(diagnostics, fallback) =>
        assert(fallback.eq(fixture.primary), clue(fallback))
        assertEquals(diagnostics.size, 1)
        assertEquals(
          diagnostics.head.message,
          "generated companion member `answer` conflicts with existing direct companion term member `answer` for `PlacementSubject`"
        )
      case other => fail(s"expected Rejected, found $other")

    assertEquals(existing.impl.body, originalBody)
    assert(Trees.mods(fixture.primary).annotations.contains(fixture.currentAnnotation))
  }

  test("a later unsupported companion member preserves the exact original primary and companion") {
    val fixture = parsedFixture(
      "trait PlacementSubject",
      companionBody = "val before: Int = 1"
    )
    given Context = fixture.context
    val existing = fixture.companion.getOrElse(fail("missing existing companion"))
    val originalBody = existing.impl.body

    ExpansionHelpers.placeMembersInCompanion(
      fixture.input(Some(existing)),
      List(fixture.generatedValue, fixture.generatedType)
    ) match
      case ExpansionOutcome.Rejected(diagnostics, fallback) =>
        assert(fallback.eq(fixture.primary), clue(fallback))
        assertEquals(diagnostics.size, 1)
        assert(diagnostics.head.message.contains("entry 1"), clue(diagnostics))
      case other => fail(s"expected Rejected, found $other")

    assertEquals(existing.impl.body, originalBody)
    assert(Trees.mods(fixture.primary).annotations.contains(fixture.currentAnnotation))
  }

  test("null generated batches and null entries reject deterministically") {
    val fixture = parsedFixture("class PlacementSubject")
    given Context = fixture.context

    val outcomes = List(
      ExpansionHelpers.placeMembersInPrimary(
        fixture.input(None),
        null.asInstanceOf[List[MemberDef]]
      ),
      ExpansionHelpers.placeMembersInCompanion(
        fixture.input(None),
        List(null.asInstanceOf[MemberDef])
      )
    )

    outcomes.zipWithIndex.foreach: (outcome, index) =>
      outcome match
        case ExpansionOutcome.Rejected(diagnostics, fallback) =>
          assert(fallback.eq(fixture.primary), clue(fallback))
          assertEquals(diagnostics.size, 1)
          assert(diagnostics.head.message.toLowerCase.contains("null"), clues(index, diagnostics))
        case other => fail(s"expected Rejected at $index, found $other")
  }

  test("raw constructors are rejected as unusable generated member names") {
    val fixture = parsedFixture("class PlacementSubject")
    given Context = fixture.context
    val constructor = fixture.primary.rhs.asInstanceOf[Template].constr

    ExpansionHelpers.placeMembersInPrimary(
      fixture.input(None),
      List(constructor)
    ) match
      case ExpansionOutcome.Rejected(diagnostics, fallback) =>
        assert(fallback.eq(fixture.primary), clue(fallback))
        assertEquals(diagnostics.size, 1)
        assert(diagnostics.head.message.contains("unusable direct term name `<init>`"), clue(diagnostics))
      case other => fail(s"expected Rejected, found $other")
  }

  private final case class Fixture(
      primary: TypeDef,
      companion: Option[ModuleDef],
      generatedMethod: DefDef,
      generatedValue: ValDef,
      generatedType: TypeDef,
      currentAnnotation: Tree,
      context: Context
  ):
    def input(existingCompanion: Option[ModuleDef]): ExpansionInput =
      ExpansionInput(
        "current",
        primary,
        existingCompanion,
        Set("PlacementSubject", "GeneratedOwner"),
        Some(currentAnnotation)
      )

  private def parsedFixture(
      primaryDefinition: String,
      primaryBody: String = "",
      companionBody: String = ""
  ): Fixture =
    val primary =
      if primaryBody.isEmpty then primaryDefinition
      else s"$primaryDefinition:\n${indent(primaryBody)}"
    val companion =
      if companionBody.isEmpty then ""
      else s"object PlacementSubject:\n${indent(companionBody)}"
    val source =
      s"""@current @later @unhandled
         |$primary
         |$companion
         |object GeneratedOwner:
         |  def foo(x: Int): String = x.toString
         |  val answer: Int = 42
         |  type Unsupported = String
         |""".stripMargin
    val unit = CompilationUnit("GeneratedMemberPlacementHelperSpec.scala", source)
    val context = ContextBase().initialCtx.fresh.setCompilationUnit(unit)
    val parsed = new Parsers.Parser(unit.source)(using context).parse()
    val stats = parsed match
      case PackageDef(_, values) => values
      case tree => List(tree)
    val primaryTree = typeDefNamed(stats, "PlacementSubject")
    val existingCompanion = stats.collectFirst:
      case value: ModuleDef if value.name.toString == "PlacementSubject" => value
    val generatedOwner = stats.collectFirst:
      case value: ModuleDef if value.name.toString == "GeneratedOwner" => value
    .getOrElse(fail(s"missing generated owner in $stats"))
    val generatedMembers = generatedOwner.impl.body(using context)
    val generatedMethod = generatedMembers.collectFirst:
      case value: DefDef if value.name.toString == "foo" => value
    .getOrElse(fail(s"missing generated method in $generatedMembers"))
    val generatedValue = generatedMembers.collectFirst:
      case value: ValDef if value.name.toString == "answer" => value
    .getOrElse(fail(s"missing generated value in $generatedMembers"))
    val generatedType = generatedMembers.collectFirst:
      case value: TypeDef if value.name.toString == "Unsupported" => value
    .getOrElse(fail(s"missing generated type in $generatedMembers"))
    val currentAnnotation = Trees.mods(primaryTree).annotations.headOption
      .getOrElse(fail("missing current annotation"))
    Fixture(
      primaryTree,
      existingCompanion,
      generatedMethod,
      generatedValue,
      generatedType,
      currentAnnotation,
      context
    )

  private def typeDefNamed(stats: List[Tree], name: String): TypeDef =
    stats.collectFirst:
      case value: TypeDef if value.name.toString == name => value
    .getOrElse(fail(s"missing TypeDef $name in $stats"))

  private def indent(value: String): String =
    value.linesIterator.map(line => s"  $line").mkString("\n")

  private def structured(outcome: ExpansionOutcome): StructuredExpansionOutput =
    outcome match
      case ExpansionOutcome.Structured(output) => output
      case other => fail(s"expected Structured, found $other")
