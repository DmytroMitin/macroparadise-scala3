package com.example.macros.handlers

import dotty.tools.dotc.core.Contexts
import paradise3.api.{ExpansionInput, ExpansionOutcome, ParadiseAnnotationExpander}

final class IdentityHandler extends ParadiseAnnotationExpander:
  override def annotationName: String = "com.example.macros.annotations.identity"

  override def expand(input: ExpansionInput)(using Contexts.Context): ExpansionOutcome =
    ExpansionOutcome.Expanded(List(input.annotatedClass))
