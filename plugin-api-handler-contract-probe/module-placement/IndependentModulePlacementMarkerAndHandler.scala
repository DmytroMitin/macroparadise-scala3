package contractprobemodule

import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Names.{termName, typeName}
import paradise3.api.{ExpansionInput, ExpansionOutcome, ExpansionTargetProfile, ParadiseAnnotationExpander, expander}
import paradise3.api.helpers.{CompanionModuleConflictPolicy, ExpansionHelpers}
import scala.annotation.StaticAnnotation

@expander("contractprobemodule.IndependentModulePlacementHandler")
final class IndependentModulePlacementMarker extends StaticAnnotation

@expander("contractprobemodule.IndependentModulePlacementRejectHandler")
final class IndependentModulePlacementRejectMarker extends StaticAnnotation

final class IndependentModulePlacementHandler extends ParadiseAnnotationExpander:
  val annotationName: String = "IndependentModulePlacementMarker"
  override val targetProfile: ExpansionTargetProfile =
    ExpansionTargetProfile.TwoUpperBoundedGenericTrait
  override val consumesExistingCompanion: Boolean = true

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionHelpers.addModuleToCompanion(
      input,
      generatedModule(input),
      CompanionModuleConflictPolicy.PreserveExisting
    )

  private[contractprobemodule] def generatedModule(input: ExpansionInput)(using Context): untpd.ModuleDef =
    given dotty.tools.dotc.util.SourceFile = input.annotatedClass.source

    val marker = untpd.ValDef(
      termName("marker"),
      untpd.Ident(typeName("String")),
      untpd.Literal(Constant("placed"))
    )
    val template = untpd.Template(
      emptyConstructor,
      Nil,
      Nil,
      EmptyValDef,
      List(marker)
    )
    untpd.ModuleDef(termName("syntax"), template)

final class IndependentModulePlacementRejectHandler extends ParadiseAnnotationExpander:
  val annotationName: String = "IndependentModulePlacementRejectMarker"
  override val targetProfile: ExpansionTargetProfile =
    ExpansionTargetProfile.TwoUpperBoundedGenericTrait
  override val consumesExistingCompanion: Boolean = true

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionHelpers.addModuleToCompanion(
      input,
      new IndependentModulePlacementHandler().generatedModule(input),
      CompanionModuleConflictPolicy.Reject
    )
