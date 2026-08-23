package macroparadise

import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.core.Contexts.Context

private[macroparadise] object CompilationUnitSuspension:
  def suspend(unit: CompilationUnit, reason: => String)(using Context): Nothing =
    unit.suspend()
