package macroparadise.embedded

import dotty.tools.dotc.plugins.{PluginPhase, StandardPlugin}

final class EmbeddedProducerPlugin extends StandardPlugin:
  val name = "macroparadise-embedded-producer"
  val description =
    "Macro Paradise producer-only embedded declaration generator"
  def init(options: List[String]): List[PluginPhase] =
    List(new EmbeddedProducerPhase)
