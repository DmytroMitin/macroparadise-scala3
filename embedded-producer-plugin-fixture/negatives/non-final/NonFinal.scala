package p218.negative

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.{ExpansionChanges, ExpansionInput, ExpansionOutcome}
import scala.annotation.StaticAnnotation

@paradise3.api.embeddedExpander
class nonFinal extends StaticAnnotation

object nonFinal:
  def transform(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.Structured(ExpansionChanges())
