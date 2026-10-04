package p218.marker

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}

import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Names.{termName, typeName}
import paradise3.apiary.MarkerConstructorType
import paradise3.api.*
import paradise3.api.embeddedExpander
import paradise3.api.helpers.{ExpansionTransforms, MissingCompanionPolicy}
import scala.annotation.StaticAnnotation

private object EmbeddedEvidence:
  def record(line: String): Unit =
    Option(System.getProperty("p218.evidence")).foreach: raw =>
      Files.writeString(
        Path.of(raw),
        line + System.lineSeparator,
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND
      )

  def hasMethod(input: ExpansionInput, name: String)(using Context): Boolean =
    input.primary.tree match
      case root: TypeDef =>
        root.rhs match
          case template: Template => template.body.exists:
            case method: DefDef => method.name.toString == name
            case _ => false
          case _ => false
      case _ => false

  def stringMethod(name: String, value: String)(using Context): DefDef =
    DefDef(termName(name), Nil, Ident(typeName("String")), Literal(Constant(value)))

  def reject(input: ExpansionInput, message: String)(using Context): ExpansionOutcome =
    ExpansionOutcome.Rejected(List(ExpansionDiagnostic(message, input.currentAnnotation.sourcePos)))

@embeddedExpander
final class identityEmbedded extends StaticAnnotation

object identityEmbedded:
  def transform(input: ExpansionInput)(using Context): ExpansionOutcome =
    input.primary match
      case ExpansionTarget.Class(_) =>
        require(
          input.sourceOrderedHandledAnnotationNames == List("p218.marker.identityEmbedded"),
          s"P218_IDENTITY_COHORT_MISMATCH:${input.sourceOrderedHandledAnnotationNames.mkString(",")}"
        )
        EmbeddedEvidence.record(
          s"identity class=${input.primary.name} cohort=${input.sourceOrderedHandledAnnotationNames.mkString(",")}"
        )
        ExpansionEdit.finish(ExpansionEdit.start(input))
      case _ => EmbeddedEvidence.reject(input, "P218_IDENTITY_CLASS_REQUIRED")

@embeddedExpander
final class addGreeting(prefix: String) extends StaticAnnotation

object addGreeting:
  def producerHelper: String = "producer-helper"

  object Extractor:
    def unapply(value: String): Option[String] = Some(value)

  def transform(input: ExpansionInput)(using Context): ExpansionOutcome =
    input.primary match
      case ExpansionTarget.Class(_) =>
        require(
          SamePackageShortReferenceProbe.helper == "producer-helper",
          s"P218_COMPANION_REFERENCE_LOWERING:${SamePackageShortReferenceProbe.helper}"
        )
        val expectedCohort = input.primary.name match
          case "EmbeddedThenOrdinary" =>
            List("p218.marker.addGreeting", "p218.marker.ordinary")
          case "OrdinaryThenEmbedded" =>
            List("p218.marker.ordinary", "p218.marker.addGreeting")
          case _ => List("p218.marker.addGreeting")
        require(
          input.sourceOrderedHandledAnnotationNames == expectedCohort,
          s"P218_EMBEDDED_COHORT_MISMATCH:${input.sourceOrderedHandledAnnotationNames.mkString(",")}"
        )
        val decoded = for
          application <- AnnotationApplication.fromInput(input)
          prefix <- application.requireSingleStringLiteralArgument("prefix")
        yield application -> prefix
        decoded match
          case Left(problem) => ExpansionOutcome.Rejected(List(problem))
          case Right((application, prefix)) =>
            val seesOrdinary = EmbeddedEvidence.hasMethod(input, "ordinaryStage")
            val syntax = application.termArguments.headOption match
              case Some(AnnotationTermArgument.Named(name, _, _)) => s"named:$name"
              case Some(AnnotationTermArgument.Positional(_, _)) => "positional"
              case None => "missing"
            val value =
              s"$prefix:${if seesOrdinary then "saw-ordinary" else "before-ordinary"}:P218_EDIT_V1"
            EmbeddedEvidence.record(
              s"embedded class=${input.primary.name} syntax=$syntax applicationName=${application.annotationName} seesOrdinary=$seesOrdinary cohort=${input.sourceOrderedHandledAnnotationNames.mkString(",")}"
            )
            ExpansionEdit.finish:
              ExpansionEdit.start(input).flatMap(
                ExpansionTransforms.placeMemberInPrimary(
                  EmbeddedEvidence.stringMethod("generatedGreeting", value)
                )
              )
      case _ => EmbeddedEvidence.reject(input, "P218_ADD_GREETING_CLASS_REQUIRED")

