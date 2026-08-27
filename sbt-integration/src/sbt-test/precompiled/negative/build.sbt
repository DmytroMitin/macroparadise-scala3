import java.nio.charset.StandardCharsets
import java.io.FileOutputStream
import java.util.jar.{JarEntry, JarOutputStream}

enablePlugins(macroparadise.sbt.MacroParadisePrecompiledPlugin)

scalaVersion := "3.8.4"

def writeArtifact(root: File, name: String, value: String): File = {
  val output = root / name
  IO.write(output, value, StandardCharsets.UTF_8)
  output
}

def writeRenamedCompilerPlugin(root: File): File = {
  val output = root / "renamed-handler-tool.jar"
  val jar = new JarOutputStream(new FileOutputStream(output))
  try {
    jar.putNextEntry(new JarEntry("plugin.properties"))
    jar.write("pluginClass=macroparadise.MacroParadisePlugin\n".getBytes(StandardCharsets.UTF_8))
    jar.closeEntry()
  } finally jar.close()
  output
}

lazy val renamedCompilerPlugin = taskKey[File]("Create a renamed compiler-plugin fixture")
renamedCompilerPlugin := writeRenamedCompilerPlugin(target.value)

macroParadiseMarkerArtifacts :=
  Seq(macroParadiseLabelled("marker", writeArtifact(target.value, "marker.jar", "marker")))

macroParadiseHandlerClasspath :=
  Seq(macroParadiseLabelled("handler", writeArtifact(target.value, "handler.jar", "handler")))
