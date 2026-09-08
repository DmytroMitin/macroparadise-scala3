package demo

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.{ExpansionAdmission, ExpansionChanges, ExpansionHandler, ExpansionInput, ExpansionOutcome, ExpansionShapeProfile, ExpansionTargetKind}

final class GenNameExpander extends ExpansionHandler:
  val annotationName: String = "gen"
  val admissions = List(ExpansionAdmission(ExpansionTargetKind.Class, ExpansionShapeProfile.OrdinaryTemplate))

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.Structured(ExpansionChanges())
