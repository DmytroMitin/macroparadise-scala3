package demo

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.*
import paradise3.api.embeddedExpander
import scala.annotation.StaticAnnotation

@embeddedExpander
final class actualMarker extends StaticAnnotation

object actualMarker:
  def transform(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionEdit.finish(ExpansionEdit.start(input))
