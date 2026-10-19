package demo

import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Names.{termName, typeName}
import paradise3.api.*
import paradise3.api.embeddedExpander
import paradise3.api.helpers.ExpansionTransforms
import scala.annotation.StaticAnnotation

@embeddedExpander
final class markerB extends StaticAnnotation

object markerB:
  def transform(input: ExpansionInput)(using Context): ExpansionOutcome =
    val value = untpd.DefDef(termName("generatedB"), Nil, untpd.Ident(typeName("String")), untpd.Literal(Constant("B")))
    ExpansionEdit.finish(ExpansionEdit.start(input).flatMap(ExpansionTransforms.placeMemberInPrimary(value)))
