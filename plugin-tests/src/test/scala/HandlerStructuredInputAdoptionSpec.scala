import demo.{
  ExternalCompanionDebugExpander,
  ExternalDebugExpander,
  ExternalLabelExpander,
  ExternalSiblingDebugExpander,
  ExternalTypedLabelExpander
}
import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.ast.Trees
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Contexts.{Context, ContextBase}
import dotty.tools.dotc.parsing.Parsers
import paradise3.api.{
  AnnotationApplication,
  ExpansionCompositionPolicy,
  ExpansionDiagnostic,
  ExpansionInput,
  ExpansionOutcome,
  ParadiseAnnotationExpander
}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

class HandlerStructuredInputAdoptionSpec extends munit.FunSuite:
  test("external label uses the decoded class name in structured helper output") {
    val (input, context) =
      parsedInput("@externalLabel class LabelUser", "externalLabel", "LabelUser")
    given Context = context

    val outcome = new ExternalLabelExpander().expand(input)

    assertSuccessfulStringMethod(outcome, "externalLabel", "LabelUser")
  }

  test("external label decode failure is one exact rejection rather than NotApplicable") {
    val (input, context) =
      parsedInput("@externalLabel type InvalidLabel = String", "externalLabel", "InvalidLabel")
    given Context = context
    val expected = input.annotatedClassView.left.toOption.get

    val outcome = new ExternalLabelExpander().expand(input)

    assertExactRejection(outcome, input, expected)
    assert(!hasMethod(input.annotatedClass, "externalLabel"))
  }

  test("external typed label accepts positional string syntax") {
    val (input, context) = parsedInput(
      "@externalTypedLabel[Int](\"positional\") class PositionalTypedLabel",
      "externalTypedLabel",
      "PositionalTypedLabel"
    )
    given Context = context

    val outcome = new ExternalTypedLabelExpander().expand(input)

    assertSuccessfulStringMethod(outcome, "externalTypedLabel", "positional")
  }

  test("external typed label accepts named value syntax") {
    val (input, context) = parsedInput(
      "@externalTypedLabel[Int](value = \"named\") class NamedTypedLabel",
      "externalTypedLabel",
      "NamedTypedLabel"
    )
    given Context = context

    val outcome = new ExternalTypedLabelExpander().expand(input)

    assertSuccessfulStringMethod(outcome, "externalTypedLabel", "named")
  }

  test("external typed label class decode failure wins before annotation parsing") {
    val (parsed, context) = parsedInput(
      "@externalTypedLabel[Int](\"ignored\") type InvalidTypedLabel = String",
      "externalTypedLabel",
      "InvalidTypedLabel"
    )
    given Context = context
    val input = parsed.copy(currentAnnotation = None)
    val expectedViewDiagnostic = input.annotatedClassView.left.toOption.get
    val laterApplicationDiagnostic =
      AnnotationApplication.fromInput(input).left.toOption.get
    assertNotEquals(expectedViewDiagnostic.message, laterApplicationDiagnostic.message)

    val outcome = new ExternalTypedLabelExpander().expand(input)

    assertExactRejection(outcome, input, expectedViewDiagnostic)
  }

  test("external typed label preserves the missing current annotation diagnostic") {
    val (parsed, context) = parsedInput(
      "@externalTypedLabel[Int](\"ignored\") class MissingCurrentTypedLabel",
      "externalTypedLabel",
      "MissingCurrentTypedLabel"
    )
    given Context = context
    val input = parsed.copy(currentAnnotation = None)

    assertTypedLabelValidationFailure(input)
  }

  test("external typed label preserves the missing type argument diagnostic") {
    val (input, context) = parsedInput(
      "@externalTypedLabel(\"missing-type\") class MissingTypeTypedLabel",
      "externalTypedLabel",
      "MissingTypeTypedLabel"
    )
    given Context = context

    assertTypedLabelValidationFailure(input)
  }

  test("external typed label preserves the extra type argument diagnostic") {
    val (input, context) = parsedInput(
      "@externalTypedLabel[Int, String](\"extra-type\") class ExtraTypeTypedLabel",
      "externalTypedLabel",
      "ExtraTypeTypedLabel"
    )
    given Context = context

    assertTypedLabelValidationFailure(input)
  }

  test("external typed label preserves the missing term argument diagnostic") {
    val (input, context) = parsedInput(
      "@externalTypedLabel[Int]() class MissingTermTypedLabel",
      "externalTypedLabel",
      "MissingTermTypedLabel"
    )
    given Context = context

    assertTypedLabelValidationFailure(input)
  }

  test("external typed label preserves the extra term argument diagnostic") {
    val (input, context) = parsedInput(
      "@externalTypedLabel[Int](\"first\", \"second\") class ExtraTermTypedLabel",
      "externalTypedLabel",
      "ExtraTermTypedLabel"
    )
    given Context = context

    assertTypedLabelValidationFailure(input)
  }

  test("external typed label preserves the wrong named parameter diagnostic") {
    val (input, context) = parsedInput(
      "@externalTypedLabel[Int](other = \"wrong\") class WrongNamedTypedLabel",
      "externalTypedLabel",
      "WrongNamedTypedLabel"
    )
    given Context = context

    assertTypedLabelValidationFailure(input)
  }

  test("external typed label preserves the non-string-literal diagnostic") {
    val (input, context) = parsedInput(
      "val dynamic = \"dynamic\"; @externalTypedLabel[Int](dynamic) class NonLiteralTypedLabel",
      "externalTypedLabel",
      "NonLiteralTypedLabel"
    )
    given Context = context

    assertTypedLabelValidationFailure(input)
  }

  test("the complete helper-backed handler inventory has the expected identities") {
    val handlers: List[ParadiseAnnotationExpander] = List(
      new ExternalDebugExpander(),
      new ExternalCompanionDebugExpander(),
      new ExternalSiblingDebugExpander(),
      new ExternalLabelExpander(),
      new ExternalTypedLabelExpander()
    )

    assertEquals(
      handlers.map(_.annotationName),
      List(
        "externalDebug",
        "externalCompanionDebug",
        "externalSiblingDebug",
        "externalLabel",
        "externalTypedLabel"
      )
    )
    assertEquals(
      handlers.map(_.compositionPolicy),
      List.fill(handlers.size)(ExpansionCompositionPolicy.SourceOrdered)
    )
  }

  test("helper-backed and product-owned raw marker boundaries remain explicit") {
    val helperBacked = List(
      "ExternalDebugExpander.scala",
      "ExternalCompanionDebugExpander.scala",
      "ExternalSiblingDebugExpander.scala",
      "ExternalLabelExpander.scala",
      "ExternalTypedLabelExpander.scala"
    )
    helperBacked.foreach: fileName =>
      val source = handlerSource(fileName)
      assert(source.contains("ExpansionHelpers.withAnnotatedClassView(input)"), fileName)
    List("ExternalLabelExpander.scala", "ExternalTypedLabelExpander.scala").foreach: fileName =>
      assert(!handlerSource(fileName).contains("annotatedClass.rhs match"), fileName)

    val rawMarker = handlerSource("ExternalMarkerExpander.scala")
    assert(rawMarker.contains("input.annotatedClass.rhs match"))
    assert(rawMarker.contains("untpd.cpy.TypeDef"))
    assert(rawMarker.contains("ExpansionOutcome.Expanded"))
  }

  private def assertTypedLabelValidationFailure(
      input: ExpansionInput
  )(using Context): Unit =
    val expected = typedLabelValue(input).left.toOption.get
    val outcome = new ExternalTypedLabelExpander().expand(input)

    assertExactRejection(outcome, input, expected)
    assert(!hasMethod(input.annotatedClass, "externalTypedLabel"))

  private def typedLabelValue(
      input: ExpansionInput
  )(using Context): Either[ExpansionDiagnostic, String] =
    for
      application <- AnnotationApplication.fromInput(input)
      _ <- application.requireExactlyOneTypeArgument
      value <- application.requireSingleStringLiteralArgument("value")
    yield value

  private def assertExactRejection(
      outcome: ExpansionOutcome,
      input: ExpansionInput,
      expected: ExpansionDiagnostic
  ): Unit =
    outcome match
      case ExpansionOutcome.Rejected(diagnostics, fallback) =>
        assertEquals(diagnostics, List(expected))
        assert(fallback eq input.annotatedClass)
      case ExpansionOutcome.NotApplicable =>
        fail("expected one controlled rejection, found NotApplicable")
      case other =>
        fail(s"expected one controlled rejection without partial output, found $other")

  private def assertSuccessfulStringMethod(
      outcome: ExpansionOutcome,
      methodName: String,
      expectedValue: String
  )(using Context): Unit =
    outcome match
      case ExpansionOutcome.Structured(output) =>
        assertEquals(output.companion, None)
        assertEquals(output.additionalTopLevelDefinitions, Nil)
        val method =
          output.primary.rhs match
            case template: Template =>
              template.body
                .collectFirst {
                  case definition: DefDef
                      if definition.name.toString == methodName =>
                    definition
                }
                .getOrElse(fail(s"missing generated method $methodName"))
            case other =>
              fail(s"expected structured class template, found $other")
        method.rhs match
          case Literal(Constant(value: String)) =>
            assertEquals(value, expectedValue)
          case other =>
            fail(s"expected string literal body for $methodName, found $other")
      case other =>
        fail(s"expected structured helper output, found $other")

  private def hasMethod(
      definition: TypeDef,
      methodName: String
  )(using Context): Boolean =
    definition.rhs match
      case template: Template =>
        template.body.exists:
          case method: DefDef => method.name.toString == methodName
          case _ => false
      case _ => false

  private def parsedInput(
      source: String,
      annotationName: String,
      className: String
  ): (ExpansionInput, Context) =
    val unit = CompilationUnit("HandlerStructuredInputAdoptionSpec.scala", source)
    val context = ContextBase().initialCtx.fresh.setCompilationUnit(unit)
    val parsed = new Parsers.Parser(unit.source)(using context).parse()
    val stats = parsed match
      case PackageDef(_, values) => values
      case tree => List(tree)
    val annotated =
      stats
        .collectFirst {
          case value: TypeDef if value.name.toString == className => value
        }
        .getOrElse(fail(s"missing $className in $stats"))
    val names =
      stats.collect:
        case value: TypeDef => value.name.toString
        case value: ModuleDef => value.name.toString
      .toSet
    val currentAnnotation = Trees.mods(annotated).annotations.headOption
    (
      ExpansionInput(
        annotationName,
        annotated,
        None,
        names,
        currentAnnotation
      ),
      context
    )

  private def handlerSource(fileName: String): String =
    Files.readString(
      Path.of("plugin-test-handlers/src/main/scala/demo", fileName),
      StandardCharsets.UTF_8
    )
