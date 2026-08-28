package contractprobetype

import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags.Param
import dotty.tools.dotc.core.Names.typeName
import paradise3.api.{ExpansionInput, ExpansionOutcome, ExpansionTargetProfile, ParadiseAnnotationExpander, expander}
import paradise3.api.helpers.{CompanionTypeConflictPolicy, ExpansionHelpers}
import scala.annotation.StaticAnnotation

@expander("contractprobetype.IndependentTypePlacementHandler")
final class IndependentTypePlacementMarker extends StaticAnnotation

@expander("contractprobetype.IndependentTypePlacementRejectHandler")
final class IndependentTypePlacementRejectMarker extends StaticAnnotation

final class IndependentTypePlacementHandler extends ParadiseAnnotationExpander:
  val annotationName: String = "IndependentTypePlacementMarker"
  override val targetProfile: ExpansionTargetProfile =
    ExpansionTargetProfile.TwoUpperBoundedGenericTrait
  override val consumesExistingCompanion: Boolean = true

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionHelpers.addTypeToCompanion(
      input,
      generatedType(input),
      CompanionTypeConflictPolicy.PreserveExisting
    )

  private[contractprobetype] def generatedType(input: ExpansionInput)(using Context): untpd.TypeDef =
    given dotty.tools.dotc.util.SourceFile = input.annotatedClass.source

    def upperBounded(name: String): untpd.TypeDef =
      untpd.TypeDef(
        typeName(name),
        untpd.TypeBoundsTree(EmptyTree, untpd.Ident(typeName("Nat")))
      ).withMods(untpd.Modifiers(Param)).asInstanceOf[untpd.TypeDef]

    val appliedTarget =
      untpd.AppliedTypeTree(
        untpd.Ident(input.annotatedClass.name),
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

final class IndependentTypePlacementRejectHandler extends ParadiseAnnotationExpander:
  val annotationName: String = "IndependentTypePlacementRejectMarker"
  override val targetProfile: ExpansionTargetProfile =
    ExpansionTargetProfile.TwoUpperBoundedGenericTrait
  override val consumesExistingCompanion: Boolean = true

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionHelpers.addTypeToCompanion(
      input,
      new IndependentTypePlacementHandler().generatedType(input),
      CompanionTypeConflictPolicy.Reject
    )
