package starter.negative

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.{ExpansionInput, ExpansionOutcome, ParadiseAnnotationExpander}

final class BindingMismatchHandler extends ParadiseAnnotationExpander:
  val annotationName: String = "starter.negative.otherIdentity"

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.NotApplicable
