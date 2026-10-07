enablePlugins(macroparadise.sbt.MacroParadiseSameModulePlugin)

scalaVersion := sys.props.getOrElse("test.scala.version", "3.8.4")

macroParadiseSameModuleEmbeddedBinding := Some(
  macroParadiseSameModuleEmbedded(
    annotationName = "demo.configuredMarker",
    producerSource = macroParadiseLabelledSource(
      "embedded-producer-source",
      "demo/EmbeddedAnnotations.scala"
    )
  )
)
