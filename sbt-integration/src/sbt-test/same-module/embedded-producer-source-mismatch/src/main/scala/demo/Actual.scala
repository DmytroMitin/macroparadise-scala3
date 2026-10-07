package demo

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.*
import paradise3.api.embeddedExpander
import scala.annotation.StaticAnnotation

@embeddedExpander
final class marker extends StaticAnnotation

object marker:
  def transform(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.Expanded(List(input.primary.tree))
