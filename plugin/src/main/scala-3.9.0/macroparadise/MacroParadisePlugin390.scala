package macroparadise

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.plugins.{PluginPhase, StandardPlugin}

class MacroParadisePlugin extends StandardPlugin:
  val name: String = "macroparadise"
  override val description: String =
    "compiler plugin that expands narrow built-in annotations before typer"

  override def init(options: List[String]): List[PluginPhase] =
    ExactCompilerLine.pluginPhases("3.9.0", options)

  override def initialize(options: List[String])(using Context): List[PluginPhase] =
    ExactCompilerLine.pluginPhases("3.9.0", options)
