package legacybinaryprobe

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.*
import paradise3.api.helpers.ExpansionHelpers

final class LegacyBinaryClassHandler extends ParadiseAnnotationExpander:
  val annotationName = "legacybinaryprobe.LegacyBinaryClassMarker"
  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionHelpers.addStringMethodToClass(input, "legacyClass", "class-0.1.1")

final class LegacyBinaryTraitHandler extends ParadiseAnnotationExpander:
  val annotationName = "legacybinaryprobe.LegacyBinaryTraitMarker"
  override val targetProfile = ExpansionTargetProfile.PlainZeroParameterTrait
  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionHelpers.addStringMethodToClass(input, "legacyTrait", "trait-0.1.1")
