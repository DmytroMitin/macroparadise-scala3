package macroparadise

import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.ast.Trees
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Contexts.{Context, ContextBase}
import dotty.tools.dotc.core.Names.*
import dotty.tools.dotc.parsing.Parsers
import paradise3.api.{ExpansionInput, ExpansionOutcome, StructuredExpansionOutput}
import paradise3.api.helpers.{CompanionModuleConflictPolicy, ExpansionHelpers}

class CompanionModulePlacementHelperSpec extends munit.FunSuite:
  test("missing companion receives the exact supplied syntax ModuleDef") {
    val fixture = parsedFixture()
    given Context = fixture.context

    val output = structured:
      ExpansionHelpers.addModuleToCompanion(
        fixture.input(None),
        fixture.generatedModule,
        CompanionModuleConflictPolicy.PreserveExisting
      )

    val companion = output.companion.getOrElse(fail("missing generated companion"))
    assertEquals(companion.name.toString, "PlacementSubject")
    val body = companion.impl.body
    assertEquals(body.size, 1)
    assert(body.head.eq(fixture.generatedModule), clue(body))
  }

  test("existing companion preserves exact shape, order, and identities before appending the supplied module") {
    val fixture = parsedFixture(existingBody = "val before: Int = 1\nval after: Int = 2")
    given Context = fixture.context
    val existing = fixture.companion.getOrElse(fail("missing existing companion"))
    val existingTemplate = existing.impl
    val existingBody = existingTemplate.body
    val existingMods = Trees.mods(existing)

    val output = structured:
      ExpansionHelpers.addModuleToCompanion(
        fixture.input(Some(existing)),
        fixture.generatedModule,
        CompanionModuleConflictPolicy.PreserveExisting
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
    assert(mergedTemplate.body.last.eq(fixture.generatedModule), clue(mergedTemplate.body))
    assert(mergedTemplate.constr.eq(existingTemplate.constr), clue(mergedTemplate.constr))
    assertEquals(mergedTemplate.parentsOrDerived, existingTemplate.parentsOrDerived)
    assertEquals(mergedTemplate.derived, existingTemplate.derived)
    assert(mergedTemplate.self.eq(existingTemplate.self), clue(mergedTemplate.self))
    assertEquals(Trees.mods(merged), existingMods)
    assertEquals(merged.sourcePos, existing.sourcePos)
    assertEquals(mergedTemplate.sourcePos, existingTemplate.sourcePos)
  }

  test("PreserveExisting returns the exact companion for a direct conflicting ModuleDef") {
    val fixture = parsedFixture(existingBody = "object syntax:\n  val existing: Int = 1")
    given Context = fixture.context
    val existing = fixture.companion.getOrElse(fail("missing existing companion"))

    val output = structured:
      ExpansionHelpers.addModuleToCompanion(
        fixture.input(Some(existing)),
        fixture.generatedModule,
        CompanionModuleConflictPolicy.PreserveExisting
      )

    assert(output.companion.getOrElse(fail("missing companion")).eq(existing), clue(output.companion))
    assertEquals(directTermMembersNamed(existing, "syntax").size, 1)
  }

  test("PreserveExisting treats direct DefDef and ValDef term names as conflicts") {
    List("def syntax: Int = 1", "val syntax: Int = 1").foreach: conflictingDefinition =>
      val fixture = parsedFixture(existingBody = conflictingDefinition)
      given Context = fixture.context
      val existing = fixture.companion.getOrElse(fail("missing existing companion"))

      val output = structured:
        ExpansionHelpers.addModuleToCompanion(
          fixture.input(Some(existing)),
          fixture.generatedModule,
          CompanionModuleConflictPolicy.PreserveExisting
        )

      assert(output.companion.getOrElse(fail("missing companion")).eq(existing), clue(conflictingDefinition))
      assertEquals(directTermMembersNamed(existing, "syntax").size, 1)
  }

  test("Reject reports a module-specific conflict and returns the untouched annotated fallback") {
    val fixture = parsedFixture(existingBody = "val syntax: Int = 1")
    given Context = fixture.context
    val existing = fixture.companion.getOrElse(fail("missing existing companion"))

    ExpansionHelpers.addModuleToCompanion(
      fixture.input(Some(existing)),
      fixture.generatedModule,
      CompanionModuleConflictPolicy.Reject
    ) match
      case ExpansionOutcome.Rejected(diagnostics, fallback) =>
        assert(fallback.eq(fixture.primary), clue(fallback))
        assertEquals(diagnostics.size, 1)
        assertEquals(
          diagnostics.head.message,
          "generated companion module `syntax` conflicts with existing direct companion term member `syntax` for `PlacementSubject`"
        )
        assertEquals(diagnostics.head.pos, fixture.currentAnnotation.sourcePos)
      case other => fail(s"expected Rejected, found $other")

    assertEquals(directTermMembersNamed(existing, "syntax").size, 1)
  }

  test("a direct TypeDef with the same decoded spelling is not a term conflict") {
    val fixture = parsedFixture(existingBody = "type syntax = String")
    given Context = fixture.context

    val output = structured:
      ExpansionHelpers.addModuleToCompanion(
        fixture.input(fixture.companion),
        fixture.generatedModule,
        CompanionModuleConflictPolicy.PreserveExisting
      )

    val merged = output.companion.getOrElse(fail("missing merged companion"))
    assertEquals(directTermMembersNamed(merged, "syntax"), List(fixture.generatedModule))
    assert(
      merged.impl.body.exists:
        case value: TypeDef => value.name.toString == "syntax"
        case _ => false,
      clue(merged.impl.body)
    )
  }

  test("a nested same-name definition is not a direct conflict") {
    val fixture = parsedFixture(existingBody = "object Nested:\n  object syntax:\n    val existing: Int = 1")
    given Context = fixture.context

    val output = structured:
      ExpansionHelpers.addModuleToCompanion(
        fixture.input(fixture.companion),
        fixture.generatedModule,
        CompanionModuleConflictPolicy.PreserveExisting
      )

    val merged = output.companion.getOrElse(fail("missing merged companion"))
    assertEquals(directTermMembersNamed(merged, "syntax"), List(fixture.generatedModule))
  }

  test("successful placement removes only the current annotation and preserves later identities and order") {
    val fixture = parsedFixture()
    given Context = fixture.context
    val originalAnnotations = Trees.mods(fixture.primary).annotations

    val output = structured:
      ExpansionHelpers.addModuleToCompanion(
        fixture.input(None),
        fixture.generatedModule,
        CompanionModuleConflictPolicy.PreserveExisting
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
      ExpansionHelpers.addModuleToCompanion(
        fixture.input(None).copy(currentAnnotation = None),
        fixture.generatedModule,
        CompanionModuleConflictPolicy.PreserveExisting
      )

    assertEquals(Trees.mods(output.primary).annotations, Nil)
  }

  test("unsupported annotated TypeDef shape returns NotApplicable") {
    val fixture = parsedFixture(primaryDefinition = "type PlacementSubject = String")
    given Context = fixture.context

    val outcome =
      ExpansionHelpers.addModuleToCompanion(
        fixture.input(None),
        fixture.generatedModule,
        CompanionModuleConflictPolicy.Reject
      )

    assert(outcome.eq(ExpansionOutcome.NotApplicable), clue(outcome))
  }

  test("placement never rebuilds or interprets the supplied module and its body") {
    val fixture = parsedFixture(existingBody = "val preserved: Int = 1")
    given Context = fixture.context
    val generatedBody = fixture.generatedModule.impl.body

    val output = structured:
      ExpansionHelpers.addModuleToCompanion(
        fixture.input(fixture.companion),
        fixture.generatedModule,
        CompanionModuleConflictPolicy.PreserveExisting
      )

    val inserted = directTermMembersNamed(
      output.companion.getOrElse(fail("missing companion")),
      "syntax"
    ).headOption.getOrElse(fail("missing inserted module"))
    assert(inserted.eq(fixture.generatedModule), clue(inserted))
    val insertedModule = inserted.asInstanceOf[ModuleDef]
    assert(insertedModule.impl.eq(fixture.generatedModule.impl), clue(insertedModule.impl))
    assert(
      insertedModule.impl.body.zip(generatedBody).forall:
        case (actual, expected) => actual.eq(expected),
      clue(insertedModule.impl.body)
    )
  }

  private final case class Fixture(
      primary: TypeDef,
      companion: Option[ModuleDef],
      generatedModule: ModuleDef,
      currentAnnotation: Tree,
      context: Context
  ):
    def input(existingCompanion: Option[ModuleDef]): ExpansionInput =
      ExpansionInput(
        "current",
        primary,
        existingCompanion,
        Set("PlacementSubject", "GeneratedModuleOwner"),
        Some(currentAnnotation)
      )

  private def parsedFixture(
      primaryDefinition: String = "trait PlacementSubject[A]",
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
         |object GeneratedModuleOwner:
         |  object syntax:
         |    val marker: String = "placed"
         |""".stripMargin
    val unit = CompilationUnit("CompanionModulePlacementHelperSpec.scala", source)
    val context = ContextBase().initialCtx.fresh.setCompilationUnit(unit)
    val parsed = new Parsers.Parser(unit.source)(using context).parse()
    val stats = parsed match
      case PackageDef(_, values) => values
      case tree => List(tree)
    val primary = typeDefNamed(stats, "PlacementSubject")
    val existingCompanion = stats.collectFirst:
      case value: ModuleDef if value.name.toString == "PlacementSubject" => value
    val generatedOwner = stats.collectFirst:
      case value: ModuleDef if value.name.toString == "GeneratedModuleOwner" => value
    .getOrElse(fail(s"missing generated module owner in $stats"))
    val generatedModule = generatedOwner.impl.body(using context).collectFirst:
      case value: ModuleDef if value.name.toString == "syntax" => value
    .getOrElse(fail(s"missing generated module in ${generatedOwner.impl.body(using context)}"))
    val currentAnnotation = Trees.mods(primary).annotations.headOption
      .getOrElse(fail("missing current annotation"))
    Fixture(primary, existingCompanion, generatedModule, currentAnnotation, context)

  private def directTermMembersNamed(
      companion: ModuleDef,
      name: String
  )(using Context): List[MemberDef] =
    companion.impl.body.collect:
      case member: MemberDef if member.name == termName(name) => member

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
