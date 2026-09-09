package demo

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.{ExpansionChanges, ExpansionHandler, ExpansionInput, ExpansionOutcome}

final class DuplicateExternalDebugExpander extends ExpansionHandler:
  val annotationName: String = "externalDebug"

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.Structured(ExpansionChanges())
