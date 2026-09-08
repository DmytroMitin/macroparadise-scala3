package com.example.`macro`.handlers

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.{ExpansionAdmission, ExpansionHandler, ExpansionInput, ExpansionOutcome, ExpansionShapeProfile, ExpansionTargetKind}

final class IdentityHandler extends ExpansionHandler:
  override def annotationName: String =
    "com.example.macro.annotations.identity"

  override val admissions = List(ExpansionAdmission(ExpansionTargetKind.Class, ExpansionShapeProfile.OrdinaryTemplate))

  override def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.Expanded(List(input.primary.tree))
