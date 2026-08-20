package macroparadise

import scala.io.Source
import scala.util.Using

class MacroParadisePluginSpec extends munit.FunSuite:
  test("plugin metadata is stable") {
    val plugin = new MacroParadisePlugin
    assertEquals(plugin.name, "macroparadise")
    assertEquals(
      plugin.description,
      "research plugin that expands narrow built-in annotations before typer"
    )
  }

  test("plugin properties points to the plugin class") {
    val content =
      Using.resource(Source.fromResource("plugin.properties"))(_.mkString.trim)

    assertEquals(content, "pluginClass=macroparadise.MacroParadisePlugin")
  }
