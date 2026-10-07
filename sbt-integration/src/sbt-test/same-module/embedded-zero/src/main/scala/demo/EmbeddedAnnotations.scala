package demo

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.*
import paradise3.api.embeddedExpander
import scala.annotation.StaticAnnotation

@embeddedExpander
final class sameModuleIdentity extends StaticAnnotation

object sameModuleIdentity:
  def transform(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.Expanded(List(input.primary.tree))
