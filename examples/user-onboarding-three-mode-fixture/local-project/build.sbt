import macroparadise.sbt.MacroParadiseIntegration
import macroparadise.sbt.MacroParadisePrecompiledPlugin.autoImport._

ThisBuild / scalaVersion := sys.props("macroparadise.exactScalaVersion")
ThisBuild / version := "0.0.1-onboarding-matrix"
ThisBuild / resolvers := Seq(
  "task-product-repository" at file(sys.props("macroparadise.productRepository")).toURI.toString,
  Resolver.mavenCentral
)
ThisBuild / credentials := Nil
ThisBuild / publish / skip := true

val mpVersion = "0.1.1-SNAPSHOT"
val mpApi =
  ("com.github.dmytromitin" % "macroparadise-scala3-plugin-api" % mpVersion)
    .cross(CrossVersion.full)

lazy val verifyFixture = taskKey[Unit]("Verify the exact local-project onboarding fixture")

lazy val macroAnnotations = (project in file("macro-annotations"))
  .settings(libraryDependencies += mpApi)

lazy val macroHandlers = (project in file("macro-handlers"))
  .settings(
    libraryDependencies ++= Seq(
      mpApi,
      "org.scala-lang" %% "scala3-compiler" % scalaVersion.value
    )
  )

lazy val core = (project in file("core"))
  .dependsOn(macroAnnotations)
  .settings(MacroParadiseIntegration.precompiledProjects(macroAnnotations, macroHandlers))
  .enablePlugins(macroparadise.sbt.MacroParadisePrecompiledPlugin)
  .settings(macroParadiseCompilerProductVersion := mpVersion)

lazy val root = (project in file("."))
  .aggregate(macroAnnotations, macroHandlers, core)
  .settings(
    verifyFixture := {
      (core / Compile / compile).value
      val compileClasspath = (core / Compile / fullClasspath).value.files.map(_.getCanonicalFile)
      val runtimeClasspath = (core / Runtime / fullClasspath).value.files.map(_.getCanonicalFile)
      val markerClasses = (macroAnnotations / Compile / classDirectory).value.getCanonicalFile
      val handlerClasses = (macroHandlers / Compile / classDirectory).value.getCanonicalFile
      val handlerJar = (macroHandlers / Compile / packageBin).value.getCanonicalFile
      val markerArtifacts = (core / macroParadiseMarkerArtifacts).value
      val handlerClasspath = (core / macroParadiseHandlerClasspath).value
      val identity = (core / macroParadiseExternalArtifactIdentity).value
      require(compileClasspath.contains(markerClasses), "marker classes missing from consumer compile classpath")
      require(markerArtifacts.exists(_.file.getCanonicalFile == (macroAnnotations / Compile / packageBin).value.getCanonicalFile), "marker package missing from explicit marker role")
      require(handlerClasspath.headOption.exists(_.file.getCanonicalFile == handlerJar), "primary handler is not first")
      require(!runtimeClasspath.contains(handlerClasses) && !runtimeClasspath.contains(handlerJar), "handler leaked onto consumer runtime classpath")
      require(identity.matches("[0-9a-f]{64}"), "derived identity missing")
      require(((core / Compile / classDirectory).value / "com/example/core/GenUser.class").isFile, "GenUser did not compile")
      require(((core / Compile / classDirectory).value / "com/example/core/Something.class").isFile, "Something did not compile")
      streams.value.log.info("SBT_PLUGIN_LOCAL_PROJECTS_NO_PRODUCER_PUBLISHLOCAL=PASS markerCompileClasspath=true handlerRuntimeAbsent=true exactFixture=true")
    }
  )
