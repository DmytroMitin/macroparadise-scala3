import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, StandardCopyOption}
import java.security.MessageDigest

import scala.sys.process.{Process, ProcessLogger}

object EmbeddedProducerSbtIntegrationMatrix {
  final case class Config(scalaVersion: String, sbtVersion: String, productVersion: String)

  final case class Result(
      scalaVersion: String,
      sameBuildSbtIntegration: Boolean,
      sameBuildManual: Boolean,
      publishedSbtIntegration: Boolean,
      publishedManual: Boolean,
      transformEditNoClean: Boolean,
      handlerDependencyInvalidation: Boolean,
      manualProducerHelper: Boolean,
      negativeBuildMatrix: Boolean,
      evidenceDirectory: File
  ) {
    def render: String =
      s"scala=$scalaVersion sameBuildSbt=$sameBuildSbtIntegration " +
        s"sameBuildManual=$sameBuildManual publishedSbt=$publishedSbtIntegration " +
        s"publishedManual=$publishedManual transformEditNoClean=$transformEditNoClean " +
        s"handlerDependencyInvalidation=$handlerDependencyInvalidation " +
        s"manualProducerHelper=$manualProducerHelper negativeBuildMatrix=$negativeBuildMatrix evidence=$evidenceDirectory"
  }

  private val ProductOrganization = "com.github.dmytromitin"
  private val FixtureOrganization = "example.embedded"
  private val FixtureBaseModule = "sample-embedded-producer"
  private val FixtureVersion = "1.0.0"

  def verify(
      repositoryRoot: File,
      pluginApiJar: File,
      consumerPluginJar: File,
      producerPluginJar: File,
      pluginApiPom: File,
      consumerPluginPom: File,
      producerPluginPom: File,
      taskRoot: File,
      config: Config
  ): Result = {
    require(Set("3.3.8", "3.8.4", "3.9.0")(config.scalaVersion), "unsupported exact Scala line")
    require(config.sbtVersion == "1.12.15", "unsupported sbt version")
    sbt.IO.delete(taskRoot)
    sbt.IO.createDirectory(taskRoot)
    val productRepository = new File(taskRoot, "product-repository")
    val roleRepository = new File(taskRoot, "role-repository")
    val evidence = new File(taskRoot, "evidence")
    sbt.IO.createDirectory(evidence)
    stageProduct(productRepository, pluginApiJar, pluginApiPom, "macroparadise-scala3-plugin-api", config)
    stageProduct(productRepository, consumerPluginJar, consumerPluginPom, "macroparadise-scala3-plugin", config)
    stageProduct(productRepository, producerPluginJar, producerPluginPom, "macroparadise-scala3-embedded-producer-plugin", config)

    val sameBuild = new File(taskRoot, "same-build")
    createSameBuild(repositoryRoot, productRepository, roleRepository, evidence, sameBuild, config)
    val persistentLog = new File(evidence, "10-same-build-persistent.log")
    val persistentCommands = Vector(
      "clean",
      "coreLocalSbt/run",
      "recordBaseline",
      "coreLocalSbt/compile",
      "recordNoOp",
      "editTransform",
      "coreLocalSbt/run",
      "recordTransformEdited",
      "coreLocalSbt/compile",
      "recordTransformSecondNoOp",
      "restoreTransform",
      "coreLocalSbt/run",
      "recordTransformRestored",
      "recordDependencyBaseline",
      "editRuntimeDependency",
      "coreLocalSbt/run",
      "recordDependencyEdited",
      "restoreRuntimeDependency",
      "coreLocalSbt/run",
      "recordDependencyRestored",
      "coreLocalSbt/verifyQuadrant",
      "coreLocalManual/verifyManualQuadrant",
      "publishEmbeddedRoles"
    )
    require(
      runSbt(sameBuild, persistentCommands, persistentLog) == 0,
      s"same-build persistent embedded integration failed; see $persistentLog"
    )
    val persistentText = read(persistentLog)
    require(persistentText.contains("EMBEDDED_SAME_BUILD_SBT_PASS"), "same-build sbt witness is absent")
    require(persistentText.contains("EMBEDDED_SAME_BUILD_MANUAL_PASS"), "same-build manual witness is absent")
    require(persistentText.contains("EMBEDDED_RUNTIME:runtime-v2"), "transform edit runtime witness is absent")
    require(persistentText.contains("EMBEDDED_RUNTIME:dependency-v2"), "dependency edit runtime witness is absent")

    val baseline = readState(new File(evidence, "baseline.state"))
    val noOp = readState(new File(evidence, "noop.state"))
    val transformEdited = readState(new File(evidence, "transform-edited.state"))
    val transformSecondNoOp = readState(new File(evidence, "transform-second-noop.state"))
    val transformRestored = readState(new File(evidence, "transform-restored.state"))
    validateTransformTransition(baseline, noOp, transformEdited, transformSecondNoOp, transformRestored)

    val dependencyBaseline = readState(new File(evidence, "dependency-baseline.state"))
    val dependencyEdited = readState(new File(evidence, "dependency-edited.state"))
    val dependencyRestored = readState(new File(evidence, "dependency-restored.state"))
    validateDependencyTransition(dependencyBaseline, dependencyEdited, dependencyRestored)

    val publishedBuild = new File(taskRoot, "published-consumers")
    createPublishedBuild(repositoryRoot, productRepository, roleRepository, publishedBuild, config)
    val publishedLog = new File(evidence, "20-published-quadrants.log")
    require(
      runSbt(
        publishedBuild,
        Vector("corePublishedSbt/verifyQuadrant", "corePublishedManual/verifyManualQuadrant"),
        publishedLog
      ) == 0,
      s"published embedded integration failed; see $publishedLog"
    )
    val publishedText = read(publishedLog)
    require(publishedText.contains("EMBEDDED_PUBLISHED_SBT_PASS"), "published sbt witness is absent")
    require(publishedText.contains("EMBEDDED_PUBLISHED_MANUAL_PASS"), "published manual witness is absent")

    val negativeBuildMatrix =
      if (config.scalaVersion == "3.8.4") verifyNegativeBuildMatrix(sameBuild, evidence)
      else true

    val sourceHelper = new File(repositoryRoot, "sbt-integration/src/main/scala/macroparadise/sbt/EmbeddedProducerRoles.scala")
    val copiedHelper = new File(sameBuild, "project/EmbeddedProducerRoles.scala")
    val manualHelperSelfContained = sha256(sourceHelper) == sha256(copiedHelper)
    require(manualHelperSelfContained, "manual producer helper differs from the AutoPlugin implementation source")

    Result(
      config.scalaVersion,
      sameBuildSbtIntegration = true,
      sameBuildManual = true,
      publishedSbtIntegration = true,
      publishedManual = true,
      transformEditNoClean = true,
      handlerDependencyInvalidation = true,
      manualProducerHelper = true,
      negativeBuildMatrix = negativeBuildMatrix,
      evidence
    )
  }

