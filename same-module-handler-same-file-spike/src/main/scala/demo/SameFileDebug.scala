package demo

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.*
import paradise3.api.helpers.ExpansionHelpers

final class sameFileDebug extends scala.annotation.StaticAnnotation

final class SameFileDebugExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "sameFileDebug"

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionHelpers.addStringMethodToClass(
      input,
      methodName = "sameFileDebugName",
      value = input.className
    )

@sameFileDebug
class SameFileUser

val sameFileResult: String = new SameFileUser().sameFileDebugName
