enablePlugins(macroparadise.sbt.MacroParadiseSameModulePlugin)

scalaVersion := sys.props.getOrElse("test.scala.version", "3.8.4")
offline := true

macroParadiseSameModuleEmbeddedBinding := Some(
  macroParadiseSameModuleEmbedded(
    annotationName = "demo.marker",
    producerSource = macroParadiseLabelledSource(
      "embedded-producer-source",
      "demo/EmbeddedAnnotations.scala"
    )
  )
)

macroParadiseSameModuleEmbeddedProducerCompilerPluginModule :=
  ("invalid.organization" % "invalid-embedded-producer" % "0.0.0-invalid")
    .cross(CrossVersion.full)