  private def verifyNegativeBuildMatrix(build: File, evidence: File): Boolean = {
    val cases = Vector(
      ("unsupported-or-missing-generator", Vector(
        "set producer / scalaVersion := \"3.4.0\"",
        "producer/macroParadiseEmbeddedValidate"
      ), Vector("not found", "unsupported embedded producer Scala version")),
      ("missing-generator-coordinate", Vector(
        "set producer / macroParadiseEmbeddedProducerCompilerPluginModule := (\"example.invalid\" % \"missing-generator\" % \"0\").cross(CrossVersion.full)",
        "producer/compile"
      ), Vector("not found", "unresolved dependency")),
      ("role-output-and-module-collision", Vector(
        "set producer / macroParadiseEmbeddedMarkerModuleName := \"collision\"",
        "set producer / macroParadiseEmbeddedHandlerModuleName := \"collision\"",
        "producer/macroParadiseEmbeddedValidate"
      ), Vector("distinct output JARs", "module names must be distinct")),
      ("strict-empty-producer", Vector(
        "set producer / Compile / sources := Seq.empty",
        "producer/clean",
        "producer/macroParadiseEmbeddedValidate"
      ), Vector("no valid embedded declaration")),
      ("derived-handler-not-jar", Vector(
        "set producer / macroParadiseEmbeddedHandlerArtifact := (producer / target).value",
        "producer/macroParadiseEmbeddedHandlerClasspath"
      ), Vector("not a regular JAR")),
      ("missing-handler-runtime-closure", Vector(
        "set producer / macroParadiseEmbeddedHandlerClasspath := Seq((producer / macroParadiseEmbeddedHandlerArtifact).value)",
        "coreLocalSbt/clean",
        "coreLocalSbt/compile"
      ), Vector("RuntimeToken", "NoClassDefFoundError", "ClassNotFoundException"))
    )
    cases.foreach { case (id, commands, fragments) =>
      val log = new File(evidence, "negative-" + id + ".log")
      val exit = runSbt(build, commands, log)
      val body = read(log)
      require(exit != 0, id + " unexpectedly passed")
      require(fragments.exists(body.contains), id + " lacked an actionable role-specific diagnostic")
    }
    val invalidHelperBuild = new File(build.getParentFile, "invalid-helper")
    sbt.IO.delete(invalidHelperBuild)
    sbt.IO.createDirectory(new File(invalidHelperBuild, "project"))
    Vector(
      "ArtifactIdentity.scala",
      "EmbeddedProducerRoles.scala",
      "MacroParadisePrecompiledPlugin.scala",
      "MacroParadiseEmbeddedProducerPlugin.scala"
    ).foreach { source =>
      Files.copy(
        new File(build, "project/" + source).toPath,
        new File(invalidHelperBuild, "project/" + source).toPath,
        StandardCopyOption.REPLACE_EXISTING
      )
    }
    Files.copy(
      new File(build, "project/build.properties").toPath,
      new File(invalidHelperBuild, "project/build.properties").toPath,
      StandardCopyOption.REPLACE_EXISTING
    )
    Files.copy(
      new File(build, "repositories").toPath,
      new File(invalidHelperBuild, "repositories").toPath,
      StandardCopyOption.REPLACE_EXISTING
    )
    write(
      new File(invalidHelperBuild, "build.sbt"),
      """import macroparadise.sbt.{MacroParadiseIntegration, MacroParadisePrecompiledPlugin}
        |lazy val plainProducer = project.in(file("plain-producer"))
        |lazy val invalidConsumer = project.in(file("invalid-consumer"))
        |  .enablePlugins(MacroParadisePrecompiledPlugin)
        |  .settings(MacroParadiseIntegration.precompiledEmbeddedProject(plainProducer))
        |""".stripMargin
    )
    val helperLog = new File(evidence, "negative-consumer-helper-without-producer-plugin.log")
    val helperExit = runSbt(invalidHelperBuild, Vector("invalidConsumer/update"), helperLog)
    val helperBody = read(helperLog)
    require(helperExit != 0, "consumer helper without producer plugin unexpectedly loaded")
    require(
      helperBody.contains("macroParadiseEmbeddedMarkerArtifact") && helperBody.contains("undefined"),
      "consumer helper without producer plugin lacked the expected static-edge diagnostic"
    )
    true
  }

