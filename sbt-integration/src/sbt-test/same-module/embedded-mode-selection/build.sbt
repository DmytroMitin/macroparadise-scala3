enablePlugins(macroparadise.sbt.MacroParadiseSameModulePlugin)

scalaVersion := sys.props.getOrElse("test.scala.version", "3.8.4")
