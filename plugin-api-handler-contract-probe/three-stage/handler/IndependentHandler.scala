package contractprobe

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Names.*
import paradise3.api.{ExpansionAdmission, ExpansionEdit, ExpansionHandler, ExpansionInput, ExpansionOutcome, ExpansionShapeProfile, ExpansionTargetKind}
import paradise3.api.helpers.ExpansionHelpers

final class IndependentHandler extends ExpansionHandler:
  val annotationName: String = "IndependentMarker"
  val admissions = List(ExpansionAdmission(ExpansionTargetKind.Class, ExpansionShapeProfile.OrdinaryTemplate))

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    val method = DefDef(termName("independentHandlerName"), Nil, Ident(typeName("String")), Literal(Constant(input.primary.name)))
    ExpansionEdit.finish(ExpansionEdit.start(input).flatMap(edit => ExpansionHelpers.placeMemberInPrimary(edit, method)))
