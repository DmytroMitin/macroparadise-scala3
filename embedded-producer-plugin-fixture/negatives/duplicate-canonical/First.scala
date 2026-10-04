package p218.negative.duplicate

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.{ExpansionChanges, ExpansionInput, ExpansionOutcome}
import scala.annotation.StaticAnnotation

@paradise3.api.embeddedExpander
final class duplicateCanonical extends StaticAnnotation

object duplicateCanonical:
  def transform(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.Structured(ExpansionChanges())