  private def validateTransformTransition(
      baseline: Map[String, String],
      noOp: Map[String, String],
      edited: Map[String, String],
      secondNoOp: Map[String, String],
      restored: Map[String, String]
  ): Unit = {
    require(baseline("markerSha256") == noOp("markerSha256"), "baseline no-op changed marker role")
    require(baseline("handlerSha256") == noOp("handlerSha256"), "baseline no-op changed handler role")
    require(baseline("identity") == noOp("identity"), "baseline no-op changed external identity")
    require(baseline("consumerSha256") == noOp("consumerSha256"), "baseline no-op changed consumer output")
    require(baseline("consumerMtime") == noOp("consumerMtime"), "baseline no-op rewrote consumer output")
    require(baseline("markerSha256") == edited("markerSha256"), "transform-only edit changed marker role")
    require(baseline("handlerSha256") != edited("handlerSha256"), "transform-only edit did not change handler role")
    require(baseline("identity") != edited("identity"), "transform-only edit did not change external identity")
    require(baseline("consumerSha256") != edited("consumerSha256"), "transform-only edit did not recompile unchanged consumer")
    require(edited("markerSha256") == secondNoOp("markerSha256"), "second no-op changed marker role")
    require(edited("handlerSha256") == secondNoOp("handlerSha256"), "second no-op changed handler role")
    require(edited("identity") == secondNoOp("identity"), "second no-op changed identity")
    require(edited("consumerSha256") == secondNoOp("consumerSha256"), "second no-op changed consumer output")
    require(edited("consumerMtime") == secondNoOp("consumerMtime"), "second no-op rewrote consumer output")
    Vector("markerSha256", "handlerSha256", "identity", "consumerSha256").foreach { key =>
      require(baseline(key) == restored(key), s"restored transform did not recover baseline $key")
    }
    require(edited("runtimeIsolated") == "true", "handler/compiler implementation leaked onto application runtime")
    require(!edited("handlerClasspath").split(File.pathSeparator).exists(_.contains("scala3-compiler")),
      "Scala compiler implementation leaked onto handler child path")
  }

  private def validateDependencyTransition(
      baseline: Map[String, String],
      edited: Map[String, String],
      restored: Map[String, String]
  ): Unit = {
    require(baseline("markerSha256") == edited("markerSha256"), "handler dependency edit changed marker role")
    require(baseline("handlerSha256") == edited("handlerSha256"), "handler dependency edit changed direct handler role")
    require(baseline("dependencySha256") != edited("dependencySha256"), "handler dependency edit did not change closure bytes")
    require(baseline("identity") != edited("identity"), "handler dependency edit did not change external identity")
    require(baseline("consumerSha256") != edited("consumerSha256"), "handler dependency edit did not recompile consumer")
    Vector("markerSha256", "handlerSha256", "dependencySha256", "identity", "consumerSha256").foreach { key =>
      require(baseline(key) == restored(key), s"restored handler dependency did not recover baseline $key")
    }
    require(edited("runtimeIsolated") == "true", "handler dependency leaked onto application runtime")
  }

  private def stageProduct(
      repository: File,
      jar: File,
      pom: File,
      baseModule: String,
      config: Config
  ): Unit = {
    val module = baseModule + "_" + config.scalaVersion
    val directory = new File(
      repository,
      ProductOrganization.replace('.', '/') + "/" + module + "/" + config.productVersion
    )
    sbt.IO.createDirectory(directory)
    Files.copy(jar.toPath, new File(directory, module + "-" + config.productVersion + ".jar").toPath, StandardCopyOption.REPLACE_EXISTING)
    Files.copy(pom.toPath, new File(directory, module + "-" + config.productVersion + ".pom").toPath, StandardCopyOption.REPLACE_EXISTING)
  }

  private def createSameBuild(
      repositoryRoot: File,
      productRepository: File,
      roleRepository: File,
      evidence: File,
      build: File,
      config: Config
  ): Unit = {
    Vector(
      "project",
      "transform-runtime/src/main/scala/support",
      "producer/src/main/scala/embedded",
      "manual-producer/src/main/scala/embedded",
      "consumer-sbt/src/main/scala/consumer",
      "consumer-manual/src/main/scala/consumer",
      "facades/marker",
      "facades/handler"
    ).foreach(path => sbt.IO.createDirectory(new File(build, path)))
    write(new File(build, "project/build.properties"), "sbt.version=" + config.sbtVersion + "\n")
    writeRepositories(new File(build, "repositories"), Vector(productRepository))
    Vector("ArtifactIdentity.scala", "EmbeddedProducerRoles.scala", "MacroParadisePrecompiledPlugin.scala", "MacroParadiseEmbeddedProducerPlugin.scala").foreach { source =>
      Files.copy(
        new File(repositoryRoot, "sbt-integration/src/main/scala/macroparadise/sbt/" + source).toPath,
        new File(build, "project/" + source).toPath,
        StandardCopyOption.REPLACE_EXISTING
      )
    }
    Files.copy(
      new File(repositoryRoot, "examples/external-handler-starter/project/ExternalArtifactIdentity.scala").toPath,
      new File(build, "project/ExternalArtifactIdentity.scala").toPath,
      StandardCopyOption.REPLACE_EXISTING
    )
    write(
      new File(build, "transform-runtime/src/main/scala/support/RuntimeToken.scala"),
      """package support
        |object RuntimeToken:
        |  val value: String = "runtime-v1"
        |""".stripMargin
    )
    write(new File(build, "producer/src/main/scala/embedded/Generated.scala"), producerSource)
    write(new File(build, "manual-producer/src/main/scala/embedded/Generated.scala"), producerSource)
    write(new File(build, "consumer-sbt/src/main/scala/consumer/Main.scala"), consumerSource(named = true))
    write(new File(build, "consumer-manual/src/main/scala/consumer/Main.scala"), consumerSource(named = false))
    write(new File(build, "build.sbt"), sameBuildText(productRepository, roleRepository, evidence, config))
  }

