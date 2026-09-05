package macroparadise

import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.ast.Trees
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Contexts.{Context, ContextBase}
import dotty.tools.dotc.core.Names.{termName, typeName}
import dotty.tools.dotc.parsing.Parsers
import dotty.tools.dotc.util.{NoSource, SourceFile}
import paradise3.api.{ExpansionInput, ExpansionOutcome, StructuredExpansionOutput}
import paradise3.api.helpers.{CompanionMethodConflictPolicy, ExpansionHelpers}

class CompanionMethodPlacementHelperSpec extends munit.FunSuite:
  test("missing companion receives the exact supplied contextual generic DefDef") {
    val fixture = parsedFixture()
    given Context = fixture.context

    val output = structured:
      ExpansionHelpers.addMethodToCompanion(
        fixture.input(None),
        fixture.generatedMethod,
        CompanionMethodConflictPolicy.PreserveExisting
      )

    val companion = output.companion.getOrElse(fail("missing generated companion"))
    assertEquals(companion.name.toString, "PlacementSubject")
    val body = companion.impl.body
    assertEquals(body.size, 1)
    assert(body.head.eq(fixture.generatedMethod), clue(body))
    assertEquals(fixture.generatedMethod.paramss.size, 2)
  }

  test("existing companion preserves direct member order and appends the exact supplied method") {
    val fixture = parsedFixture(existingBody = "val before: Int = 1\nval after: Int = 2")
    given Context = fixture.context
    val existing = fixture.companion.getOrElse(fail("missing existing companion"))
    val existingTemplate = existing.impl
    val existingBody = existingTemplate.body
    val existingMods = Trees.mods(existing)

    val output = structured:
      ExpansionHelpers.addMethodToCompanion(
        fixture.input(Some(existing)),
        fixture.generatedMethod,
        CompanionMethodConflictPolicy.PreserveExisting
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
    assert(mergedTemplate.body.last.eq(fixture.generatedMethod), clue(mergedTemplate.body))
    assert(mergedTemplate.constr.eq(existingTemplate.constr), clue(mergedTemplate.constr))
    assertEquals(mergedTemplate.parentsOrDerived, existingTemplate.parentsOrDerived)
    assertEquals(mergedTemplate.derived, existingTemplate.derived)
    assert(mergedTemplate.self.eq(existingTemplate.self), clue(mergedTemplate.self))
    assertEquals(Trees.mods(merged), existingMods)
    assertEquals(merged.sourcePos, existing.sourcePos)
    assertEquals(mergedTemplate.sourcePos, existingTemplate.sourcePos)
  }

  test("PreserveExisting returns the exact companion for a direct conflicting DefDef") {
    val fixture = parsedFixture(existingBody = "def apply[A](using inst: Show[A]): Show[A] = inst")
    given Context = fixture.context
    val existing = fixture.companion.getOrElse(fail("missing existing companion"))

    val output = structured:
      ExpansionHelpers.addMethodToCompanion(
        fixture.input(Some(existing)),
        fixture.generatedMethod,
        CompanionMethodConflictPolicy.PreserveExisting
      )

    assert(output.companion.getOrElse(fail("missing companion")).eq(existing), clue(output.companion))
    assertEquals(directMembersNamed(existing, "apply").size, 1)
  }

  test("PreserveExisting treats a direct non-DefDef MemberDef name as a conflict") {
    val fixture = parsedFixture(existingBody = "val apply: Int = 1")
    given Context = fixture.context
    val existing = fixture.companion.getOrElse(fail("missing existing companion"))

    val output = structured:
      ExpansionHelpers.addMethodToCompanion(
        fixture.input(Some(existing)),
        fixture.generatedMethod,
        CompanionMethodConflictPolicy.PreserveExisting
      )

    assert(output.companion.getOrElse(fail("missing companion")).eq(existing), clue(output.companion))
    assertEquals(directMembersNamed(existing, "apply").size, 1)
  }

  test("a nested same-name method is not a direct conflict") {
    val fixture = parsedFixture(existingBody = "object Nested:\n  def apply(value: Int): Int = value")
    given Context = fixture.context
    val existing = fixture.companion.getOrElse(fail("missing existing companion"))

    val output = structured:
      ExpansionHelpers.addMethodToCompanion(
        fixture.input(Some(existing)),
        fixture.generatedMethod,
        CompanionMethodConflictPolicy.PreserveExisting
      )

    val merged = output.companion.getOrElse(fail("missing merged companion"))
    assertEquals(directMembersNamed(merged, "apply"), List(fixture.generatedMethod))
  }

  test("Reject returns one deterministic diagnostic with the untouched annotated fallback") {
    val fixture = parsedFixture(existingBody = "val apply: Int = 1")
    given Context = fixture.context
    val existing = fixture.companion.getOrElse(fail("missing existing companion"))

    ExpansionHelpers.addMethodToCompanion(
      fixture.input(Some(existing)),
      fixture.generatedMethod,
      CompanionMethodConflictPolicy.Reject
    ) match
      case ExpansionOutcome.Rejected(diagnostics, fallback) =>
        assert(fallback.eq(fixture.primary), clue(fallback))
        assertEquals(diagnostics.size, 1)
        assertEquals(
          diagnostics.head.message,
          "generated companion method `apply` conflicts with existing direct companion member `apply` for `PlacementSubject`"
        )
        assertEquals(diagnostics.head.pos, fixture.currentAnnotation.sourcePos)
      case other => fail(s"expected Rejected, found $other")

    assertEquals(directMembersNamed(existing, "apply").size, 1)
  }

  test("successful placement removes only the exact current annotation tree") {
    val fixture = parsedFixture()
    given Context = fixture.context

    val output = structured:
      ExpansionHelpers.addMethodToCompanion(
        fixture.input(None),
        fixture.generatedMethod,
        CompanionMethodConflictPolicy.PreserveExisting
      )

    val originalAnnotations = Trees.mods(fixture.primary).annotations
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

  test("successful placement preserves unrelated and later annotation identities") {
    val fixture = parsedFixture()
    given Context = fixture.context
    val preserved = Trees.mods(fixture.primary).annotations.tail

    val output = structured:
      ExpansionHelpers.addMethodToCompanion(
        fixture.input(None),
        fixture.generatedMethod,
        CompanionMethodConflictPolicy.PreserveExisting
      )

    val remaining = Trees.mods(output.primary).annotations
    assert(
      remaining.zip(preserved).forall:
        case (actual, expected) => actual.eq(expected),
      clue(remaining)
    )
  }

  test("missing currentAnnotation retains the deliberate legacy clear-all fallback") {
    val fixture = parsedFixture()
    given Context = fixture.context

    val output = structured:
      ExpansionHelpers.addMethodToCompanion(
        fixture.input(None).copy(currentAnnotation = None),
        fixture.generatedMethod,
        CompanionMethodConflictPolicy.PreserveExisting
      )

    assertEquals(Trees.mods(output.primary).annotations, Nil)
  }

  test("unsupported annotated TypeDef shape returns NotApplicable") {
    val fixture = parsedFixture(primaryDefinition = "type PlacementSubject = String")
    given Context = fixture.context

    val outcome =
      ExpansionHelpers.addMethodToCompanion(
        fixture.input(None),
        fixture.generatedMethod,
        CompanionMethodConflictPolicy.Reject
      )

    assert(outcome.eq(ExpansionOutcome.NotApplicable), clue(outcome))
  }

  test("placement never rebuilds the supplied method tree") {
    val fixture = parsedFixture(existingBody = "val preserved: Int = 1")
    given Context = fixture.context

    val output = structured:
      ExpansionHelpers.addMethodToCompanion(
        fixture.input(fixture.companion),
        fixture.generatedMethod,
        CompanionMethodConflictPolicy.PreserveExisting
      )

    val inserted = directMembersNamed(
      output.companion.getOrElse(fail("missing companion")),
      "apply"
    ).headOption.getOrElse(fail("missing inserted method"))
    assert(inserted.eq(fixture.generatedMethod), clue(inserted))
    assert(inserted.asInstanceOf[DefDef].tpt.eq(fixture.generatedMethod.tpt), clue(inserted))
    assert(inserted.asInstanceOf[DefDef].rhs.eq(fixture.generatedMethod.rhs), clue(inserted))
  }

  test("narrow method placement rejects a source-free DefDef before creating a companion") {
    val fixture = parsedFixture()
    given Context = fixture.context
    val sourceFree = sourceFreeMethod()

    assert(!sourceFree.source.exists, clue(sourceFree.source))
    assert(!sourceFree.span.exists, clue(sourceFree.span))

    ExpansionHelpers.addMethodToCompanion(
      fixture.input(None),
      sourceFree,
      CompanionMethodConflictPolicy.PreserveExisting
    ) match
      case ExpansionOutcome.Rejected(diagnostics, fallback) =>
        assert(fallback.eq(fixture.primary), clue(fallback))
        assertEquals(diagnostics.size, 1)
        assertEquals(
          diagnostics.head.message,
          "generated member `apply` for `PlacementSubject` has no usable source position; direct placement requires an insertion-ready positioned DefDef or ValDef"
        )
        assertEquals(diagnostics.head.pos, fixture.currentAnnotation.sourcePos)
      case other => fail(s"expected Rejected, found $other")

    assert(Trees.mods(fixture.primary).annotations.contains(fixture.currentAnnotation))
  }

  private final case class Fixture(
      primary: TypeDef,
      companion: Option[ModuleDef],
      generatedMethod: DefDef,
      currentAnnotation: Tree,
      context: Context
  ):
    def input(existingCompanion: Option[ModuleDef]): ExpansionInput =
      ExpansionInput(
        "current",
        primary,
        existingCompanion,
        Set("PlacementSubject", "GeneratedMethodOwner"),
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
           |object PlacementSubject:
           |${indent(existingBody)}
           |""".stripMargin
    val source =
      s"""@current @later @unhandled
         |$primaryDefinition
         |$companion
         |object GeneratedMethodOwner:
         |  def apply[A](using inst: Show[A]): Show[A] = inst
         |""".stripMargin
    val unit = CompilationUnit("CompanionMethodPlacementHelperSpec.scala", source)
    val context = ContextBase().initialCtx.fresh.setCompilationUnit(unit)
    val parsed = new Parsers.Parser(unit.source)(using context).parse()
    val stats = parsed match
      case PackageDef(_, values) => values
      case tree => List(tree)
    val primary = typeDefNamed(stats, "PlacementSubject")
    val existingCompanion = stats.collectFirst:
      case value: ModuleDef if value.name.toString == "PlacementSubject" => value
    val generatedOwner = stats.collectFirst:
      case value: ModuleDef if value.name.toString == "GeneratedMethodOwner" => value
    .getOrElse(fail(s"missing generated method owner in $stats"))
    val generatedMethod = generatedOwner.impl.body(using context).collectFirst:
      case value: DefDef if value.name.toString == "apply" => value
    .getOrElse(fail(s"missing generated method in ${generatedOwner.impl.body(using context)}"))
    val currentAnnotation = Trees.mods(primary).annotations.headOption
      .getOrElse(fail("missing current annotation"))
    Fixture(primary, existingCompanion, generatedMethod, currentAnnotation, context)

  private def sourceFreeMethod()(using Context): DefDef =
    given SourceFile = NoSource
    val parameter =
      ValDef(termName("value"), Ident(typeName("Int")), EmptyTree)
    DefDef(
      termName("apply"),
      List(List(parameter)),
      Ident(typeName("Int")),
      Ident(termName("value"))
    )

  private def directMembersNamed(
      companion: ModuleDef,
      name: String
  )(using Context): List[MemberDef] =
    companion.impl.body.collect:
      case member: MemberDef if member.name.toString == name => member

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
