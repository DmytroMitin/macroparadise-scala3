package demo

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.{ExpansionCompositionPolicy, ExpansionInput, ExpansionOutcome, ParadiseAnnotationExpander}
import paradise3.api.helpers.ExpansionHelpers

final class ExternalSiblingDebugExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "externalSiblingDebug"
  override val compositionPolicy: ExpansionCompositionPolicy =
    ExpansionCompositionPolicy.SourceOrdered

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionHelpers.withAnnotatedClassView(input): view =>
      ExpansionHelpers.addStringMethodSiblingClass(
        input,
        siblingClassName = s"${view.className}ExternalMeta",
        methodName = "externalSiblingDebugName",
        value = view.className
      )