  private def sameBuildText(
      productRepository: File,
      roleRepository: File,
      evidence: File,
      config: Config
  ): String =
    s"""import java.io.File
       |import java.nio.charset.StandardCharsets
       |import java.nio.file.Files
       |import java.security.MessageDigest
       |import macroparadise.sbt.{EmbeddedProducerRoles, MacroParadiseEmbeddedProducerPlugin, MacroParadiseIntegration, MacroParadisePrecompiledPlugin}
       |import MacroParadiseEmbeddedProducerPlugin.autoImport._
       |import MacroParadisePrecompiledPlugin.autoImport._
       |
       |ThisBuild / scalaVersion := "${config.scalaVersion}"
       |ThisBuild / version := "$FixtureVersion"
       |ThisBuild / organization := "$FixtureOrganization"
       |ThisBuild / resolvers := Seq(
       |  "task-product-repository" at "${scalaString(productRepository.toURI.toString)}",
       |  Resolver.mavenCentral
       |)
       |ThisBuild / credentials := Nil
       |ThisBuild / publishMavenStyle := true
       |ThisBuild / publishTo := Some("task-role-repository" at "${scalaString(roleRepository.toURI.toString)}")
       |ThisBuild / publish / skip := true
       |
       |val mpVersion = "${config.productVersion}"
       |val mpApi = ("$ProductOrganization" % "macroparadise-scala3-plugin-api" % mpVersion).cross(CrossVersion.full)
       |val mpConsumer = ("$ProductOrganization" % "macroparadise-scala3-plugin" % mpVersion).cross(CrossVersion.full)
       |val mpProducer = ("$ProductOrganization" % "macroparadise-scala3-embedded-producer-plugin" % mpVersion).cross(CrossVersion.full)
       |
       |lazy val ManualEmbeddedMarker = config("manualEmbeddedMarker").hide
       |lazy val manualRoleInventory = taskKey[EmbeddedProducerRoles.RoleInventory]("Manual embedded role inventory")
       |lazy val manualMarkerArtifact = taskKey[File]("Manual marker role")
       |lazy val manualHandlerArtifact = taskKey[File]("Manual handler role")
       |lazy val manualHandlerClasspath = taskKey[Seq[File]]("Manual complete handler classpath")
       |lazy val verifyQuadrant = taskKey[Unit]("Verify embedded quadrant")
       |lazy val verifyManualQuadrant = taskKey[Unit]("Verify manual embedded quadrant")
       |lazy val editTransform = taskKey[Unit]("Edit only the transform body")
       |lazy val restoreTransform = taskKey[Unit]("Restore only the transform body")
       |lazy val editRuntimeDependency = taskKey[Unit]("Edit handler runtime dependency")
       |lazy val restoreRuntimeDependency = taskKey[Unit]("Restore handler runtime dependency")
       |lazy val recordBaseline = taskKey[Unit]("Record baseline state")
       |lazy val recordNoOp = taskKey[Unit]("Record no-op state")
       |lazy val recordTransformEdited = taskKey[Unit]("Record transform-edited state")
       |lazy val recordTransformSecondNoOp = taskKey[Unit]("Record transform second no-op state")
       |lazy val recordTransformRestored = taskKey[Unit]("Record transform-restored state")
       |lazy val recordDependencyBaseline = taskKey[Unit]("Record dependency baseline state")
       |lazy val recordDependencyEdited = taskKey[Unit]("Record dependency-edited state")
       |lazy val recordDependencyRestored = taskKey[Unit]("Record dependency-restored state")
       |lazy val publishEmbeddedRoles = taskKey[Unit]("Publish task-owned embedded role modules")
       |
       |lazy val transformRuntime = project.in(file("transform-runtime"))
       |  .settings(moduleName := "sample-transform-runtime", publish / skip := false)
       |
       |lazy val producer = project.in(file("producer"))
       |  .dependsOn(transformRuntime)
       |  .enablePlugins(MacroParadiseEmbeddedProducerPlugin)
       |  .settings(moduleName := "$FixtureBaseModule", macroParadiseEmbeddedCompilerProductVersion := mpVersion)
       |
       |lazy val manualProducer = project.in(file("manual-producer"))
       |  .configs(ManualEmbeddedMarker)
       |  .dependsOn(transformRuntime)
       |  .settings(inConfig(ManualEmbeddedMarker)(Defaults.configSettings))
       |  .settings(
       |    moduleName := "$FixtureBaseModule-manual",
       |    libraryDependencies ++= Seq(compilerPlugin(mpProducer), mpApi),
       |    Compile / scalacOptions += "-Xplugin-require:macroparadise-embedded-producer",
       |    manualRoleInventory := {
       |      (Compile / compile).value
       |      EmbeddedProducerRoles.packageRoles(
       |        (Compile / classDirectory).value,
       |        target.value / ("manual-marker_" + scalaVersion.value + ".jar"),
       |        target.value / ("manual-handler_" + scalaVersion.value + ".jar"),
       |        strict = true
       |      )
       |    },
       |    manualMarkerArtifact := { manualRoleInventory.value; target.value / ("manual-marker_" + scalaVersion.value + ".jar") },
       |    manualHandlerArtifact := { manualRoleInventory.value; target.value / ("manual-handler_" + scalaVersion.value + ".jar") },
       |    manualHandlerClasspath := {
       |      val excluded = (Compile / dependencyClasspath).value.files.filter { file =>
       |        val name = file.getName
       |        name.contains("embedded-producer-plugin") ||
       |          name.contains("scala3-compiler") || name.contains("scala3-interfaces") ||
       |          name.contains("tasty-core") || name.contains("scala-asm") ||
       |          name.contains("compiler-interface") || name.contains("util-interface")
       |      }
       |      EmbeddedProducerRoles.completeHandlerClasspath(
       |        manualHandlerArtifact.value,
       |        (Compile / classDirectory).value,
       |        (Runtime / dependencyClasspath).value.files,
       |        excluded :+ manualMarkerArtifact.value,
       |        target.value / "manual-handler-runtime"
       |      )
       |    },
       |    ManualEmbeddedMarker / products := Seq(manualMarkerArtifact.value),
       |    ManualEmbeddedMarker / exportedProducts := Seq(Attributed.blank(manualMarkerArtifact.value))
       |  )
       |
       |lazy val coreLocalSbt = project.in(file("consumer-sbt"))
       |  .dependsOn(producer % "provided->macroParadiseEmbeddedMarker")
       |  .enablePlugins(MacroParadisePrecompiledPlugin)
       |  .settings(MacroParadiseIntegration.precompiledEmbeddedProject(producer))
       |  .settings(
       |    macroParadiseCompilerProductVersion := mpVersion,
       |    verifyQuadrant := {
       |      (producer / macroParadiseEmbeddedValidate).value
       |      (Compile / compile).value
       |      val marker = (producer / macroParadiseEmbeddedMarkerArtifact).value.getCanonicalFile
       |      val handler = (producer / macroParadiseEmbeddedHandlerArtifact).value.getCanonicalFile
       |      val closure = (producer / macroParadiseEmbeddedHandlerClasspath).value.map(_.getCanonicalFile)
       |      val compileCp = (Compile / fullClasspath).value.files.map(_.getCanonicalFile)
       |      val runtimeCp = (Runtime / fullClasspath).value.files.map(_.getCanonicalFile)
       |      require(compileCp.contains(marker), "derived marker is absent from consumer compile classpath")
       |      require(closure.headOption.contains(handler), "derived handler is not first in the handler closure")
       |      require(!runtimeCp.contains(handler), "derived handler leaked onto application runtime")
       |      require(!runtimeCp.exists(_.getName.contains("sample-transform-runtime")), "transform runtime leaked onto application runtime")
       |      require(!runtimeCp.exists(_.getName.contains("scala3-compiler")), "Scala compiler leaked onto application runtime")
       |      require(!runtimeCp.exists(_.getName.contains("embedded-producer-plugin")), "producer compiler plugin leaked onto application runtime")
       |      (Compile / runner).value.run("consumer.Main", (Runtime / fullClasspath).value.files, Seq.empty, streams.value.log).get
       |      streams.value.log.info("EMBEDDED_SAME_BUILD_SBT_PASS identity=" + macroParadiseExternalArtifactIdentity.value)
       |    }
       |  )
       |
       |lazy val coreLocalManual = project.in(file("consumer-manual"))
       |  .dependsOn(manualProducer % "provided->manualEmbeddedMarker")
       |  .settings(
       |    libraryDependencies += compilerPlugin(mpConsumer),
       |    Compile / scalacOptions ++= Def.task {
       |      val marker = (manualProducer / manualMarkerArtifact).value
       |      val handlers = (manualProducer / manualHandlerClasspath).value
       |      val identity = ExternalArtifactIdentity.combined(
       |        Seq("manual-embedded-marker" -> marker),
       |        handlers.zipWithIndex.map { case (file, index) => f"manual-handler-$$index%04d" -> file }
       |      )
       |      Seq(
       |        "-Xplugin-require:macroparadise",
       |        "-P:macroparadise:handlerClasspath=" + handlers.map(_.getAbsolutePath).mkString(File.pathSeparator),
       |        "-P:macroparadise:externalArtifactIdentity=sha256:" + identity
       |      )
       |    }.value,
       |    verifyManualQuadrant := {
       |      (Compile / compile).value
       |      val marker = (manualProducer / manualMarkerArtifact).value.getCanonicalFile
       |      val handler = (manualProducer / manualHandlerArtifact).value.getCanonicalFile
       |      val handlers = (manualProducer / manualHandlerClasspath).value.map(_.getCanonicalFile)
       |      val compileCp = (Compile / fullClasspath).value.files.map(_.getCanonicalFile)
       |      val runtimeCp = (Runtime / fullClasspath).value.files.map(_.getCanonicalFile)
       |      require(compileCp.contains(marker), "manual marker is absent from compile classpath")
       |      require(handlers.headOption.contains(handler), "manual handler is not first")
       |      require(!runtimeCp.contains(handler), "manual handler leaked onto runtime")
       |      require(!runtimeCp.exists(_.getName.contains("sample-transform-runtime")), "manual transform runtime leaked onto runtime")
       |      require(!runtimeCp.exists(_.getName.contains("scala3-compiler")), "manual Scala compiler leaked onto runtime")
       |      (Compile / runner).value.run("consumer.Main", (Runtime / fullClasspath).value.files, Seq.empty, streams.value.log).get
       |      streams.value.log.info("EMBEDDED_SAME_BUILD_MANUAL_PASS")
       |    }
       |  )
       |
       |lazy val markerFacade = project.in(file("facades/marker"))
       |  .settings(MacroParadiseIntegration.embeddedMarkerPublicationFacade(producer))
       |  .settings(publish / skip := false)
       |
       |lazy val handlerFacade = project.in(file("facades/handler"))
       |  .dependsOn(transformRuntime)
       |  .settings(MacroParadiseIntegration.embeddedHandlerPublicationFacade(producer))
       |  .settings(publish / skip := false)
       |
       |def sha256(file: File): String =
       |  MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath))
       |    .map(value => f"$${value & 0xff}%02x").mkString
       |
       |def recordState(slot: String) = Def.task {
       |  (coreLocalSbt / Compile / compile).value
       |  val marker = (producer / macroParadiseEmbeddedMarkerArtifact).value.getCanonicalFile
       |  val handler = (producer / macroParadiseEmbeddedHandlerArtifact).value.getCanonicalFile
       |  val closure = (producer / macroParadiseEmbeddedHandlerClasspath).value.map(_.getCanonicalFile)
       |  val identity = (coreLocalSbt / macroParadiseExternalArtifactIdentity).value
       |  val runtimeCp = (coreLocalSbt / Runtime / fullClasspath).value.files.map(_.getCanonicalFile)
       |  val consumerClass = (coreLocalSbt / Compile / classDirectory).value / "consumer" / "Target.class"
       |  val dependency = closure.tail.find(_.getName.startsWith("runtime-")).getOrElse(sys.error("materialized transform runtime is absent from handler closure"))
       |  val runtimeIsolated =
       |    !runtimeCp.contains(handler) && !runtimeCp.contains(dependency) &&
       |      !runtimeCp.exists(_.getName.contains("scala3-compiler")) &&
       |      !runtimeCp.exists(_.getName.contains("embedded-producer-plugin"))
       |  IO.write(
       |    file("${scalaString(evidence.getAbsolutePath)}/" + slot + ".state"),
       |    "markerSha256=" + sha256(marker) + "\\n" +
       |      "handlerSha256=" + sha256(handler) + "\\n" +
       |      "dependencySha256=" + sha256(dependency) + "\\n" +
       |      "identity=" + identity + "\\n" +
       |      "consumerSha256=" + sha256(consumerClass) + "\\n" +
       |      "consumerMtime=" + consumerClass.lastModified + "\\n" +
       |      "handlerClasspath=" + closure.map(_.getAbsolutePath).mkString(File.pathSeparator) + "\\n" +
       |      "runtimeIsolated=" + runtimeIsolated + "\\n",
       |    StandardCharsets.UTF_8
       |  )
       |}
       |
       |lazy val root = project.in(file("."))
       |  .aggregate(transformRuntime, producer, manualProducer, coreLocalSbt, coreLocalManual, markerFacade, handlerFacade)
       |  .settings(
       |    recordBaseline := recordState("baseline").value,
       |    recordNoOp := recordState("noop").value,
       |    recordTransformEdited := recordState("transform-edited").value,
       |    recordTransformSecondNoOp := recordState("transform-second-noop").value,
       |    recordTransformRestored := recordState("transform-restored").value,
       |    recordDependencyBaseline := recordState("dependency-baseline").value,
       |    recordDependencyEdited := recordState("dependency-edited").value,
       |    recordDependencyRestored := recordState("dependency-restored").value,
       |    editTransform := {
       |      val source = file("producer/src/main/scala/embedded/Generated.scala")
       |      val before = IO.read(source)
       |      require(before.contains("RuntimeToken.value // P219_TRANSFORM_BODY"), "baseline transform token is absent")
       |      IO.write(source, before.replace("RuntimeToken.value // P219_TRANSFORM_BODY", "\\\"runtime-v2\\\" // P219_TRANSFORM_BODY"))
       |    },
       |    restoreTransform := {
       |      val source = file("producer/src/main/scala/embedded/Generated.scala")
       |      val before = IO.read(source)
       |      require(before.contains("\\\"runtime-v2\\\" // P219_TRANSFORM_BODY"), "edited transform token is absent")
       |      IO.write(source, before.replace("\\\"runtime-v2\\\" // P219_TRANSFORM_BODY", "RuntimeToken.value // P219_TRANSFORM_BODY"))
       |    },
       |    editRuntimeDependency := {
       |      val source = file("transform-runtime/src/main/scala/support/RuntimeToken.scala")
       |      val before = IO.read(source)
       |      require(before.contains("\\\"runtime-v1\\\""), "baseline runtime token is absent")
       |      IO.write(source, before.replace("\\\"runtime-v1\\\"", "\\\"dependency-v2\\\""))
       |    },
       |    restoreRuntimeDependency := {
       |      val source = file("transform-runtime/src/main/scala/support/RuntimeToken.scala")
       |      val before = IO.read(source)
       |      require(before.contains("\\\"dependency-v2\\\""), "edited runtime token is absent")
       |      IO.write(source, before.replace("\\\"dependency-v2\\\"", "\\\"runtime-v1\\\""))
       |    },
       |    publishEmbeddedRoles := {
       |      (transformRuntime / publish).value
       |      (markerFacade / publish).value
       |      (handlerFacade / publish).value
       |      streams.value.log.info("EMBEDDED_ROLE_PUBLICATION_PASS")
       |    }
       |  )
       |""".stripMargin

