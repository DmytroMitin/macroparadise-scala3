package external.traitprobe

import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags.{Given, Param}
import dotty.tools.dotc.core.Names.*
import paradise3.api.*
import paradise3.api.helpers.{ExpansionHelpers, MemberConflictPolicy, MissingCompanionPolicy}

import scala.annotation.StaticAnnotation

@expander("external.traitprobe.RestrictedApplyHandler")
final class RestrictedApply extends StaticAnnotation

@expander("external.traitprobe.DefaultClassOnlyHandler")
final class DefaultTraitAttempt extends StaticAnnotation

final class DefaultClassOnlyHandler extends ExpansionHandler:
  val annotationName: String = "DefaultTraitAttempt"
  val admissions = List(ExpansionAdmission(ExpansionTargetKind.Class, ExpansionShapeProfile.OrdinaryTemplate))

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.Rejected(List(ExpansionDiagnostic("unexpected default handler invocation", input.currentAnnotation.sourcePos)))

final class RestrictedApplyHandler extends ExpansionHandler:
  val annotationName: String = "RestrictedApply"
  val admissions = List(ExpansionAdmission(ExpansionTargetKind.Trait, ExpansionShapeProfile.OneInvariantUnboundedTypeParameter))

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    input.targetView match
      case Left(diagnostic) => ExpansionOutcome.Rejected(List(diagnostic))
      case Right(view) =>
        val parameterName = view.typeParameters.head.name
        ExpansionEdit.finish(
          ExpansionEdit.start(input).flatMap(edit => ExpansionHelpers.placeMemberInCompanion(
            edit,
            makeApply(input, parameterName),
            MissingCompanionPolicy.Create(ExpansionTargetKind.Object, DefinitionPlacement.AfterPrimary),
            MemberConflictPolicy.PreserveExisting
          ))
        )

  private def makeApply(
      input: ExpansionInput,
      parameterName: String
  )(using Context): DefDef =
    given dotty.tools.dotc.util.SourceFile = input.primary.tree.source
    val methodParameter =
      TypeDef(typeName(parameterName), TypeBoundsTree(EmptyTree, EmptyTree))
        .withMods(Modifiers(Param))
        .asInstanceOf[TypeDef]
    def appliedTrait: AppliedTypeTree =
      AppliedTypeTree(
        Ident(typeName(input.primary.name)),
        List(Ident(typeName(parameterName)))
      )
    val instance =
      ValDef(termName("instance"), appliedTrait, EmptyTree)
        .withMods(Modifiers(Param | Given))
        .asInstanceOf[ValDef]
    DefDef(
      termName("apply"),
      List(List(methodParameter), List(instance)),
      appliedTrait,
      Ident(termName("instance"))
    )
