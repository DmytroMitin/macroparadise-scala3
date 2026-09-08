package contractprobetype

import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags.Param
import dotty.tools.dotc.core.Names.typeName
import paradise3.api.{DefinitionPlacement, ExpansionAdmission, ExpansionEdit, ExpansionHandler, ExpansionInput, ExpansionOutcome, ExpansionShapeProfile, ExpansionTargetKind, expander}
import paradise3.api.helpers.{ExpansionHelpers, MemberConflictPolicy, MissingCompanionPolicy}
import scala.annotation.StaticAnnotation

@expander("contractprobetype.IndependentTypePlacementHandler")
final class IndependentTypePlacementMarker extends StaticAnnotation

@expander("contractprobetype.IndependentTypePlacementRejectHandler")
final class IndependentTypePlacementRejectMarker extends StaticAnnotation

final class IndependentTypePlacementHandler extends ExpansionHandler:
  val annotationName: String = "IndependentTypePlacementMarker"
  val admissions = List(ExpansionAdmission(ExpansionTargetKind.Trait, ExpansionShapeProfile.TwoInvariantUpperBoundedTypeParameters))

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionEdit.finish(
      ExpansionEdit.start(input).flatMap(edit => ExpansionHelpers.placeMemberInCompanion(
        edit,
        generatedType(input),
        MissingCompanionPolicy.Create(ExpansionTargetKind.Object, DefinitionPlacement.AfterPrimary),
        MemberConflictPolicy.PreserveExisting
      ))
    )

  private[contractprobetype] def generatedType(input: ExpansionInput)(using Context): untpd.TypeDef =
    given dotty.tools.dotc.util.SourceFile = input.primary.tree.source

    def upperBounded(name: String): untpd.TypeDef =
      untpd.TypeDef(
        typeName(name),
        untpd.TypeBoundsTree(EmptyTree, untpd.Ident(typeName("Nat")))
      ).withMods(untpd.Modifiers(Param)).asInstanceOf[untpd.TypeDef]

    val appliedTarget =
      untpd.AppliedTypeTree(
        untpd.Ident(typeName(input.primary.name)),
        List(untpd.Ident(typeName("N")), untpd.Ident(typeName("M")))
      )
    val refinedTarget =
      untpd.RefinedTypeTree(
        appliedTarget,
        List(untpd.TypeDef(typeName("Out"), untpd.Ident(typeName("Out0"))))
      )

    untpd.TypeDef(
      typeName("Aux"),
      untpd.LambdaTypeTree(
        List(upperBounded("N"), upperBounded("M"), upperBounded("Out0")),
        refinedTarget
      )
    )

final class IndependentTypePlacementRejectHandler extends ExpansionHandler:
  val annotationName: String = "IndependentTypePlacementRejectMarker"
  val admissions = List(ExpansionAdmission(ExpansionTargetKind.Trait, ExpansionShapeProfile.TwoInvariantUpperBoundedTypeParameters))

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionEdit.finish(
      ExpansionEdit.start(input).flatMap(edit => ExpansionHelpers.placeMemberInCompanion(
        edit,
        new IndependentTypePlacementHandler().generatedType(input),
        MissingCompanionPolicy.Create(ExpansionTargetKind.Object, DefinitionPlacement.AfterPrimary),
        MemberConflictPolicy.Reject
      ))
    )
