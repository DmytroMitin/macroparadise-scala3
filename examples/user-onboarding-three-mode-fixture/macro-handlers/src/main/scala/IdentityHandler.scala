package com.example.macros.handlers

import dotty.tools.dotc.core.Contexts
import paradise3.api.*

final class IdentityHandler extends ExpansionHandler:
  override def annotationName: String = "com.example.macros.annotations.identity"
  override val admissions = List(ExpansionAdmission(ExpansionTargetKind.Class, ExpansionShapeProfile.OrdinaryTemplate))

  override def expand(input: ExpansionInput)(using Contexts.Context): ExpansionOutcome =
    ExpansionOutcome.Structured(ExpansionChanges())
