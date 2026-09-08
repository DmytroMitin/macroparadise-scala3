package demo

import dotty.tools.dotc.ast.Trees
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Names.*
import paradise3.api.*
import paradise3.api.helpers.{ExpansionHelpers, MissingCompanionPolicy}

private object StandardUnifiedExpanders:
  val classAdmission =
    List(ExpansionAdmission(ExpansionTargetKind.Class, ExpansionShapeProfile.OrdinaryTemplate))

  def addPrimaryStringMethod(
      input: ExpansionInput,
      methodName: String,
      value: String
  )(using Context): ExpansionOutcome =
    ExpansionEdit.finish:
      for
        start <- ExpansionEdit.start(input)
        stripped <- ExpansionHelpers.replacePrimaryAnnotations(
          start,
          Trees.mods(input.primary.tree.asInstanceOf[TypeDef]).annotations.filterNot(_ eq input.currentAnnotation)
        )
        edited <- ExpansionHelpers.placeMemberInPrimary(stripped, stringMethod(methodName, value))
      yield edited

  def addCompanionStringMethod(
      input: ExpansionInput,
      methodName: String,
      value: String
  )(using Context): ExpansionOutcome =
    ExpansionEdit.finish:
      for
        start <- ExpansionEdit.start(input)
        stripped <- ExpansionHelpers.replacePrimaryAnnotations(
          start,
          Trees.mods(input.primary.tree.asInstanceOf[TypeDef]).annotations.filterNot(_ eq input.currentAnnotation)
        )
        edited <- ExpansionHelpers.placeMemberInCompanion(
          stripped,
          stringMethod(methodName, value),
          MissingCompanionPolicy.Create(ExpansionTargetKind.Object, DefinitionPlacement.AfterPrimary)
        )
      yield edited

  def stringMethod(name: String, value: String)(using Context): DefDef =
    DefDef(termName(name), Nil, Ident(typeName("String")), Literal(Constant(value)))

final class ExternalDebugExpander extends ExpansionHandler:
  val annotationName = "externalDebug"
  val admissions = StandardUnifiedExpanders.classAdmission
  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    StandardUnifiedExpanders.addPrimaryStringMethod(input, "externalDebugName", input.primary.name)

final class LegacyExternalDebugExpander extends ExpansionHandler:
  val annotationName = "legacyExternalDebug"
  val admissions = StandardUnifiedExpanders.classAdmission
  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    StandardUnifiedExpanders.addPrimaryStringMethod(input, "legacyExternalDebugName", input.primary.name)

final class ExternalCompanionDebugExpander extends ExpansionHandler:
  val annotationName = "externalCompanionDebug"
  val admissions = StandardUnifiedExpanders.classAdmission
  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    StandardUnifiedExpanders.addCompanionStringMethod(input, "externalCompanionDebugName", input.primary.name)

final class ExternalLabelExpander extends ExpansionHandler:
  val annotationName = "externalLabel"
  val admissions = StandardUnifiedExpanders.classAdmission
  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    StandardUnifiedExpanders.addPrimaryStringMethod(input, "externalLabel", input.primary.name)

final class ExternalTypedLabelExpander extends ExpansionHandler:
  val annotationName = "externalTypedLabel"
  val admissions = StandardUnifiedExpanders.classAdmission
  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    val value = for
      application <- AnnotationApplication.fromInput(input)
      _ <- application.requireExactlyOneTypeArgument
      literal <- application.requireSingleStringLiteralArgument("value")
    yield literal
    value match
      case Right(literal) =>
        StandardUnifiedExpanders.addPrimaryStringMethod(input, "externalTypedLabel", literal)
      case Left(diagnostic) => ExpansionOutcome.Rejected(List(diagnostic))
