import java.io.File

ThisBuild / scalaVersion := sys.props("macroparadise.exactScalaVersion")
ThisBuild / version := "0.0.1-onboarding-matrix"
ThisBuild / resolvers := Seq(
  "task-product-repository" at file(sys.props("macroparadise.productRepository")).toURI.toString,
  Resolver.mavenCentral
)
ThisBuild / credentials := Nil
ThisBuild / publish / skip := true

val mpOrg = "com.github.dmytromitin"
val mpVersion = "0.1.1-SNAPSHOT"
val mpApi = (mpOrg % "macroparadise-scala3-plugin-api" % mpVersion).cross(CrossVersion.full)
val macroparadisePlugin =
  (mpOrg % "macroparadise-scala3-plugin" % mpVersion).cross(CrossVersion.full)

lazy val verifyFixture = taskKey[Unit]("Verify the exact manual onboarding fixture")

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
  .settings(
    libraryDependencies += compilerPlugin(macroparadisePlugin),
    Compile / scalacOptions ++= {
      val markerJar = (macroAnnotations / Compile / packageBin).value
      val handlerJar = (macroHandlers / Compile / packageBin).value
      val handlerClasses = (macroHandlers / Compile / classDirectory).value.getCanonicalFile
      val handlerClasspath = handlerJar +:
        (macroHandlers / Runtime / dependencyClasspath).value.files
          .filterNot(_.getCanonicalFile == handlerClasses)
      val buildIdentity = ExternalArtifactIdentity.combined(
        Seq("marker" -> markerJar),
        handlerClasspath.zipWithIndex.map { case (file, index) =>
          f"handler-$index%04d" -> file
        }
      )
      Seq(
        "-Xplugin-require:macroparadise",
        s"-P:macroparadise:handlerClasspath=${handlerClasspath.map(_.getAbsolutePath).mkString(File.pathSeparator)}",
        s"-P:macroparadise:externalArtifactIdentity=sha256:$buildIdentity"
      )
    }
  )

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
      val options = (core / Compile / scalacOptions).value
      require(compileClasspath.contains(markerClasses), "marker classes missing from consumer compile classpath")
      require(!runtimeClasspath.contains(handlerClasses) && !runtimeClasspath.contains(handlerJar), "handler leaked onto consumer runtime classpath")
      require(options.count(_.startsWith("-P:macroparadise:handlerClasspath=")) == 1, "handler classpath option missing")
      require(options.count(_.matches("-P:macroparadise:externalArtifactIdentity=sha256:[0-9a-f]{64}")) == 1, "identity option missing")
      require(((core / Compile / classDirectory).value / "com/example/core/GenUser.class").isFile, "GenUser did not compile")
      require(((core / Compile / classDirectory).value / "com/example/core/Something.class").isFile, "Something did not compile")
      streams.value.log.info("MANUAL_SETUP=PASS markerCompileClasspath=true handlerRuntimeAbsent=true exactFixture=true")
    }
  )
