package fixture.handler

import dotty.tools.dotc.core.Contexts.Context
import fixture.runtime.SharedRuntime
import paradise3.api.{ExpansionInput, ExpansionOutcome, ParadiseAnnotationExpander}
import paradise3.api.helpers.ExpansionHelpers

final class HandlerA extends ParadiseAnnotationExpander:
  override def annotationName: String = "fixture.marker.markerA"

  override def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionHelpers.addStringMethodToClass(input, "generatedA", "A:" + SharedRuntime.current)