import p218.marker.addGreeting.producerHelper
import _root_.dotty.tools.dotc.core.Names.{termName as handlerOnlyTermName}
import _root_.paradise3.api.{ExpansionInput as HandlerOnlyExpansionInput}

@embeddedExpander
final class companionEmbedded extends StaticAnnotation

object companionEmbedded:
  def transform(input: ExpansionInput)(using Context): ExpansionOutcome =
    input.primary match
      case ExpansionTarget.Class(_) =>
        require(
          input.sourceOrderedHandledAnnotationNames == List("p218.marker.companionEmbedded"),
          s"P218_COMPANION_COHORT_MISMATCH:${input.sourceOrderedHandledAnnotationNames.mkString(",")}"
        )
        EmbeddedEvidence.record(
          s"companion class=${input.primary.name} existing=${input.companion.nonEmpty} cohort=${input.sourceOrderedHandledAnnotationNames.mkString(",")}"
        )
        ExpansionEdit.finish:
          ExpansionEdit.start(input).flatMap(
            ExpansionTransforms.placeMemberInCompanion(
              EmbeddedEvidence.stringMethod("embeddedCompanionValue", input.primary.name),
              MissingCompanionPolicy.Create(
                ExpansionTargetKind.Object,
                DefinitionPlacement.AfterPrimary
              )
            )
          )
      case _ => EmbeddedEvidence.reject(input, "P218_COMPANION_CLASS_REQUIRED")

@embeddedExpander
final class defaultedEmbedded(
    prefix: String = "Default",
    markerType: MarkerConstructorType = null
) extends StaticAnnotation

object defaultedEmbedded:
  def transform(input: ExpansionInput)(using Context): ExpansionOutcome =
    input.primary match
      case ExpansionTarget.Class(_) =>
        AnnotationApplication.fromInput(input) match
          case Left(problem) => ExpansionOutcome.Rejected(List(problem))
          case Right(application) =>
            require(
              application.termArguments.isEmpty,
              s"P218_DEFAULT_WAS_EVALUATED:${application.termArguments.size}"
            )
            EmbeddedEvidence.record(
              s"defaulted class=${input.primary.name} explicitTerms=${application.termArguments.size}"
            )
            ExpansionEdit.finish(ExpansionEdit.start(input))
      case _ => EmbeddedEvidence.reject(input, "P218_DEFAULTED_CLASS_REQUIRED")

@embeddedExpander
final class genericEmbedded[A] extends StaticAnnotation

object genericEmbedded:
  def transform(input: ExpansionInput)(using Context): ExpansionOutcome =
    input.primary match
      case ExpansionTarget.Class(_) =>
        val decoded = for
          application <- AnnotationApplication.fromInput(input)
          argument <- application.requireExactlyOneTypeArgument
        yield argument
        decoded match
          case Left(problem) => ExpansionOutcome.Rejected(List(problem))
          case Right(argument) =>
            EmbeddedEvidence.record(
              s"generic class=${input.primary.name} typeArgument=${argument.show}"
            )
            ExpansionEdit.finish(ExpansionEdit.start(input))
      case _ => EmbeddedEvidence.reject(input, "P218_GENERIC_CLASS_REQUIRED")
