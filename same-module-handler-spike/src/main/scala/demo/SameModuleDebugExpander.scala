package demo

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.*
import paradise3.api.helpers.ExpansionHelpers

final class SameModuleDebugExpander extends ExpansionHandler:
  val annotationName: String = "sameModuleDebug"

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    dotty.tools.dotc.report.echo(
      s"[same-module-handler] expanding target=${input.primary.name} handler=${getClass.getName}"
    )
    ExpansionEdit.finish:
      ExpansionEdit.start(input).flatMap: edit =>
        ExpansionHelpers.placeMemberInPrimary(
          edit,
          dotty.tools.dotc.ast.untpd.DefDef(
            dotty.tools.dotc.core.Names.termName("sameModuleDebugName"),
            Nil,
            dotty.tools.dotc.ast.untpd.Ident(dotty.tools.dotc.core.Names.typeName("String")),
            dotty.tools.dotc.ast.untpd.Literal(dotty.tools.dotc.core.Constants.Constant(input.primary.name))
          )
        )
