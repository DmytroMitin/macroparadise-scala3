import macroparadise.sbt.{
  MacroParadiseEmbeddedProducerPlugin,
  MacroParadiseIntegration,
  MacroParadisePrecompiledPlugin
}
import MacroParadiseEmbeddedProducerPlugin.autoImport._
import MacroParadisePrecompiledPlugin.autoImport._

ThisBuild / scalaVersion :=
  sys.props.getOrElse("macroparadise.example.scalaVersion", "3.8.4")
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / organization := "example.embedded"
ThisBuild / publish / skip := true

val mpVersion = "0.2.0-SNAPSHOT"
lazy val verifyEmbeddedStarter =
  taskKey[Unit]("Compile and run the public embedded producer starter")

lazy val embeddedProducer = project
  .in(file("embedded-producer"))
  .enablePlugins(MacroParadiseEmbeddedProducerPlugin)
  .settings(
    moduleName := "embedded-producer-starter",
    macroParadiseEmbeddedCompilerProductVersion := mpVersion
  )

lazy val core = project
  .in(file("core"))
  .dependsOn(
    embeddedProducer % "provided->macroParadiseEmbeddedMarker"
  )
  .enablePlugins(MacroParadisePrecompiledPlugin)
  .settings(
    MacroParadiseIntegration.precompiledEmbeddedProject(embeddedProducer),
    macroParadiseCompilerProductVersion := mpVersion,
    verifyEmbeddedStarter := {
      (embeddedProducer / macroParadiseEmbeddedValidate).value
      (Compile / compile).value
      val marker =
        (embeddedProducer / macroParadiseEmbeddedMarkerArtifact).value.getCanonicalFile
      val handler =
        (embeddedProducer / macroParadiseEmbeddedHandlerArtifact).value.getCanonicalFile
      val closure =
        (embeddedProducer / macroParadiseEmbeddedHandlerClasspath).value
          .map(_.getCanonicalFile)
      val compileClasspath =
        (Compile / fullClasspath).value.files.map(_.getCanonicalFile)
      val runtimeClasspath =
        (Runtime / fullClasspath).value.files.map(_.getCanonicalFile)

      require(compileClasspath.contains(marker), "derived marker is absent from compile classpath")
      require(closure.headOption.contains(handler), "derived handler is not first in its closure")
      require(!runtimeClasspath.contains(handler), "handler implementation leaked onto runtime")
      require(
        !runtimeClasspath.exists(_.getName.contains("scala3-compiler")),
        "Scala compiler leaked onto runtime"
      )
      require(
        !runtimeClasspath.exists(_.getName.contains("embedded-producer-plugin")),
        "producer compiler plugin leaked onto runtime"
      )

      (Compile / runner).value
        .run(
          "starter.core.Main",
          (Runtime / fullClasspath).value.files,
          Seq.empty,
          streams.value.log
        )
        .get
    }
  )

lazy val root = project
  .in(file("."))
  .aggregate(embeddedProducer, core)
  .settings(publish / skip := true)
