import macroparadise.sbt.MacroParadiseIntegration
import macroparadise.sbt.MacroParadisePrecompiledPlugin
import MacroParadisePrecompiledPlugin.autoImport.*

val exactScalaVersion =
  sys.props.getOrElse("macroparadise.example.scalaVersion", "3.8.4")

ThisBuild / scalaVersion := exactScalaVersion
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / organization := "example.existingmethod"
ThisBuild / publish / skip := true
ThisBuild / resolvers ++= sys.props.get("macroparadise.example.localIvy").toSeq.map { root =>
  Resolver.file("isolated-local-ivy", file(root))(Resolver.ivyStylePatterns)
}

Global / onLoad := { state =>
  val exact = Set("3.3.8", "3.8.4", "3.9.0")
  require(exact(exactScalaVersion), s"unsupported exact Scala line $exactScalaVersion")
  require(
    java.lang.Runtime.version().feature() == 25,
    s"existing-method U-U starter requires JDK 25, found ${java.lang.Runtime.version().feature()}"
  )
  state
}

val mpVersion = "0.2.0-SNAPSHOT"
val quasiVersion = "0.4.0-SNAPSHOT"
val mpApi =
  ("com.github.dmytromitin" % "macroparadise-scala3-plugin-api" % mpVersion)
    .cross(CrossVersion.full)
val quasiDotty =
  ("com.github.dmytromitin" % "quasiquotes-scala3-dotty-internal" % quasiVersion)
    .cross(CrossVersion.full)

lazy val verifyStarter = taskKey[Unit](
  "Compile and run the existing-method U-U starter and verify role isolation"
)

lazy val marker = project
  .in(file("marker"))
  .settings(
    name := "existing-method-u-u-starter-marker",
    libraryDependencies += mpApi % Provided
  )

lazy val handler = project
  .in(file("handler"))
  .settings(
    name := "existing-method-u-u-starter-handler",
    libraryDependencies ++= Seq(mpApi, quasiDotty)
  )

def consumerProject(id: String, directory: String): Project =
  Project(id, file(directory))
    .dependsOn(marker % "provided->compile")
    .enablePlugins(MacroParadisePrecompiledPlugin)
    .settings(MacroParadiseIntegration.precompiledProjects(marker, handler))
    .settings(
      macroParadiseCompilerProductVersion := mpVersion
    )

lazy val consumer = consumerProject("consumer", "consumer")
lazy val negativeCall = consumerProject("negativeCall", "negative-call")
lazy val unsupportedShape = consumerProject("unsupportedShape", "unsupported-shape")

lazy val root = project
  .in(file("."))
  .aggregate(marker, handler, consumer)
  .settings(
    verifyStarter := {
      (consumer / Compile / compile).value
      val markerClasses = (marker / Compile / classDirectory).value.getCanonicalFile
      val handlerClasses = (handler / Compile / classDirectory).value.getCanonicalFile
      val handlerJar = (handler / Compile / packageBin).value.getCanonicalFile
      val compileClasspath =
        (consumer / Compile / fullClasspath).value.files.map(_.getCanonicalFile)
      val runtimeClasspath =
        (consumer / Runtime / fullClasspath).value.files.map(_.getCanonicalFile)
      val handlerClasspath =
        (consumer / macroParadiseHandlerClasspath).value.map(_.file.getCanonicalFile)
      val compilerOptions = (consumer / Compile / scalacOptions).value
      val quasiJars = handlerClasspath.filter(
        _.getName.startsWith("quasiquotes-scala3-dotty-internal_" + scalaVersion.value)
      )

      require(compileClasspath.contains(markerClasses), "marker is absent from consumer compile classpath")
      require(!runtimeClasspath.contains(markerClasses), "marker leaked onto consumer runtime classpath")
      require(handlerClasspath.headOption.contains(handlerJar), "handler is not first in its hidden closure")
      require(
        !runtimeClasspath.contains(handlerClasses) && !runtimeClasspath.contains(handlerJar),
        "handler leaked onto consumer runtime classpath"
      )
      require(quasiJars.size == 1, s"expected one exact Quasiquotes dotty-internal JAR, found $quasiJars")
      require(
        !runtimeClasspath.exists(_.getName.contains("quasiquotes-scala3-dotty-internal")),
        "Quasiquotes compiler-facing backend leaked onto consumer runtime classpath"
      )
      require(
        !handlerClasspath.exists(_.getName.startsWith("macroparadise-scala3-plugin_")),
        "Macro-Paradise compiler-plugin implementation leaked onto the handler child path"
      )
      require(
        compilerOptions.contains("-Xplugin-require:macroparadise") &&
          compilerOptions.exists(_.contains("macroparadise-scala3-plugin_" + scalaVersion.value)),
        "exact Macro-Paradise compiler plugin is absent"
      )

      (consumer / Compile / runner).value
        .run(
          "starter.consumer.Main",
          (consumer / Runtime / fullClasspath).value.files,
          Seq.empty,
          streams.value.log
        )
        .get
    }
  )
