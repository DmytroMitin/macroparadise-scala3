package demo

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.{ExpansionInput, ExpansionOutcome, ParadiseAnnotationExpander}

final class ThrowingExpander extends ParadiseAnnotationExpander:
  throw IllegalStateException("boom during handler construction")

  val annotationName: String = "throwing"

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.NotApplicable
