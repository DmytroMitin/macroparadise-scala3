package contractprobeunion

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.{ExpansionInput, ExpansionOutcome, ExpansionTargetProfile, ParadiseAnnotationExpander, expander}
import paradise3.api.helpers.ExpansionHelpers
import scala.annotation.StaticAnnotation

@expander("contractprobeunion.IndependentClosedTargetUnionHandler")
final class IndependentClosedTargetUnionMarker extends StaticAnnotation

final class IndependentClosedTargetUnionHandler extends ParadiseAnnotationExpander:
  val annotationName: String = "IndependentClosedTargetUnionMarker"
  override val targetProfile: ExpansionTargetProfile =
    ExpansionTargetProfile.RestrictedOrTwoUpperBoundedGenericTrait

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionHelpers.addStringMethodToCompanion(
      input,
      methodName = "closedTargetUnionInvoked",
      value = input.className
    )