  private def createPublishedBuild(
      repositoryRoot: File,
      productRepository: File,
      roleRepository: File,
      build: File,
      config: Config
  ): Unit = {
    Vector("project", "consumer-sbt/src/main/scala/consumer", "consumer-manual/src/main/scala/consumer")
      .foreach(path => sbt.IO.createDirectory(new File(build, path)))
    write(new File(build, "project/build.properties"), "sbt.version=" + config.sbtVersion + "\n")
    writeRepositories(new File(build, "repositories"), Vector(productRepository, roleRepository))
    Vector("ArtifactIdentity.scala", "EmbeddedProducerRoles.scala", "MacroParadisePrecompiledPlugin.scala", "MacroParadiseEmbeddedProducerPlugin.scala").foreach { source =>
      Files.copy(
        new File(repositoryRoot, "sbt-integration/src/main/scala/macroparadise/sbt/" + source).toPath,
        new File(build, "project/" + source).toPath,
        StandardCopyOption.REPLACE_EXISTING
      )
    }
    Files.copy(
      new File(repositoryRoot, "examples/external-handler-starter/project/ExternalArtifactIdentity.scala").toPath,
      new File(build, "project/ExternalArtifactIdentity.scala").toPath,
      StandardCopyOption.REPLACE_EXISTING
    )
    write(new File(build, "consumer-sbt/src/main/scala/consumer/Main.scala"), consumerSource(named = true))
    write(new File(build, "consumer-manual/src/main/scala/consumer/Main.scala"), consumerSource(named = false))
    write(new File(build, "build.sbt"), publishedBuildText(productRepository, roleRepository, config))
  }

