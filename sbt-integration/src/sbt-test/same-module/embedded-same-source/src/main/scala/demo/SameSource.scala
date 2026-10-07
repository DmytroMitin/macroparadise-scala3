package demo

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.*
import paradise3.api.embeddedExpander
import scala.annotation.StaticAnnotation

@embeddedExpander
final class sameSourceMarker extends StaticAnnotation

object sameSourceMarker:
  def transform(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.Expanded(List(input.primary.tree))

@demo.sameSourceMarker
final class SameSourceConsumer
