import java.nio.charset.StandardCharsets
import macroparadise.sbt.MacroParadiseIntegration
import macroparadise.sbt.MacroParadisePrecompiledPlugin.autoImport._

ThisBuild / scalaVersion := "3.8.4"
ThisBuild / publish / skip := true

lazy val verifyMultiLocal = taskKey[Unit]("Verify multi-local direct-first ordering and canonical de-duplication")
lazy val verifyOldSinglePair = taskKey[Unit]("Verify the original helper labels and one-pair behavior")
lazy val verifyRepeatedOldHelperOverwrite = taskKey[Unit]("Verify repeated original helper calls overwrite rather than compose")

def writeSource(project: Project, relative: String, text: String): Project =
  project.settings(Compile / sourceGenerators += Def.task {
    val output = (Compile / sourceManaged).value / relative
    IO.write(output, text, StandardCharsets.UTF_8)
    Seq(output)
  }.taskValue)

lazy val sharedRuntime = writeSource(
  project.in(file("shared-runtime")),
  "fixture/SharedRuntime.scala",
  "package fixture\nobject SharedRuntime\n"
)

lazy val markerA = writeSource(
  project.in(file("marker-a")),
  "fixture/MarkerA.scala",
  "package fixture\nfinal class MarkerA\n"
)

lazy val markerB = writeSource(
  project.in(file("marker-b")),
  "fixture/MarkerB.scala",
  "package fixture\nfinal class MarkerB\n"
)

def handlerProject(id: String, className: String): Project =
  writeSource(
    Project(id.replace('-', '_'), file(id)).settings(
      Runtime / unmanagedJars += Attributed.blank((sharedRuntime / Compile / packageBin).value)
    ),
    s"fixture/$className.scala",
    s"package fixture\nfinal class $className\n"
  )

lazy val handlerA = handlerProject("handler-a", "HandlerA")
lazy val handlerB = handlerProject("handler-b", "HandlerB")

lazy val core = project.in(file("core"))
  .enablePlugins(macroparadise.sbt.MacroParadisePrecompiledPlugin)
  .settings(
    MacroParadiseIntegration.precompiledProjects(
      markers = Seq(markerA, markerB),
      handlers = Seq(handlerA, handlerB)
    )
  )

lazy val oldSingle = project.in(file("old-single"))
  .enablePlugins(macroparadise.sbt.MacroParadisePrecompiledPlugin)
  .settings(MacroParadiseIntegration.precompiledProjects(markerA, handlerA))

lazy val oldRepeated = project.in(file("old-repeated"))
  .enablePlugins(macroparadise.sbt.MacroParadisePrecompiledPlugin)
  .settings(MacroParadiseIntegration.precompiledProjects(markerA, handlerA, "marker-a", "handler-a"))
  .settings(MacroParadiseIntegration.precompiledProjects(markerB, handlerB, "marker-b", "handler-b"))

lazy val root = project.in(file("."))
  .aggregate(sharedRuntime, markerA, markerB, handlerA, handlerB, core, oldSingle, oldRepeated)
  .settings(
    verifyMultiLocal := {
      val markers = (core / macroParadiseMarkerArtifacts).value
      val handlers = (core / macroParadiseHandlerClasspath).value
      val markerLabels = markers.map(_.label)
      val handlerLabels = handlers.map(_.label)
      val handlerFiles = handlers.map(_.file.getCanonicalFile)
      val shared = (sharedRuntime / Compile / packageBin).value.getCanonicalFile
      assert(markerLabels == Seq("local-marker-0000", "local-marker-0001"), markerLabels)
      assert(handlerLabels.take(2) == Seq("local-handler-0000", "local-handler-0001"), handlerLabels)
      assert(handlerLabels.drop(2).forall { label =>
        label.startsWith("local-handler-0000-runtime-") || label.startsWith("local-handler-0001-runtime-")
      }, handlerLabels)
      assert(handlerFiles.count(_ == shared) == 1, handlerFiles)
      assert(handlerFiles.distinct == handlerFiles, handlerFiles)
      assert(handlers.nonEmpty && markers.size == 2)
      (core / macroParadiseValidate).value
    },
    verifyOldSinglePair := {
      val markers = (oldSingle / macroParadiseMarkerArtifacts).value
      val handlers = (oldSingle / macroParadiseHandlerClasspath).value
      assert(markers.map(_.label) == Seq("local-marker"), markers)
      assert(handlers.headOption.map(_.label).contains("local-handler"), handlers)
      (oldSingle / macroParadiseValidate).value
    },
    verifyRepeatedOldHelperOverwrite := {
      val markers = (oldRepeated / macroParadiseMarkerArtifacts).value
      val handlers = (oldRepeated / macroParadiseHandlerClasspath).value
      assert(markers.map(_.label) == Seq("marker-b"), markers)
      assert(handlers.headOption.map(_.label).contains("handler-b"), handlers)
    }
  )
