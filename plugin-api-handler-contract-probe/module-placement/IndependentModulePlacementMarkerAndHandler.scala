package contractprobemodule

import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Names.{termName, typeName}
import paradise3.api.{DefinitionPlacement, ExpansionEdit, ExpansionHandler, ExpansionInput, ExpansionOutcome, ExpansionTargetKind, expander}
import paradise3.api.helpers.{ExpansionHelpers, MemberConflictPolicy, MissingCompanionPolicy}
import scala.annotation.StaticAnnotation

@expander("contractprobemodule.IndependentModulePlacementHandler")
final class IndependentModulePlacementMarker extends StaticAnnotation

@expander("contractprobemodule.IndependentModulePlacementRejectHandler")
final class IndependentModulePlacementRejectMarker extends StaticAnnotation

final class IndependentModulePlacementHandler extends ExpansionHandler:
  val annotationName: String = "IndependentModulePlacementMarker"

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionEdit.finish(
      ExpansionEdit.start(input).flatMap(edit => ExpansionHelpers.placeMemberInCompanion(
        edit,
        generatedModule(input),
        MissingCompanionPolicy.Create(ExpansionTargetKind.Object, DefinitionPlacement.AfterPrimary),
        MemberConflictPolicy.PreserveExisting
      ))
    )

  private[contractprobemodule] def generatedModule(input: ExpansionInput)(using Context): untpd.ModuleDef =
    given dotty.tools.dotc.util.SourceFile = input.primary.tree.source

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

final class IndependentModulePlacementRejectHandler extends ExpansionHandler:
  val annotationName: String = "IndependentModulePlacementRejectMarker"

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionEdit.finish(
      ExpansionEdit.start(input).flatMap(edit => ExpansionHelpers.placeMemberInCompanion(
        edit,
        new IndependentModulePlacementHandler().generatedModule(input),
        MissingCompanionPolicy.Create(ExpansionTargetKind.Object, DefinitionPlacement.AfterPrimary),
        MemberConflictPolicy.Reject
      ))
    )
