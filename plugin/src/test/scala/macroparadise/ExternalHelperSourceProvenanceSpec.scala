package macroparadise

import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.ast.Trees
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Contexts.{Context, ContextBase}
import dotty.tools.dotc.parsing.Parsers
import paradise3.api.{ExpansionInput, ExpansionOutcome, StructuredExpansionOutput}
import paradise3.api.helpers.ExpansionHelpers

class ExternalHelperSourceProvenanceSpec extends munit.FunSuite:
  test("class helper preserves copied spans and leaves generated method spans empty") {
    val fixture = parsedFixture()
    given Context = fixture.context
    val originalTemplate = fixture.primary.rhs.asInstanceOf[Template]
    val preservedMember = originalTemplate.body.head

    val output = structured:
      ExpansionHelpers.addStringMethodToClass(
        fixture.input(existingCompanion = None),
        "generatedLabel",
        "class-helper"
      )
    val copiedTemplate = output.primary.rhs.asInstanceOf[Template]
    val generatedMethod = methodNamed(copiedTemplate, "generatedLabel")

    reportPosition("class.original-primary", fixture.primary)
    reportPosition("class.original-template", originalTemplate)
    reportPosition("class.preserved-member", preservedMember)
    reportPosition("class.copied-primary", output.primary)
    reportPosition("class.copied-template", copiedTemplate)
    assert(!(output.primary eq fixture.primary))
    assert(!(copiedTemplate eq originalTemplate))
    assertEquals(output.primary.source, fixture.primary.source)
    assertEquals(output.primary.sourcePos.span, fixture.primary.sourcePos.span)
    assertEquals(copiedTemplate.source, originalTemplate.source)
    assertEquals(copiedTemplate.sourcePos.span, originalTemplate.sourcePos.span)
    assert(copiedTemplate.body.head eq preservedMember)
    assertGeneratedPosition("class.generated-method", generatedMethod, fixture.primary.source)
    assertGeneratedPosition("class.generated-return-type", generatedMethod.tpt, fixture.primary.source)
    assertGeneratedPosition("class.generated-body", generatedMethod.rhs, fixture.primary.source)
  }

  test("created companion and sibling retain the annotated source without fabricated spans") {
    val fixture = parsedFixture()
    given Context = fixture.context

    val companionOutput = structured:
      ExpansionHelpers.addStringMethodToCompanion(
        fixture.input(existingCompanion = None),
        "generatedCompanionLabel",
        "created-companion"
      )
    val createdCompanion = companionOutput.companion.getOrElse(fail("missing created companion"))
    val createdMethod = methodNamed(createdCompanion.impl, "generatedCompanionLabel")

    val siblingOutput = structured:
      ExpansionHelpers.addStringMethodSiblingClass(
        fixture.input(existingCompanion = None),
        "PositionSubjectMeta",
        "generatedSiblingLabel",
        "created-sibling"
      )
    val sibling = siblingOutput.additionalTopLevelDefinitions.collectFirst:
      case value: TypeDef => value
    .getOrElse(fail("missing generated sibling"))
    val siblingTemplate = sibling.rhs.asInstanceOf[Template]
    val siblingMethod = methodNamed(siblingTemplate, "generatedSiblingLabel")

    List(
      "created-companion.module" -> createdCompanion,
      "created-companion.template" -> createdCompanion.impl,
      "created-companion.method" -> createdMethod,
      "created-companion.return-type" -> createdMethod.tpt,
      "created-companion.body" -> createdMethod.rhs,
      "sibling.type" -> sibling,
      "sibling.template" -> siblingTemplate,
      "sibling.method" -> siblingMethod,
      "sibling.return-type" -> siblingMethod.tpt,
      "sibling.body" -> siblingMethod.rhs
    ).foreach: (role, tree) =>
      assertGeneratedPosition(role, tree, fixture.primary.source)
  }

  test("merged companion preserves its span and member identity while its new method has no span") {
    val fixture = parsedFixture()
    given Context = fixture.context
    val originalTemplate = fixture.companion.impl
    val preservedMember = originalTemplate.body.head

    val output = structured:
      ExpansionHelpers.addStringMethodToCompanion(
        fixture.input(existingCompanion = Some(fixture.companion)),
        "generatedCompanionLabel",
        "merged-companion"
      )
    val mergedCompanion = output.companion.getOrElse(fail("missing merged companion"))
    val mergedTemplate = mergedCompanion.impl
    val generatedMethod = methodNamed(mergedTemplate, "generatedCompanionLabel")

    reportPosition("merged-companion.original-module", fixture.companion)
    reportPosition("merged-companion.original-template", originalTemplate)
    reportPosition("merged-companion.preserved-member", preservedMember)
    reportPosition("merged-companion.copied-module", mergedCompanion)
    reportPosition("merged-companion.copied-template", mergedTemplate)
    assert(!(mergedCompanion eq fixture.companion))
    assert(!(mergedTemplate eq originalTemplate))
    assertEquals(mergedCompanion.source, fixture.companion.source)
    assertEquals(mergedCompanion.sourcePos.span, fixture.companion.sourcePos.span)
    assertEquals(mergedTemplate.source, originalTemplate.source)
    assertEquals(mergedTemplate.sourcePos.span, originalTemplate.sourcePos.span)
    assert(mergedTemplate.body.head eq preservedMember)
    assertGeneratedPosition("merged-companion.generated-method", generatedMethod, fixture.primary.source)
    assertGeneratedPosition("merged-companion.generated-return-type", generatedMethod.tpt, fixture.primary.source)
    assertGeneratedPosition("merged-companion.generated-body", generatedMethod.rhs, fixture.primary.source)
  }

  test("standalone string method retains its SourceFile and remains NoSpan") {
    val fixture = parsedFixture()
    given Context = fixture.context
    val method =
      ExpansionHelpers.stringReturningMethod(
        "standaloneGeneratedLabel",
        "standalone",
        fixture.primary.source
      )

    List(
      "standalone-method.def" -> method,
      "standalone-method.return-type" -> method.tpt,
      "standalone-method.body" -> method.rhs
    ).foreach: (role, tree) =>
      reportPosition(role, tree)
      assertEquals(tree.source, fixture.primary.source)
      assert(!tree.sourcePos.span.exists, clue(tree.sourcePos))
  }

  test("sibling helper rejection prefers the current annotation source position") {
    val fixture = parsedFixture()
    given Context = fixture.context
    val outcome =
      ExpansionHelpers.addStringMethodSiblingClass(
        fixture.input(existingCompanion = None).copy(topLevelNames = Set("PositionSubjectMeta")),
        "PositionSubjectMeta",
        "generatedSiblingLabel",
        "conflict"
      )
    val diagnostic = outcome match
      case ExpansionOutcome.Rejected(diagnostics, fallback) =>
        assert(fallback eq fixture.primary)
        diagnostics.headOption.getOrElse(fail("missing rejection diagnostic"))
      case other => fail(s"expected Rejected, found $other")

    assertEquals(diagnostic.pos, fixture.currentAnnotation.sourcePos)
  }

  test("sibling helper rejection falls back to the class for missing null or NoSpan annotation evidence") {
    val fixture = parsedFixture()
    given Context = fixture.context
    given dotty.tools.dotc.util.SourceFile = fixture.primary.source
    val noSpanAnnotation = Literal(Constant("not-a-positioned-annotation"))
    val currentAnnotations = List(
      None,
      Some(null.asInstanceOf[Tree]),
      Some(noSpanAnnotation)
    )

    currentAnnotations.foreach: currentAnnotation =>
      val outcome =
        ExpansionHelpers.addStringMethodSiblingClass(
          fixture.input(existingCompanion = None).copy(
            topLevelNames = Set("PositionSubjectMeta"),
            currentAnnotation = currentAnnotation
          ),
          "PositionSubjectMeta",
          "generatedSiblingLabel",
          "conflict"
        )
      val diagnostic = outcome match
        case ExpansionOutcome.Rejected(diagnostics, _) =>
          diagnostics.headOption.getOrElse(fail("missing rejection diagnostic"))
        case other => fail(s"expected Rejected, found $other")

      assertEquals(diagnostic.pos, fixture.primary.sourcePos)
  }

  test("plugin diagnostic position policy prefers a meaningful current annotation") {
    val fixture = parsedFixture()
    given Context = fixture.context

    val position =
      DiagnosticPositionPolicy.mostSpecific(
        Some(fixture.currentAnnotation),
        fixture.primary.sourcePos
      )

    assertEquals(position, fixture.currentAnnotation.sourcePos)
  }

  test("plugin diagnostic position policy falls back for missing null or NoSpan annotation evidence") {
    val fixture = parsedFixture()
    given Context = fixture.context
    given dotty.tools.dotc.util.SourceFile = fixture.primary.source
    val noSpanAnnotation = Literal(Constant("not-a-positioned-annotation"))
    val currentAnnotations = List(
      None,
      Some(null.asInstanceOf[Tree]),
      Some(noSpanAnnotation)
    )

    currentAnnotations.foreach: currentAnnotation =>
      assertEquals(
        DiagnosticPositionPolicy.mostSpecific(
          currentAnnotation,
          fixture.primary.sourcePos
        ),
        fixture.primary.sourcePos
      )
  }

  private final case class Fixture(
      sourceText: String,
      primary: TypeDef,
      companion: ModuleDef,
      currentAnnotation: Tree,
      context: Context
  ):
    def input(existingCompanion: Option[ModuleDef]): ExpansionInput =
      ExpansionInput(
        "externalSiblingDebug",
        primary,
        existingCompanion,
        Set("PositionSubject", "Neighbor"),
        Some(currentAnnotation)
      )

  private def parsedFixture(): Fixture =
    val sourceText =
      """@externalSiblingDebug
        |class PositionSubject:
        |  val preserved: Int = 1
        |
        |object PositionSubject:
        |  val preserved: Int = 2
        |""".stripMargin
    val unit = CompilationUnit("ExternalHelperSourcePositions.scala", sourceText)
    val context = ContextBase().initialCtx.fresh.setCompilationUnit(unit)
    val parsed = new Parsers.Parser(unit.source)(using context).parse()
    val stats = parsed match
      case PackageDef(_, values) => values
      case tree => List(tree)
    val primary = stats.collectFirst:
      case value: TypeDef if value.name.toString == "PositionSubject" => value
    .getOrElse(fail(s"missing primary in $stats"))
    val companion = stats.collectFirst:
      case value: ModuleDef if value.name.toString == "PositionSubject" => value
    .getOrElse(fail(s"missing companion in $stats"))
    val annotation = Trees.mods(primary).annotations.headOption.getOrElse(fail("missing annotation"))
    Fixture(sourceText, primary, companion, annotation, context)

  private def structured(outcome: ExpansionOutcome): StructuredExpansionOutput =
    outcome match
      case ExpansionOutcome.Structured(output) => output
      case other => fail(s"expected Structured, found $other")

  private def methodNamed(template: Template, name: String)(using Context): DefDef =
    template.body.collectFirst:
      case value: DefDef if value.name.toString == name => value
    .getOrElse(fail(s"missing method $name in ${template.body}"))

  private def assertGeneratedPosition(
      role: String,
      tree: Tree,
      expectedSource: dotty.tools.dotc.util.SourceFile
  )(using Context): Unit =
    assertEquals(tree.source, expectedSource, clue(tree))
    reportPosition(role, tree)
    val span = tree.sourcePos.span
    assert(!span.exists || span.isZeroExtent, clue(tree.sourcePos))

  private def reportPosition(role: String, tree: Tree)(using Context): Unit =
    val span = tree.sourcePos.span
    val coordinates =
      if span.exists then s"start=${span.start} end=${span.end} point=${span.point}"
      else "start=absent end=absent point=absent"
    println(
      s"EXTERNAL_HELPER_TREE_POSITION role=$role kind=${tree.getClass.getSimpleName} " +
        s"source=${tree.source.path} exists=${span.exists} synthetic=${span.isSynthetic} " +
        s"sourceDerived=${span.isSourceDerived} zeroExtent=${span.isZeroExtent} " +
        coordinates
    )