  private def publishedBuildText(
      productRepository: File,
      roleRepository: File,
      config: Config
  ): String =
    s"""import java.io.File
       |import macroparadise.sbt.{MacroParadiseIntegration, MacroParadisePrecompiledPlugin}
       |import MacroParadisePrecompiledPlugin.autoImport._
       |
       |ThisBuild / scalaVersion := "${config.scalaVersion}"
       |ThisBuild / version := "$FixtureVersion"
       |ThisBuild / resolvers := Seq(
       |  "task-product-repository" at "${scalaString(productRepository.toURI.toString)}",
       |  "task-role-repository" at "${scalaString(roleRepository.toURI.toString)}",
       |  Resolver.mavenCentral
       |)
       |ThisBuild / credentials := Nil
       |ThisBuild / publish / skip := true
       |
       |val mpVersion = "${config.productVersion}"
       |val mpApi = ("$ProductOrganization" % "macroparadise-scala3-plugin-api" % mpVersion).cross(CrossVersion.full)
       |val mpConsumer = ("$ProductOrganization" % "macroparadise-scala3-plugin" % mpVersion).cross(CrossVersion.full)
       |val embeddedModules =
       |  MacroParadiseIntegration.embeddedModuleIds("$FixtureOrganization", "$FixtureBaseModule", "$FixtureVersion")
       |val markerModule = embeddedModules._1
       |val handlerModule = embeddedModules._2
       |lazy val ManualHandler = config("manualEmbeddedHandler").hide
       |lazy val manualHandlerClasspath = taskKey[Seq[File]]("Resolved manual handler closure")
       |lazy val verifyQuadrant = taskKey[Unit]("Verify published sbt quadrant")
       |lazy val verifyManualQuadrant = taskKey[Unit]("Verify published manual quadrant")
       |
       |lazy val corePublishedSbt = project.in(file("consumer-sbt"))
       |  .enablePlugins(MacroParadisePrecompiledPlugin)
       |  .settings(MacroParadiseIntegration.precompiledEmbeddedModules("$FixtureOrganization", "$FixtureBaseModule", "$FixtureVersion"))
       |  .settings(
       |    macroParadiseCompilerProductVersion := mpVersion,
       |    verifyQuadrant := {
       |      macroParadiseValidate.value
       |      (Compile / compile).value
       |      val marker = macroParadiseMarkerArtifacts.value.head.file.getCanonicalFile
       |      val handlers = macroParadiseHandlerClasspath.value.map(_.file.getCanonicalFile)
       |      val runtimeCp = (Runtime / fullClasspath).value.files.map(_.getCanonicalFile)
       |      require(handlers.head.getName.contains("macro-handlers"), "published handler module is not first")
       |      require(!runtimeCp.exists(_.getName.contains("macro-handlers")), "published handler leaked onto runtime")
       |      require(!runtimeCp.exists(_.getName.contains("sample-transform-runtime")), "published transform dependency leaked onto runtime")
       |      require(!runtimeCp.exists(_.getName.contains("scala3-compiler")), "published Scala compiler leaked onto runtime")
       |      require(!runtimeCp.exists(_.getName.contains("embedded-producer-plugin")), "published producer plugin leaked onto runtime")
       |      require((Compile / fullClasspath).value.files.map(_.getCanonicalFile).contains(marker), "published marker missing from compile classpath")
       |      (Compile / runner).value.run("consumer.Main", (Runtime / fullClasspath).value.files, Seq.empty, streams.value.log).get
       |      streams.value.log.info("EMBEDDED_PUBLISHED_SBT_PASS identity=" + macroParadiseExternalArtifactIdentity.value)
       |    }
       |  )
       |
       |lazy val corePublishedManual = project.in(file("consumer-manual"))
       |  .configs(ManualHandler)
       |  .settings(inConfig(ManualHandler)(Defaults.configSettings))
       |  .settings(
       |    libraryDependencies ++= Seq(compilerPlugin(mpConsumer), markerModule, handlerModule % ManualHandler.name),
       |    manualHandlerClasspath := {
       |      val cp = (ManualHandler / dependencyClasspath).value
       |      val direct = cp.filter(_.get(moduleID.key).exists { module =>
       |        module.organization == handlerModule.organization &&
       |          (module.name == handlerModule.name || module.name.startsWith(handlerModule.name + "_")) &&
       |          module.revision == handlerModule.revision
       |      }).map(_.data.getCanonicalFile)
       |      val directSet = direct.toSet
       |      direct ++ cp.map(_.data.getCanonicalFile).filterNot(directSet)
       |    },
       |    Compile / scalacOptions ++= Def.task {
       |      val marker = (Compile / dependencyClasspath).value.find(_.get(moduleID.key).exists { module =>
       |        module.organization == markerModule.organization &&
       |          (module.name == markerModule.name || module.name.startsWith(markerModule.name + "_")) &&
       |          module.revision == markerModule.revision
       |      }).map(_.data.getCanonicalFile).toSeq
       |      val handlers = manualHandlerClasspath.value
       |      val identity = ExternalArtifactIdentity.combined(
       |        marker.zipWithIndex.map { case (file, index) => f"published-marker-$$index%04d" -> file },
       |        handlers.zipWithIndex.map { case (file, index) => f"published-handler-$$index%04d" -> file }
       |      )
       |      Seq(
       |        "-Xplugin-require:macroparadise",
       |        "-P:macroparadise:handlerClasspath=" + handlers.map(_.getAbsolutePath).mkString(File.pathSeparator),
       |        "-P:macroparadise:externalArtifactIdentity=sha256:" + identity
       |      )
       |    }.value,
       |    verifyManualQuadrant := {
       |      (Compile / compile).value
       |      val handlers = manualHandlerClasspath.value
       |      val runtimeCp = (Runtime / fullClasspath).value.files.map(_.getCanonicalFile)
       |      require(handlers.head.getName.contains("macro-handlers"), "manual published handler is not first")
       |      require(!runtimeCp.exists(_.getName.contains("macro-handlers")), "manual published handler leaked onto runtime")
       |      require(!runtimeCp.exists(_.getName.contains("sample-transform-runtime")), "manual published transform dependency leaked onto runtime")
       |      require(!runtimeCp.exists(_.getName.contains("scala3-compiler")), "manual published Scala compiler leaked onto runtime")
       |      require(!runtimeCp.exists(_.getName.contains("embedded-producer-plugin")), "manual published producer plugin leaked onto runtime")
       |      (Compile / runner).value.run("consumer.Main", (Runtime / fullClasspath).value.files, Seq.empty, streams.value.log).get
       |      streams.value.log.info("EMBEDDED_PUBLISHED_MANUAL_PASS")
       |    }
       |  )
       |""".stripMargin

