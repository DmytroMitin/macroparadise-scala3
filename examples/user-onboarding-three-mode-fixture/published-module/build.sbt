import macroparadise.sbt.MacroParadisePrecompiledPlugin.autoImport._

ThisBuild / scalaVersion := sys.props("macroparadise.exactScalaVersion")
ThisBuild / organization := "com.example.onboarding"
ThisBuild / version := "0.0.1-onboarding-matrix"
ThisBuild / resolvers := Seq(
  "task-producer-repository" at file(sys.props("macroparadise.producerRepository")).toURI.toString,
  "task-product-repository" at file(sys.props("macroparadise.productRepository")).toURI.toString,
  Resolver.mavenCentral
)
ThisBuild / credentials := Nil

val mpVersion = "0.1.1-SNAPSHOT"
val producerVersion = "0.0.1-onboarding-matrix"
val mpApi =
  ("com.github.dmytromitin" % "macroparadise-scala3-plugin-api" % mpVersion)
    .cross(CrossVersion.full)
val producerRepository = Resolver.file(
  "task-producer-publish",
  file(sys.props("macroparadise.producerRepository"))
)(Resolver.mavenStylePatterns)

lazy val verifyFixture = taskKey[Unit]("Verify the exact published-module onboarding fixture")

lazy val macroAnnotations = (project in file("macro-annotations"))
  .settings(
    moduleName := "user-macro-annotations",
    crossVersion := CrossVersion.full,
    libraryDependencies += mpApi,
    publish / skip := false,
    publishTo := Some(producerRepository)
  )

lazy val macroHandlers = (project in file("macro-handlers"))
  .settings(
    moduleName := "user-macro-handlers",
    crossVersion := CrossVersion.full,
    libraryDependencies ++= Seq(
      mpApi,
      "org.scala-lang" %% "scala3-compiler" % scalaVersion.value
    ),
    publish / skip := false,
    publishTo := Some(producerRepository)
  )

lazy val core = (project in file("core"))
  .enablePlugins(macroparadise.sbt.MacroParadisePrecompiledPlugin)
  .settings(
    publish / skip := true,
    macroParadiseCompilerProductVersion := mpVersion,
    macroParadiseMarkerModules := Seq(
      ("com.example.onboarding" % "user-macro-annotations" % producerVersion)
        .cross(CrossVersion.full)
    ),
    macroParadiseHandlerModules := Seq(
      ("com.example.onboarding" % "user-macro-handlers" % producerVersion)
        .cross(CrossVersion.full)
    )
  )

lazy val root = (project in file("."))
  .aggregate(macroAnnotations, macroHandlers, core)
  .settings(
    publish / skip := true,
    verifyFixture := {
      (core / Compile / compile).value
      val compileClasspath = (core / Compile / fullClasspath).value.files.map(_.getCanonicalFile)
      val runtimeClasspath = (core / Runtime / fullClasspath).value.files.map(_.getCanonicalFile)
      val markerArtifacts = (core / macroParadiseMarkerArtifacts).value
      val handlerClasspath = (core / macroParadiseHandlerClasspath).value
      val identity = (core / macroParadiseExternalArtifactIdentity).value
      val primaryHandler = handlerClasspath.headOption.getOrElse(sys.error("published handler expansion classpath is empty"))
      require(markerArtifacts.nonEmpty && markerArtifacts.forall(artifact => compileClasspath.contains(artifact.file.getCanonicalFile)), "published marker missing from consumer compile classpath")
      require(!runtimeClasspath.contains(primaryHandler.file.getCanonicalFile), "handler implementation leaked onto consumer runtime classpath")
      require(identity.matches("[0-9a-f]{64}"), "derived identity missing")
      require(((core / Compile / classDirectory).value / "com/example/core/GenUser.class").isFile, "GenUser did not compile")
      require(((core / Compile / classDirectory).value / "com/example/core/Something.class").isFile, "Something did not compile")
      streams.value.log.info("SBT_PLUGIN_PUBLISHED_MODULES=PASS markerCompileClasspath=true handlerRuntimeAbsent=true exactFixture=true taskRepository=true")
    }
  )
