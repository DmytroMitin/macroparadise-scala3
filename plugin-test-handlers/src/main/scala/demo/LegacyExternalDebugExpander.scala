package demo

import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.{ExpansionInput, ExpansionOutcome, ParadiseAnnotationExpander}
import paradise3.api.helpers.ExpansionHelpers

final class LegacyExternalDebugExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "legacyExternalDebug"

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    input.annotatedClass.rhs match
      case _: Template =>
        ExpansionHelpers.addStringMethodToClass(
          input,
          "legacyExternalDebugName",
          input.className
        )
      case _ =>
        ExpansionOutcome.NotApplicable
