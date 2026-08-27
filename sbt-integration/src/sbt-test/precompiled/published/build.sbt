import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.jar.JarOutputStream

enablePlugins(macroparadise.sbt.MacroParadisePrecompiledPlugin)

scalaVersion := "3.8.4"

lazy val publishFixture = taskKey[Unit]("Create the task-local Maven fixture")
lazy val verifyPublished = taskKey[Unit]("Verify hidden published handler resolution")

val fixtureRepository = file("repository")
resolvers := Seq("scripted-fixture" at fixtureRepository.toURI.toString, Resolver.mavenCentral)
credentials := Nil

macroParadiseMarkerModules := Seq(
  ("fixture" % "published-marker" % "1.0").cross(CrossVersion.full)
)
macroParadiseHandlerModules := Seq(
  ("fixture" % "published-handler" % "1.0").cross(CrossVersion.full)
)

def moduleDirectory(organization: String, module: String, version: String): File =
  fixtureRepository / organization.replace('.', '/') / module / version

def writeModule(
    organization: String,
    module: String,
    version: String,
    dependencies: Seq[(String, String, String)] = Seq.empty
): Unit = {
  val directory = moduleDirectory(organization, module, version)
  IO.createDirectory(directory)
  val jar = directory / s"$module-$version.jar"
  val output = new JarOutputStream(new FileOutputStream(jar))
  output.close()
  val dependencyXml = dependencies.map { case (org, name, revision) =>
    s"<dependency><groupId>$org</groupId><artifactId>$name</artifactId><version>$revision</version></dependency>"
  }.mkString
  IO.write(
    directory / s"$module-$version.pom",
    s"""<project xmlns="http://maven.apache.org/POM/4.0.0">
       |<modelVersion>4.0.0</modelVersion><groupId>$organization</groupId>
       |<artifactId>$module</artifactId><version>$version</version>
       |<dependencies>$dependencyXml</dependencies></project>
       |""".stripMargin,
    StandardCharsets.UTF_8
  )
}

publishFixture := {
  IO.delete(fixtureRepository)
  writeModule("com.github.dmytromitin", "macroparadise-scala3-plugin_3.8.4", "0.1.1-SNAPSHOT")
  writeModule("fixture", "published-marker_3.8.4", "1.0")
  writeModule("fixture", "published-runtime_3.8.4", "1.0")
  writeModule(
    "fixture",
    "published-handler_3.8.4",
    "1.0",
    Seq(("fixture", "published-runtime_3.8.4", "1.0"))
  )
}

verifyPublished := {
  val markers = macroParadiseMarkerArtifacts.value
  val handlers = macroParadiseHandlerClasspath.value
  val handlerNames = handlers.map(_.file.getName)
  val ordinaryRuntime = (Runtime / fullClasspath).value.files.map(_.getCanonicalFile)
  assert(markers.size == 1 && markers.head.file.getName.contains("published-marker"))
  assert(handlers.head.file.getName == "published-handler_3.8.4-1.0.jar", handlerNames)
  val runtimeDependency = handlers.find(_.file.getName == "published-runtime_3.8.4-1.0.jar").getOrElse {
    sys.error("published handler runtime dependency is missing: " + handlerNames.mkString(","))
  }
  assert(!ordinaryRuntime.contains(handlers.head.file.getCanonicalFile))
  assert(!ordinaryRuntime.contains(runtimeDependency.file.getCanonicalFile))
  assert(macroParadiseExternalArtifactIdentity.value.matches("[0-9a-f]{64}"))
}