  private val producerSource =
    """package embedded
      |
      |import dotty.tools.dotc.ast.untpd
      |import dotty.tools.dotc.core.Constants.Constant
      |import dotty.tools.dotc.core.Contexts.Context
      |import dotty.tools.dotc.core.Names.{termName, typeName}
      |import paradise3.api.*
      |import paradise3.api.embeddedExpander
      |import paradise3.api.helpers.ExpansionHelpers
      |import scala.annotation.StaticAnnotation
      |import support.RuntimeToken
      |
      |@embeddedExpander
      |final class generated(prefix: String = "ignored") extends StaticAnnotation
      |
      |object generated:
      |  private def generatedText: String =
      |    RuntimeToken.value // P219_TRANSFORM_BODY
      |
      |  def transform(input: ExpansionInput)(using Context): ExpansionOutcome =
      |    ExpansionEdit.finish:
      |      ExpansionEdit.start(input).flatMap: edit =>
      |        ExpansionHelpers.placeMemberInPrimary(
      |          edit,
      |          untpd.DefDef(
      |            termName("generatedValue"),
      |            Nil,
      |            untpd.Ident(typeName("String")),
      |            untpd.Literal(Constant(generatedText))
      |          )
      |        )
      |""".stripMargin

  private def consumerSource(named: Boolean): String = {
    val annotation = if (named) "@generated(prefix = \"ignored\")" else "@generated(\"ignored\")"
    s"""package consumer
       |
       |import embedded.generated
       |
       |$annotation
       |final class Target
       |
       |object Main:
       |  def main(args: Array[String]): Unit =
       |    val value = new Target().generatedValue
       |    println("EMBEDDED_RUNTIME:" + value)
       |""".stripMargin
  }

