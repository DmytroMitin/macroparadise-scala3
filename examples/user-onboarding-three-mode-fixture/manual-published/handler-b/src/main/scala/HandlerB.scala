package fixture.handler

import dotty.tools.dotc.core.Contexts.Context
import fixture.runtime.SharedRuntime
import paradise3.api.{ExpansionInput, ExpansionOutcome, ParadiseAnnotationExpander}
import paradise3.api.helpers.ExpansionHelpers

final class HandlerB extends ParadiseAnnotationExpander:
  override def annotationName: String = "fixture.marker.markerB"

  override def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionHelpers.addStringMethodToClass(input, "generatedB", "B:" + SharedRuntime.current)
