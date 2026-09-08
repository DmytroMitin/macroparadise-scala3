package fixture.handler

import dotty.tools.dotc.core.Contexts.Context
import fixture.runtime.SharedRuntime
import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Names.{termName, typeName}
import paradise3.api.*
import paradise3.api.helpers.ExpansionHelpers

final class HandlerB extends ExpansionHandler:
  override def annotationName: String = "fixture.marker.markerB"
  override val admissions = List(ExpansionAdmission(ExpansionTargetKind.Class, ExpansionShapeProfile.OrdinaryTemplate))

  override def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionEdit.finish:
      ExpansionEdit.start(input).flatMap: edit =>
        ExpansionHelpers.placeMemberInPrimary(
          edit,
          untpd.DefDef(termName("generatedB"), Nil, untpd.Ident(typeName("String")), untpd.Literal(Constant("B:" + SharedRuntime.current)))
        )
