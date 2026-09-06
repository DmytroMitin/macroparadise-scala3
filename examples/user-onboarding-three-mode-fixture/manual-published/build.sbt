import java.io.File
import scala.collection.mutable

ThisBuild / scalaVersion := sys.props("macroparadise.exactScalaVersion")
ThisBuild / organization := "com.example.manualpublished"
ThisBuild / version := "0.0.1-onboarding-matrix"
ThisBuild / resolvers := Seq(
  "task-producer-repository" at file(sys.props("macroparadise.producerRepository")).toURI.toString,
  "task-product-repository" at file(sys.props("macroparadise.productRepository")).toURI.toString,
  Resolver.mavenCentral
)
ThisBuild / credentials := Nil

val mpVersion = "0.2.0-SNAPSHOT"
val producerVersion = "0.0.1-onboarding-matrix"
val mpApi =
  ("com.github.dmytromitin" % "macroparadise-scala3-plugin-api" % mpVersion)
    .cross(CrossVersion.full)
val mpPlugin =
  ("com.github.dmytromitin" % "macroparadise-scala3-plugin" % mpVersion)
    .cross(CrossVersion.full)
val producerRepository = Resolver.file(
  "task-producer-publish",
  file(sys.props("macroparadise.producerRepository"))
)(Resolver.mavenStylePatterns)

val MacroParadiseHandler = config("macroParadiseHandler").hide
lazy val markerArtifacts = taskKey[Seq[(String, File)]]("Exact published marker inventory")
lazy val handlerClasspath = taskKey[Seq[(String, File)]]("Complete published handler closure")
lazy val externalArtifactIdentity = taskKey[String]("Combined marker and handler identity")
lazy val verifyFixture = taskKey[Unit]("Verify manual published-module onboarding")

val markerModules = Seq(
  (("com.example.manualpublished" % "manual-marker-a" % producerVersion)
    .cross(CrossVersion.full)) % Provided,
  (("com.example.manualpublished" % "manual-marker-b" % producerVersion)
    .cross(CrossVersion.full)) % Provided
)
val handlerModules = Seq(
  ("com.example.manualpublished" % "manual-handler-a" % producerVersion)
    .cross(CrossVersion.full),
  ("com.example.manualpublished" % "manual-handler-b" % producerVersion)
    .cross(CrossVersion.full)
)

def resolveConfigured(
    modules: Seq[ModuleID],
    classpath: Classpath,
    role: String
): Seq[(String, File)] =
  modules.flatMap { requested =>
    val matches = classpath.filter(_.get(moduleID.key).exists { actual =>
      actual.organization == requested.organization &&
      (actual.name == requested.name || actual.name.startsWith(requested.name + "_")) &&
      actual.revision == requested.revision
    })
    require(matches.nonEmpty,
      s"configured $role module did not resolve: ${requested.organization}:${requested.name}:${requested.revision}")
    matches.zipWithIndex.map { case (entry, index) =>
      s"${requested.organization}:${requested.name}:${requested.revision}:$index" -> entry.data
    }
  }

def completeHandlers(
    modules: Seq[ModuleID],
    classpath: Classpath
): Seq[(String, File)] = {
  val direct = resolveConfigured(modules, classpath, "handler")
  val directPaths = direct.map(_._2.getCanonicalFile).toSet
  val transitive = classpath.iterator
    .filterNot(entry => directPaths(entry.data.getCanonicalFile))
    .zipWithIndex
    .map { case (entry, index) =>
      val coordinate = entry.get(moduleID.key)
        .map(m => s"${m.organization}:${m.name}:${m.revision}")
        .getOrElse(entry.data.getName)
      f"transitive-$index%04d:$coordinate" -> entry.data
    }.toVector
  val seen = mutable.LinkedHashSet.empty[File]
  (direct ++ transitive).filter { case (_, file) => seen.add(file.getCanonicalFile) }
}

def producerSettings(module: String): Seq[Def.Setting[_]] = Seq(
  moduleName := module,
  crossVersion := CrossVersion.full,
  publish / skip := false,
  publishTo := Some(producerRepository)
)

lazy val sharedHandlerRuntime = (project in file("shared-handler-runtime"))
  .settings(producerSettings("manual-handler-runtime"))

lazy val markerA = (project in file("marker-a"))
  .settings(producerSettings("manual-marker-a"))
  .settings(libraryDependencies += mpApi)

lazy val markerB = (project in file("marker-b"))
  .settings(producerSettings("manual-marker-b"))
  .settings(libraryDependencies += mpApi)

