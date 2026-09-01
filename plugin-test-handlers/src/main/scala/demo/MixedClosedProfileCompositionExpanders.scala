package demo

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.{
  ExpansionCompositionPolicy,
  ExpansionInput,
  ExpansionOutcome,
  ExpansionTargetProfile,
  ParadiseAnnotationExpander
}
import paradise3.api.helpers.ExpansionHelpers

final class MixedUnionCompanionExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "mixedUnionCompanion"
  override val targetProfile: ExpansionTargetProfile =
    ExpansionTargetProfile.RestrictedOrTwoUpperBoundedGenericTrait
  override val compositionPolicy: ExpansionCompositionPolicy =
    ExpansionCompositionPolicy.SourceOrdered
  override val consumesExistingCompanion: Boolean = true

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionHelpers.withAnnotatedClassView(input): view =>
      ExpansionHelpers.addStringMethodToCompanion(
        input,
        "mixedUnionResult",
        view.className
      )

final class MixedRestrictedCompanionExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "mixedRestrictedCompanion"
  override val targetProfile: ExpansionTargetProfile =
    ExpansionTargetProfile.RestrictedGenericTraitApply
  override val compositionPolicy: ExpansionCompositionPolicy =
    ExpansionCompositionPolicy.SourceOrdered
  override val consumesExistingCompanion: Boolean = true

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    if input.className == "MixedProfileLateFailureUser" then
      throw new IllegalStateException("mixed-profile late-step fixture failure")
    else
      ExpansionHelpers.withAnnotatedClassView(input): view =>
        ExpansionHelpers.addStringMethodToCompanion(
          input,
          "mixedRestrictedResult",
          view.className
        )
