import java.nio.charset.StandardCharsets

enablePlugins(macroparadise.sbt.MacroParadisePrecompiledPlugin)

scalaVersion := "3.8.4"

lazy val verify = taskKey[Unit]("Verify the opt-in precompiled integration surface")

def writeArtifact(root: File, name: String, value: String): File = {
  val output = root / name
  IO.write(output, value, StandardCharsets.UTF_8)
  output
}

macroParadiseMarkerArtifacts :=
  Seq(macroParadiseLabelled("marker", writeArtifact(target.value, "marker.jar", "marker")))

macroParadiseHandlerClasspath := Seq(
  macroParadiseLabelled("handler", writeArtifact(target.value, "handler.jar", "handler")),
  macroParadiseLabelled("runtime", writeArtifact(target.value, "runtime.jar", "runtime"))
)

verify := {
  val pluginModule = macroParadiseCompilerPluginModule.value
  val apiModule = macroParadisePluginApiModule.value
  assert(pluginModule.name == "macroparadise-scala3-plugin")
  assert(apiModule.name == "macroparadise-scala3-plugin-api")
  assert(pluginModule.crossVersion == CrossVersion.full)
  assert(apiModule.crossVersion == CrossVersion.full)
  assert(macroParadiseExternalArtifactIdentity.value.matches("[0-9a-f]{64}"))
  val options = macroParadiseCompilerOptions.value
  assert(options.contains("-Xplugin-require:macroparadise"))
  assert(options.exists(_.startsWith("-P:macroparadise:externalArtifactIdentity=sha256:")))
  val handler = options.find(_.startsWith("-P:macroparadise:handlerClasspath=")).get
  assert(handler.indexOf("handler.jar") < handler.indexOf("runtime.jar"))
  macroParadiseValidate.value
}
