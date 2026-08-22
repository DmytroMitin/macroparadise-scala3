package com.example.`macro`.handlers

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.{ExpansionInput, ExpansionOutcome, ParadiseAnnotationExpander}

final class IdentityHandler extends ParadiseAnnotationExpander:
  override def annotationName: String =
    "com.example.macro.annotations.identity"

  override def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.Expanded(List(input.annotatedClass))
