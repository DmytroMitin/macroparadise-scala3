package demo

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.{ExpansionCompositionPolicy, ExpansionInput, ExpansionOutcome, ParadiseAnnotationExpander}
import paradise3.api.helpers.ExpansionHelpers

final class ExternalDebugExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "externalDebug"
  override val compositionPolicy: ExpansionCompositionPolicy =
    ExpansionCompositionPolicy.SourceOrdered

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionHelpers.withAnnotatedClassView(input): view =>
      ExpansionHelpers.addStringMethodToClass(
        input,
        "externalDebugName",
        view.className
      )
