package demo

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.{ExpansionInput, ExpansionOutcome, ParadiseAnnotationExpander}

final class DuplicateExternalDebugExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "externalDebug"

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.NotApplicable
