package demo

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.*
import paradise3.api.embeddedExpander
import scala.annotation.StaticAnnotation

@embeddedExpander
final class cycleMarker extends StaticAnnotation

object cycleMarker:
  def transform(input: ExpansionInput)(using Context): ExpansionOutcome =
    val unavailable = consumer.Helper.value
    assert(unavailable.nonEmpty)
    ExpansionOutcome.Expanded(List(input.primary.tree))
