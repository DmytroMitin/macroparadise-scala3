package demo

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.{
  ExpansionCompositionPolicy,
  ExpansionInput,
  ExpansionOutcome,
  ParadiseAnnotationExpander,
  StructuredExpansionOutput
}
import paradise3.api.helpers.ExpansionHelpers

final class ExternalCompanionDebugExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "externalCompanionDebug"
  override val compositionPolicy: ExpansionCompositionPolicy =
    ExpansionCompositionPolicy.SourceOrdered
  override val consumesExistingCompanion: Boolean = true

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    if input.className == "InvocationCompositionFailureUser" then
      throw new IllegalStateException("later admitted composition fixture failure")
    else if input.className == "StructuredCompositionFailureUser" then
      ExpansionOutcome.Structured(
        StructuredExpansionOutput(
          input.annotatedClass,
          input.existingCompanion,
          null
        )
      )
    else
      ExpansionHelpers.withAnnotatedClassView(input): view =>
        ExpansionHelpers.addStringMethodToCompanion(
          input,
          "externalCompanionDebugName",
          view.className
        )
