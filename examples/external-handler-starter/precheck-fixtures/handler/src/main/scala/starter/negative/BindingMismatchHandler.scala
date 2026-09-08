package starter.negative

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.*

final class BindingMismatchHandler extends ExpansionHandler:
  val annotationName: String = "starter.negative.otherIdentity"
  val admissions = List(ExpansionAdmission(ExpansionTargetKind.Class, ExpansionShapeProfile.OrdinaryTemplate))

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.Structured(ExpansionChanges())
