package demo

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.{ExpansionCompositionPolicy, ExpansionInput, ExpansionOutcome, ParadiseAnnotationExpander}
import paradise3.api.helpers.ExpansionHelpers

final class ExternalLabelExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "externalLabel"
  override val compositionPolicy: ExpansionCompositionPolicy =
    ExpansionCompositionPolicy.SourceOrdered

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionHelpers.withAnnotatedClassView(input): view =>
      ExpansionHelpers.addStringMethodToClass(
        input,
        "externalLabel",
        view.className
      )
