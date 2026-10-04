package p218.negative

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.{ExpansionChanges, ExpansionInput, ExpansionOutcome}

@paradise3.api.embeddedExpander
final class notStatic

object notStatic:
  def transform(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.Structured(ExpansionChanges())
