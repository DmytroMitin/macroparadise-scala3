package demo

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.*
import paradise3.api.helpers.ExpansionHelpers

final class SameModuleDebugExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "sameModuleDebug"

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    dotty.tools.dotc.report.echo(
      s"[same-module-handler] expanding class=${input.className} handler=${getClass.getName}"
    )
    ExpansionHelpers.addStringMethodToClass(
      input,
      methodName = "sameModuleDebugName",
      value = input.className
    )
