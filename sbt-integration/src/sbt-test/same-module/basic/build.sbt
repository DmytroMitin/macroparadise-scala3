import java.nio.charset.StandardCharsets

enablePlugins(macroparadise.sbt.MacroParadiseSameModulePlugin)

scalaVersion := "3.8.4"

macroParadiseSameModuleBinding := Some(
  macroParadiseSameModuleHandler(
    annotationName = "demo.marker",
    handlerClassName = "demo.Handler",
    markerSource = macroParadiseLabelledSource("marker-source", "demo/Marker.scala"),
    handlerSource = macroParadiseLabelledSource("handler-source", "demo/Handler.scala")
  )
)

lazy val verifyInitial = taskKey[Unit]("Verify the explicit same-module configuration")
lazy val editHandler = taskKey[Unit]("Edit only the explicitly configured handler source")
lazy val verifyChanged = taskKey[Unit]("Verify source identity changes after the handler edit")

verifyInitial := {
  val derived = macroParadiseSameModuleConfiguration.value
  assert(derived.sourceIdentity.sources.map(_.label) == Vector("handler-source", "marker-source"))
  assert(derived.compilerOptions.count(_.startsWith("-P:macroparadise:sameModuleSourceIdentity=")) == 1)
  assert(derived.compilerOptions.count(_.startsWith("-P:macroparadise:sameModuleHandler=")) == 1)
  IO.write(target.value / "initial-identity.txt", derived.sourceIdentity.identity, StandardCharsets.UTF_8)
}

editHandler := {
  val handler = (Compile / scalaSource).value / "demo" / "Handler.scala"
  IO.write(
    handler,
    "package demo\n\nfinal class Handler:\n  def value: String = \"v2\"\n",
    StandardCharsets.UTF_8
  )
}

verifyChanged := {
  val before = IO.read(target.value / "initial-identity.txt", StandardCharsets.UTF_8)
  val after = macroParadiseSameModuleConfiguration.value.sourceIdentity.identity
  assert(after != before)
}