  private def writeRepositories(file: File, repositories: Vector[File]): Unit = {
    val records = repositories.zipWithIndex.map { case (repository, index) =>
      "task-" + index + ": " + repository.toURI.toString
    }
    write(file, ("[repositories]" +: records :+ "maven-central").mkString("\n") + "\n")
  }

  private def runSbt(build: File, commands: Vector[String], logFile: File): Int = {
    val output = new StringBuilder
    val repositories = new File(build, "repositories").getAbsolutePath
    val exit = Process(
      Vector("sbt", "-Dsbt.override.build.repos=true", "-Dsbt.repository.config=" + repositories, "-batch") ++ commands,
      build
    ).!(ProcessLogger(line => output.append(line).append('\n'), line => output.append(line).append('\n')))
    write(logFile, output.result())
    exit
  }

  private def readState(file: File): Map[String, String] =
    read(file).linesIterator.filter(_.contains("=")).map { line =>
      val index = line.indexOf('=')
      line.substring(0, index) -> line.substring(index + 1)
    }.toMap

  private def sha256(file: File): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    val input = Files.newInputStream(file.toPath)
    try {
      val buffer = new Array[Byte](8192)
      var count = input.read(buffer)
      while (count >= 0) {
        if (count > 0) digest.update(buffer, 0, count)
        count = input.read(buffer)
      }
    } finally input.close()
    digest.digest().map(value => f"${value & 0xff}%02x").mkString
  }

  private def scalaString(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")

  private def read(file: File): String =
    new String(Files.readAllBytes(file.toPath), StandardCharsets.UTF_8)

  private def write(file: File, value: String): Unit = {
    Option(file.getParentFile).foreach(sbt.IO.createDirectory)
    Files.write(file.toPath, value.getBytes(StandardCharsets.UTF_8))
  }
}
