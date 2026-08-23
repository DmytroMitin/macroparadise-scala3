package macroparadise

import dotty.tools.dotc.config.Properties
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.plugins.PluginPhase
import dotty.tools.dotc.report

private[macroparadise] object ExactCompilerLine:
  def pluginPhases(expected: String, options: List[String]): List[PluginPhase] =
    val actual = Properties.versionNumberString
    if actual == expected then List(ParadiseGenPhase(options))
    else List(ExactCompilerMismatchPhase(expected, actual))

private final case class ExactCompilerMismatchPhase(
    pluginCompiler: String,
    activeCompiler: String
) extends PluginPhase:
  override val phaseName = "macroparadiseExactCompilerMismatch"
  override val description = "rejects a macroparadise artifact from another exact compiler line"
  override def runsAfter = Set("parser")
  override def runsBefore = Set("typer")

  override def run(using Context): Unit =
    report.error(
      s"macroparadise exact compiler mismatch: plugin=$pluginCompiler compiler=$activeCompiler"
    )
