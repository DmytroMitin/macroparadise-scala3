package contractprobeunion

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Names.*
import paradise3.api.{DefinitionPlacement, ExpansionAdmission, ExpansionEdit, ExpansionHandler, ExpansionInput, ExpansionOutcome, ExpansionShapeProfile, ExpansionTargetKind, expander}
import paradise3.api.helpers.{ExpansionHelpers, MissingCompanionPolicy}
import scala.annotation.StaticAnnotation

@expander("contractprobeunion.IndependentClosedTargetUnionHandler")
final class IndependentClosedTargetUnionMarker extends StaticAnnotation

final class IndependentClosedTargetUnionHandler extends ExpansionHandler:
  val annotationName: String = "IndependentClosedTargetUnionMarker"
  val admissions = List(
    ExpansionAdmission(ExpansionTargetKind.Trait, ExpansionShapeProfile.OneInvariantUnboundedTypeParameter),
    ExpansionAdmission(ExpansionTargetKind.Trait, ExpansionShapeProfile.TwoInvariantUpperBoundedTypeParameters)
  )

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    val method = DefDef(termName("closedTargetUnionInvoked"), Nil, Ident(typeName("String")), Literal(Constant(input.primary.name)))
    ExpansionEdit.finish(
      ExpansionEdit.start(input).flatMap(edit => ExpansionHelpers.placeMemberInCompanion(
        edit,
        method,
        MissingCompanionPolicy.Create(ExpansionTargetKind.Object, DefinitionPlacement.AfterPrimary)
      ))
    )
