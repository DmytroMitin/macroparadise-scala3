package starter.metadata

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.*

final class HandlerB extends ExpansionHandler:
  val annotationName: String = "starter.metadata.handlerB"

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.Structured(ExpansionChanges())
