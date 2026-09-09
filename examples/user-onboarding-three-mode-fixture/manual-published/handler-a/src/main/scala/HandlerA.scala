package fixture.handler

import dotty.tools.dotc.core.Contexts.Context
import fixture.runtime.SharedRuntime
import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Names.{termName, typeName}
import paradise3.api.*
import paradise3.api.helpers.ExpansionHelpers

final class HandlerA extends ExpansionHandler:
  override def annotationName: String = "fixture.marker.markerA"

  override def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionEdit.finish:
      ExpansionEdit.start(input).flatMap: edit =>
        ExpansionHelpers.placeMemberInPrimary(
          edit,
          untpd.DefDef(termName("generatedA"), Nil, untpd.Ident(typeName("String")), untpd.Literal(Constant("A:" + SharedRuntime.current)))
        )
