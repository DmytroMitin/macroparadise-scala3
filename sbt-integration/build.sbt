ThisBuild / organization := "com.github.dmytromitin"
ThisBuild / organizationName := "com.github.dmytromitin"
ThisBuild / version := "0.1.1-SNAPSHOT"
ThisBuild / versionScheme := Some("early-semver")
ThisBuild / scalaVersion := "2.12.21"
ThisBuild / sbtVersion := "1.12.15"
ThisBuild / publishMavenStyle := true
ThisBuild / publishTo := None
ThisBuild / credentials := Nil
ThisBuild / licenses := List("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0"))
ThisBuild / homepage := Some(url("https://github.com/DmytroMitin/macroparadise-scala3"))
ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/DmytroMitin/macroparadise-scala3"),
    "scm:git:https://github.com/DmytroMitin/macroparadise-scala3.git",
    Some("scm:git:ssh://git@github.com:DmytroMitin/macroparadise-scala3.git")
  )
)
ThisBuild / developers := List(
  Developer(
    "DmytroMitin",
    "Dmytro Mitin",
    "dmitin3@gmail.com",
    url("https://github.com/DmytroMitin")
  )
)
ThisBuild / pomIncludeRepository := (_ => false)

lazy val verifyIntegrationPolicy = taskKey[Unit]("Verify the sbt integration publication and dependency boundary")

lazy val sbtIntegration = project
  .in(file("."))
  .enablePlugins(SbtPlugin)
  .settings(
    name := "Macro Paradise sbt integration",
    moduleName := "sbt-macroparadise",
    Compile / packageBin / mappings += baseDirectory.value.getParentFile / "LICENSE" -> "META-INF/LICENSE",
    Compile / packageSrc / mappings += baseDirectory.value.getParentFile / "LICENSE" -> "META-INF/LICENSE",
    Compile / packageDoc / mappings += baseDirectory.value.getParentFile / "LICENSE" -> "META-INF/LICENSE",
    scriptedBufferLog := false,
    scriptedLaunchOpts ++= Seq("-Dplugin.version=" + version.value),
    libraryDependencies += "org.scalameta" %% "munit" % "1.2.4" % Test,
    verifyIntegrationPolicy := {
      require(scalaVersion.value.startsWith("2.12."), "sbt integration must stay in the sbt 1.x / Scala 2.12 universe")
      require(sbtVersion.value.startsWith("1."), "sbt integration must stay on sbt 1.x")
      require(
        libraryDependencies.value.forall(module => !module.organization.startsWith("org.scala-lang") || !module.name.startsWith("scala3")),
        "sbt integration must not depend on the Dotty/Scala 3 runtime"
      )
      require(publishTo.value.isEmpty, "remote publication destination must remain unset")
      require(credentials.value.isEmpty, "publication credentials must remain unset")
      require((Compile / packageSrc / publishArtifact).value, "source artifact must remain enabled")
      require((Compile / packageDoc / publishArtifact).value, "documentation artifact must remain enabled")
      require(versionScheme.value.contains("early-semver"), "sbt integration version scheme must be early-semver")
      require(scmInfo.value.nonEmpty, "sbt integration SCM metadata must be present")
      require(developers.value.nonEmpty, "sbt integration developer metadata must be present")
      streams.value.log.info("sbt integration policy verified: sbt1.x/scala2.12 dottyRuntime=false publishTo=none credentials=none")
    }
  )
