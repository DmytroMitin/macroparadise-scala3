package com.example.macros.handlers

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.{ExpansionInput, ExpansionOutcome, ParadiseAnnotationExpander}
import paradise3.api.helpers.ExpansionHelpers

final class GenHandler extends ParadiseAnnotationExpander:
  override def annotationName: String = "com.example.macros.annotations.gen"

  override def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionHelpers.addStringMethodToClass(
      input,
      methodName = "generatedHello",
      value = s"hello ${input.className}"
    )
