package macroparadise

import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.ast.Trees
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Contexts.{Context, ContextBase}
import dotty.tools.dotc.parsing.Parsers
import paradise3.api.{ExpansionInput, ExpansionOutcome, StructuredExpansionOutput}
import paradise3.api.helpers.{CompanionTypeConflictPolicy, ExpansionHelpers}

class CompanionTypePlacementHelperSpec extends munit.FunSuite:
  test("missing companion receives the exact supplied nontrivial generic refinement TypeDef") {
    val fixture = parsedFixture()
    given Context = fixture.context

    val output = structured:
      ExpansionHelpers.addTypeToCompanion(
        fixture.input(None),
        fixture.generatedType,
        CompanionTypeConflictPolicy.PreserveExisting
      )

    val companion = output.companion.getOrElse(fail("missing generated companion"))
    assertEquals(companion.name.toString, "PlacementSubject")
    val body = companion.impl.body
    assertEquals(body.size, 1)
    assert(body.head.eq(fixture.generatedType), clue(body))
    assert(fixture.generatedType.rhs.isInstanceOf[LambdaTypeTree], clue(fixture.generatedType.rhs))
  }

  test("existing companion preserves exact shape and order before appending the supplied TypeDef") {
    val fixture = parsedFixture(existingBody = "val before: Int = 1\nval after: Int = 2")
    given Context = fixture.context
    val existing = fixture.companion.getOrElse(fail("missing existing companion"))
    val existingTemplate = existing.impl
    val existingBody = existingTemplate.body
    val existingMods = Trees.mods(existing)

    val output = structured:
      ExpansionHelpers.addTypeToCompanion(
        fixture.input(Some(existing)),
        fixture.generatedType,
        CompanionTypeConflictPolicy.PreserveExisting
      )

    val merged = output.companion.getOrElse(fail("missing merged companion"))
    val mergedTemplate = merged.impl
    assert(!merged.eq(existing), clue(merged))
    assertEquals(mergedTemplate.body.size, existingBody.size + 1)
    assert(
      mergedTemplate.body.take(existingBody.size).zip(existingBody).forall:
        case (actual, expected) => actual.eq(expected),
      clue(mergedTemplate.body)
    )
    assert(mergedTemplate.body.last.eq(fixture.generatedType), clue(mergedTemplate.body))
    assert(mergedTemplate.constr.eq(existingTemplate.constr), clue(mergedTemplate.constr))
    assertEquals(mergedTemplate.parentsOrDerived, existingTemplate.parentsOrDerived)
    assertEquals(mergedTemplate.derived, existingTemplate.derived)
    assert(mergedTemplate.self.eq(existingTemplate.self), clue(mergedTemplate.self))
    assertEquals(Trees.mods(merged), existingMods)
    assertEquals(merged.sourcePos, existing.sourcePos)
    assertEquals(mergedTemplate.sourcePos, existingTemplate.sourcePos)
  }

  test("PreserveExisting returns the exact companion for a direct type-alias conflict") {
    val fixture = parsedFixture(existingBody = "type Aux = String")
    given Context = fixture.context
    val existing = fixture.companion.getOrElse(fail("missing existing companion"))

    val output = structured:
      ExpansionHelpers.addTypeToCompanion(
        fixture.input(Some(existing)),
        fixture.generatedType,
        CompanionTypeConflictPolicy.PreserveExisting
      )

    assert(output.companion.getOrElse(fail("missing companion")).eq(existing), clue(output.companion))
    assertEquals(directTypesNamed(existing, "Aux").size, 1)
  }

  test("Reject reports a type-specific conflict and returns the untouched annotated fallback") {
    val fixture = parsedFixture(existingBody = "type Aux = String")
    given Context = fixture.context
    val existing = fixture.companion.getOrElse(fail("missing existing companion"))

    ExpansionHelpers.addTypeToCompanion(
      fixture.input(Some(existing)),
      fixture.generatedType,
      CompanionTypeConflictPolicy.Reject
    ) match
      case ExpansionOutcome.Rejected(diagnostics, fallback) =>
        assert(fallback.eq(fixture.primary), clue(fallback))
        assertEquals(diagnostics.size, 1)
        assertEquals(
          diagnostics.head.message,
          "generated companion type `Aux` conflicts with existing direct companion type member `Aux` for `PlacementSubject`"
        )
        assertEquals(diagnostics.head.pos, fixture.currentAnnotation.sourcePos)
      case other => fail(s"expected Rejected, found $other")

    assertEquals(directTypesNamed(existing, "Aux").size, 1)
  }

  test("direct nested class and trait definitions occupy the raw type namespace") {
    List("class Aux", "trait Aux").foreach: conflictingDefinition =>
      val fixture = parsedFixture(existingBody = conflictingDefinition)
      given Context = fixture.context
      val existing = fixture.companion.getOrElse(fail("missing existing companion"))

      val output = structured:
        ExpansionHelpers.addTypeToCompanion(
          fixture.input(Some(existing)),
          fixture.generatedType,
          CompanionTypeConflictPolicy.PreserveExisting
        )

      assert(output.companion.getOrElse(fail("missing companion")).eq(existing), clue(conflictingDefinition))
      assertEquals(directTypesNamed(existing, "Aux").size, 1)
  }

  test("a direct term-only definition with the same spelling is not a type conflict") {
    val fixture = parsedFixture(existingBody = "val Aux: Int = 1")
    given Context = fixture.context
    val existing = fixture.companion.getOrElse(fail("missing existing companion"))

    val output = structured:
      ExpansionHelpers.addTypeToCompanion(
        fixture.input(Some(existing)),
        fixture.generatedType,
        CompanionTypeConflictPolicy.PreserveExisting
      )

    val merged = output.companion.getOrElse(fail("missing merged companion"))
    assertEquals(directTypesNamed(merged, "Aux"), List(fixture.generatedType))
    assert(
      merged.impl.body.exists:
        case value: ValDef => value.name.toString == "Aux"
        case _ => false,
      clue(merged.impl.body)
    )
  }

  test("a nested non-direct same-name type is not a conflict") {
    val fixture = parsedFixture(existingBody = "object Nested:\n  type Aux = String")
    given Context = fixture.context

    val output = structured:
      ExpansionHelpers.addTypeToCompanion(
        fixture.input(fixture.companion),
        fixture.generatedType,
        CompanionTypeConflictPolicy.PreserveExisting
      )

    val merged = output.companion.getOrElse(fail("missing merged companion"))
    assertEquals(directTypesNamed(merged, "Aux"), List(fixture.generatedType))
  }

  test("successful placement removes only the exact current annotation and preserves later identities") {
    val fixture = parsedFixture()
    given Context = fixture.context
    val originalAnnotations = Trees.mods(fixture.primary).annotations

    val output = structured:
      ExpansionHelpers.addTypeToCompanion(
        fixture.input(None),
        fixture.generatedType,
        CompanionTypeConflictPolicy.PreserveExisting
      )

    val remaining = Trees.mods(output.primary).annotations
    assertEquals(originalAnnotations.size, 3)
    assert(!remaining.exists(_ eq fixture.currentAnnotation))
    assertEquals(remaining.size, 2)
    assert(
      remaining.zip(originalAnnotations.tail).forall:
        case (actual, expected) => actual.eq(expected),
      clue(remaining)
    )
  }

  test("missing currentAnnotation retains the legacy clear-all fallback") {
    val fixture = parsedFixture()
    given Context = fixture.context

    val output = structured:
      ExpansionHelpers.addTypeToCompanion(
        fixture.input(None).copy(currentAnnotation = None),
        fixture.generatedType,
        CompanionTypeConflictPolicy.PreserveExisting
      )

    assertEquals(Trees.mods(output.primary).annotations, Nil)
  }

  test("unsupported annotated TypeDef shape returns NotApplicable") {
    val fixture = parsedFixture(primaryDefinition = "type PlacementSubject = String")
    given Context = fixture.context

    val outcome =
      ExpansionHelpers.addTypeToCompanion(
        fixture.input(None),
        fixture.generatedType,
        CompanionTypeConflictPolicy.Reject
      )

    assert(outcome.eq(ExpansionOutcome.NotApplicable), clue(outcome))
  }

  test("placement never rebuilds the supplied TypeDef or its generic refinement shape") {
    val fixture = parsedFixture(existingBody = "val preserved: Int = 1")
    given Context = fixture.context

    val output = structured:
      ExpansionHelpers.addTypeToCompanion(
        fixture.input(fixture.companion),
        fixture.generatedType,
        CompanionTypeConflictPolicy.PreserveExisting
      )

    val inserted = directTypesNamed(
      output.companion.getOrElse(fail("missing companion")),
      "Aux"
    ).headOption.getOrElse(fail("missing inserted type"))
    assert(inserted.eq(fixture.generatedType), clue(inserted))
    assert(inserted.rhs.eq(fixture.generatedType.rhs), clue(inserted.rhs))
  }

  private final case class Fixture(
      primary: TypeDef,
      companion: Option[ModuleDef],
      generatedType: TypeDef,
      currentAnnotation: Tree,
      context: Context
  ):
    def input(existingCompanion: Option[ModuleDef]): ExpansionInput =
      ExpansionInput(
        "current",
        primary,
        existingCompanion,
        Set("PlacementSubject", "GeneratedTypeOwner"),
        Some(currentAnnotation)
      )

  private def parsedFixture(
      primaryDefinition: String = "trait PlacementSubject[N <: Nat, M <: Nat]:\n  type Out <: Nat",
      existingBody: String = ""
  ): Fixture =
    val companion =
      if existingBody.isEmpty then ""
      else
        s"""
           |object PlacementSubject extends Serializable:
           |  self =>
           |${indent(existingBody)}
           |""".stripMargin
    val source =
      s"""@current @later @unhandled
         |$primaryDefinition
         |$companion
         |object GeneratedTypeOwner:
         |  type Aux[N <: Nat, M <: Nat, Out0 <: Nat] =
         |    PlacementSubject[N, M] { type Out = Out0 }
         |""".stripMargin
    val unit = CompilationUnit("CompanionTypePlacementHelperSpec.scala", source)
    val context = ContextBase().initialCtx.fresh.setCompilationUnit(unit)
    val parsed = new Parsers.Parser(unit.source)(using context).parse()
    val stats = parsed match
      case PackageDef(_, values) => values
      case tree => List(tree)
    val primary = typeDefNamed(stats, "PlacementSubject")
    val existingCompanion = stats.collectFirst:
      case value: ModuleDef if value.name.toString == "PlacementSubject" => value
    val generatedOwner = stats.collectFirst:
      case value: ModuleDef if value.name.toString == "GeneratedTypeOwner" => value
    .getOrElse(fail(s"missing generated type owner in $stats"))
    val generatedType = generatedOwner.impl.body(using context).collectFirst:
      case value: TypeDef if value.name.toString == "Aux" => value
    .getOrElse(fail(s"missing generated type in ${generatedOwner.impl.body(using context)}"))
    val currentAnnotation = Trees.mods(primary).annotations.headOption
      .getOrElse(fail("missing current annotation"))
    Fixture(primary, existingCompanion, generatedType, currentAnnotation, context)

  private def directTypesNamed(
      companion: ModuleDef,
      name: String
  )(using Context): List[TypeDef] =
    companion.impl.body.collect:
      case member: TypeDef if member.name.toString == name => member

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
