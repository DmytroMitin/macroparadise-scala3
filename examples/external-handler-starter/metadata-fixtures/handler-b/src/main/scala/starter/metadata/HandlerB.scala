package starter.metadata

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.{ExpansionInput, ExpansionOutcome, ParadiseAnnotationExpander}

final class HandlerB extends ParadiseAnnotationExpander:
  val annotationName: String = "starter.metadata.handlerB"

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.NotApplicable
