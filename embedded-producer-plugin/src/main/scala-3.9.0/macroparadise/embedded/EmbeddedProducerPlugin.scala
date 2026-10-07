package macroparadise.embedded

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.plugins.{PluginPhase, StandardPlugin}

final class EmbeddedProducerPlugin extends StandardPlugin:
  val name = "macroparadise-embedded-producer"
  val description =
    "Macro Paradise producer-only embedded declaration generator"
  override def init(options: List[String]): List[PluginPhase] =
    List(new EmbeddedProducerPhase(options))
  override def initialize(options: List[String])(using Context): List[PluginPhase] =
    List(new EmbeddedProducerPhase(options))
