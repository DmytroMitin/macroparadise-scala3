package demo

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.{ExpansionChanges, ExpansionHandler, ExpansionInput, ExpansionOutcome}

final class ThrowingExpander extends ExpansionHandler:
  throw IllegalStateException("boom during handler construction")

  val annotationName: String = "throwing"

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.Structured(ExpansionChanges())