def handlerProject(id: String, module: String): Project =
  Project(id, file(id))
    .dependsOn(sharedHandlerRuntime)
    .settings(producerSettings(module))
    .settings(
      libraryDependencies ++= Seq(
        mpApi,
        "org.scala-lang" %% "scala3-compiler" % scalaVersion.value
      )
    )

lazy val handlerA = handlerProject("handler-a", "manual-handler-a")
lazy val handlerB = handlerProject("handler-b", "manual-handler-b")

lazy val core = (project in file("core"))
  .configs(MacroParadiseHandler)
  .settings(inConfig(MacroParadiseHandler)(Defaults.configSettings))
  .settings(
    publish / skip := true,
    libraryDependencies += compilerPlugin(mpPlugin),
    libraryDependencies ++= markerModules,
    libraryDependencies ++= handlerModules.map(_ % MacroParadiseHandler.name),
    markerArtifacts := resolveConfigured(
      markerModules,
      (Compile / dependencyClasspath).value,
      "marker"
    ),
    handlerClasspath := completeHandlers(
      handlerModules,
      (MacroParadiseHandler / dependencyClasspath).value
    ),
    externalArtifactIdentity := ExternalArtifactIdentity.combined(
      markerArtifacts.value,
      handlerClasspath.value
    ),
    Compile / scalacOptions ++= Seq(
      "-Xplugin-require:macroparadise",
      "-P:macroparadise:handlerClasspath=" +
        handlerClasspath.value.map(_._2.getAbsolutePath).mkString(File.pathSeparator),
      "-P:macroparadise:externalArtifactIdentity=sha256:" +
        externalArtifactIdentity.value
    )
  )

lazy val root = (project in file("."))
  .aggregate(sharedHandlerRuntime, markerA, markerB, handlerA, handlerB, core)
  .settings(
    publish / skip := true,
    verifyFixture := {
      (core / Compile / compile).value
      val markers = (core / markerArtifacts).value
      val handlers = (core / handlerClasspath).value
      val compileClasspath = (core / Compile / fullClasspath).value.files.map(_.getCanonicalFile)
      val runtimeClasspath = (core / Runtime / fullClasspath).value.files.map(_.getCanonicalFile)
      val handlerNames = handlers.map(_._2.getName)
      require(markers.size == 2, s"expected two published marker modules, found ${markers.size}")
      require(markers.forall { case (_, file) => compileClasspath.contains(file.getCanonicalFile) }, "published marker missing from compile classpath")
      require(markers.forall { case (_, file) => !runtimeClasspath.contains(file.getCanonicalFile) }, "Provided marker leaked onto runtime classpath")
      require(handlerNames.take(2).exists(_.contains("manual-handler-a")) && handlerNames.take(2).exists(_.contains("manual-handler-b")), "direct handlers are not first")
      require(handlerNames.count(_.contains("manual-handler-runtime")) == 1, "shared handler runtime was not retained exactly once")
      val handlerToolArtifacts = handlers.collect { case (_, file)
          if file.getName.contains("manual-handler-a") ||
            file.getName.contains("manual-handler-b") ||
            file.getName.contains("manual-handler-runtime") => file.getCanonicalFile }
      require(handlerToolArtifacts.size == 3, s"expected three handler tool artifacts, found ${handlerToolArtifacts.map(_.getName)}")
      require(handlerToolArtifacts.forall(file => !runtimeClasspath.contains(file)), "handler tool closure leaked onto runtime classpath")
      require((core / externalArtifactIdentity).value.matches("[0-9a-f]{64}"), "manual external identity missing")
      val options = (core / Compile / scalacOptions).value
      require(options.contains("-Xplugin-require:macroparadise"), "plugin requirement option missing")
      require(options.count(_.startsWith("-P:macroparadise:handlerClasspath=")) == 1, "handler classpath option missing")
      require(options.count(_.matches("-P:macroparadise:externalArtifactIdentity=sha256:[0-9a-f]{64}")) == 1, "identity option missing")
      require(((core / Compile / classDirectory).value / "fixture" / "SubjectA.class").isFile, "SubjectA did not compile")
      require(((core / Compile / classDirectory).value / "fixture" / "SubjectB.class").isFile, "SubjectB did not compile")
      streams.value.log.info("MANUAL_PUBLISHED_MODULES=PASS markers=2 handlers=2 sharedRuntime=1 markerRuntimeAbsent=true handlerRuntimeAbsent=true")
    }
  )
