package p218.handler

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}

import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Names.{termName, typeName}
import paradise3.api.*
import paradise3.api.helpers.ExpansionTransforms

final class OrdinaryHandler extends ExpansionHandler:
  val annotationName: String = "p218.marker.ordinary"

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    input.primary match
      case ExpansionTarget.Class(root) =>
        val expectedCohort = input.primary.name match
          case "EmbeddedThenOrdinary" =>
            List("p218.marker.addGreeting", "p218.marker.ordinary")
          case "OrdinaryThenEmbedded" =>
            List("p218.marker.ordinary", "p218.marker.addGreeting")
        require(
          input.sourceOrderedHandledAnnotationNames == expectedCohort,
          s"P218_ORDINARY_COHORT_MISMATCH:${input.sourceOrderedHandledAnnotationNames.mkString(",")}"
        )
        val seesEmbedded = root.rhs match
          case template: Template => template.body.exists:
            case method: DefDef => method.name.toString == "generatedGreeting"
            case _ => false
          case _ => false
        record(
          s"ordinary class=${input.primary.name} seesEmbedded=$seesEmbedded cohort=${input.sourceOrderedHandledAnnotationNames.mkString(",")}"
        )
        val method = DefDef(
          termName("ordinaryStage"),
          Nil,
          Ident(typeName("String")),
          Literal(Constant(if seesEmbedded then "saw-embedded" else "before-embedded"))
        )
        ExpansionEdit.finish:
          ExpansionEdit.start(input).flatMap(ExpansionTransforms.placeMemberInPrimary(method))
      case _ =>
        ExpansionOutcome.Rejected(
          List(ExpansionDiagnostic("P218_ORDINARY_CLASS_REQUIRED", input.currentAnnotation.sourcePos))
        )

  private def record(line: String): Unit =
    Option(System.getProperty("p218.evidence")).foreach: raw =>
      Files.writeString(
        Path.of(raw),
        line + System.lineSeparator,
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND
      )
