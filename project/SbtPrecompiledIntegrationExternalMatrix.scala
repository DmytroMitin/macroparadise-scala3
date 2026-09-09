import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, StandardCopyOption}
import java.security.MessageDigest

import scala.collection.mutable.ArrayBuffer
import scala.sys.process.{Process, ProcessLogger}

object SbtPrecompiledIntegrationExternalMatrix {
  final case class Config(scalaVersion: String, sbtVersion: String, projectVersion: String)

  final case class MultiLocalSnapshot(
      markerA: String,
      markerB: String,
      handlerA: String,
      handlerB: String,
      sharedRuntime: String,
      identity: String,
      consumerMtime: Long
  )

  def changedOnly(before: MultiLocalSnapshot, after: MultiLocalSnapshot, expected: String): Vector[String] = {
    val inputs = Vector(
      "markerA" -> (before.markerA != after.markerA),
      "markerB" -> (before.markerB != after.markerB),
      "handlerA" -> (before.handlerA != after.handlerA),
      "handlerB" -> (before.handlerB != after.handlerB),
      "sharedRuntime" -> (before.sharedRuntime != after.sharedRuntime)
    )
    val errors = Vector.newBuilder[String]
    inputs.foreach { case (name, changed) =>
      if (name == expected && !changed) errors += s"expected input did not change: $name"
      else if (name != expected && changed) errors += s"unexpected input changed: $name"
    }
    if (before.identity == after.identity) errors += "external identity did not change"
    if (after.consumerMtime <= before.consumerMtime) errors += "consumer did not recompile"
    errors.result()
  }

  final case class Transition(
      baselineValue: String,
      editedValue: String,
      markerBefore: String,
      markerAfter: String,
      handlerBefore: String,
      handlerAfter: String,
      dependencyBefore: String,
      dependencyAfter: String,
      supportedIdentityBefore: String,
      supportedIdentityAfter: String,
      oldPrimaryOnlyBefore: String,
      oldPrimaryOnlyAfter: String,
      consumerBefore: String,
      consumerAfter: String,
      consumerNoOp: String,
      noOpMtimeStable: Boolean
  )

  final case class VerificationResult(
      scalaVersion: String,
      transition: Transition,
      handlerClasspathEntries: Int,
      handlerPrimaryFirst: Boolean,
      dependencyPresent: Boolean,
      handlerRuntimeIsolated: Boolean,
      handlerOnlyInvalidation: Boolean,
      markerOnlyInvalidation: Boolean,
      staleFailureRepair: Boolean,
      missingDependencyNegative: Boolean,
      missingRoleNegatives: Boolean,
      wrongCoordinateNegative: Boolean,
      showInspectEvidence: Boolean,
      evidenceDirectory: File
  ) {
    def render: String =
      s"scala=$scalaVersion dependencyOnlyInvalidation=PASS baseline=${transition.baselineValue} " +
        s"edited=${transition.editedValue} identityChanged=${transition.supportedIdentityBefore != transition.supportedIdentityAfter} " +
        s"oldPrimaryOnlyStable=${transition.oldPrimaryOnlyBefore == transition.oldPrimaryOnlyAfter} " +
        s"handlerClasspathEntries=$handlerClasspathEntries handlerPrimaryFirst=$handlerPrimaryFirst " +
        s"dependencyPresent=$dependencyPresent handlerRuntimeIsolated=$handlerRuntimeIsolated " +
        s"handlerOnlyInvalidation=$handlerOnlyInvalidation markerOnlyInvalidation=$markerOnlyInvalidation " +
        s"staleFailureRepair=$staleFailureRepair missingDependencyNegative=$missingDependencyNegative " +
        s"missingRoleNegatives=$missingRoleNegatives wrongCoordinateNegative=$wrongCoordinateNegative " +
        s"showInspectEvidence=$showInspectEvidence " +
        s"noOpStable=${transition.noOpMtimeStable}"
  }

  final case class MultiLocalVerificationResult(
      scalaVersion: String,
      markerCount: Int,
      handlerCount: Int,
      sharedRuntimeCount: Int,
      directPrimariesFirst: Boolean,
      runtimeIsolated: Boolean,
      incrementalInputs: Vector[String],
      missingRoleNegatives: Boolean,
      roleCollisionNegative: Boolean,
      missingDependencyNegative: Boolean,
      missingHandlerNegative: Boolean,
      noOpStable: Boolean,
      evidenceDirectory: File
  ) {
    def render: String =
      s"scala=$scalaVersion multiLocalMarkers=$markerCount multiLocalHandlers=$handlerCount " +
        s"sharedRuntimeCount=$sharedRuntimeCount directPrimariesFirst=$directPrimariesFirst " +
        s"runtimeIsolated=$runtimeIsolated incrementalInputs=${incrementalInputs.mkString(",")} " +
        s"missingRoleNegatives=$missingRoleNegatives roleCollisionNegative=$roleCollisionNegative " +
        s"missingDependencyNegative=$missingDependencyNegative missingHandlerNegative=$missingHandlerNegative " +
        s"noOpStable=$noOpStable"
  }

  def validateTransition(value: Transition): Vector[String] = {
    val errors = Vector.newBuilder[String]
    if (value.baselineValue != "dependency-v1") errors += "baseline value mismatch"
    if (value.editedValue != "dependency-v2") errors += "edited value mismatch"
    if (value.markerBefore != value.markerAfter) errors += "marker bytes changed"
    if (value.handlerBefore != value.handlerAfter) errors += "primary handler bytes changed"
    if (value.dependencyBefore == value.dependencyAfter) errors += "dependency bytes did not change"
    if (value.supportedIdentityBefore == value.supportedIdentityAfter) errors += "supported identity did not change"
    if (value.oldPrimaryOnlyBefore != value.oldPrimaryOnlyAfter) errors += "old primary-only control changed"
    if (value.consumerBefore == value.consumerAfter) errors += "consumer output did not regenerate"
    if (value.consumerAfter != value.consumerNoOp || !value.noOpMtimeStable)
      errors += "no-op consumer output churned"
    errors.result()
  }

  def verify(
      repositoryRoot: File,
      pluginApiJar: File,
      pluginJar: File,
      pluginApiPom: File,
      pluginPom: File,
      taskRoot: File,
      config: Config
  ): VerificationResult = {
    require(Set("3.3.8", "3.8.4", "3.9.0")(config.scalaVersion), "unsupported exact Scala line")
    require(config.sbtVersion == "1.12.15", "unsupported sbt version")
    require(config.projectVersion == "0.2.0-SNAPSHOT", "unexpected product version")
    sbt.IO.delete(taskRoot)
    val evidence = new File(taskRoot, "evidence")
    val repository = new File(taskRoot, "repository")
    val build = new File(taskRoot, "build")
    sbt.IO.createDirectory(evidence)
    stageProduct(repository, pluginApiJar, pluginApiPom, "macroparadise-scala3-plugin-api", config)
    stageProduct(repository, pluginJar, pluginPom, "macroparadise-scala3-plugin", config)
    createBuild(repositoryRoot, repository, build, evidence, config)

    val baselineLog = new File(evidence, "baseline.log")
    require(
      runSbt(
        build,
        config,
        "baseline",
        Vector(
          "clean",
          "show core/macroParadiseCompilerPluginModule",
          "inspect core/macroParadiseHandlerClasspath",
          "core/run",
          "recordState"
        ),
        baselineLog
      ) == 0,
      "baseline external build failed"
    )
    val baseline = readState(new File(evidence, "baseline.state"))
    val baselineValue = runtimeValue(baselineLog)

    val dependencySource = new File(build, "handler-runtime/src/main/scala/DependencyValue.scala")
    val originalDependencySource = read(dependencySource)
    require(originalDependencySource.contains("dependency-v1"), "dependency fixture baseline is missing")
    write(dependencySource, originalDependencySource.replace("dependency-v1", "dependency-v2"))

    val editedLog = new File(evidence, "edited.log")
    require(runSbt(build, config, "edited", Vector("core/run", "recordState"), editedLog) == 0, "dependency-only external rebuild failed")
    val edited = readState(new File(evidence, "edited.state"))
    val editedValue = runtimeValue(editedLog)

    val noOpLog = new File(evidence, "noop.log")
    require(runSbt(build, config, "noop", Vector("core/run", "recordState"), noOpLog) == 0, "no-op external rebuild failed")
    val noOp = readState(new File(evidence, "noop.state"))

    val transition = Transition(
      baselineValue,
      editedValue,
      baseline("markerSha256"),
      edited("markerSha256"),
      baseline("handlerSha256"),
      edited("handlerSha256"),
      baseline("dependencySha256"),
      edited("dependencySha256"),
      baseline("identity"),
      edited("identity"),
      baseline("oldPrimaryOnlyIdentity"),
      edited("oldPrimaryOnlyIdentity"),
      baseline("consumerSha256"),
      edited("consumerSha256"),
      noOp("consumerSha256"),
      edited("consumerMtime") == noOp("consumerMtime")
    )
    val errors = validateTransition(transition)
    require(errors.isEmpty, "dependency-only transition failed: " + errors.mkString("; "))
    require(noOp("identity") == edited("identity"), "no-op identity changed")
    val handlerEntries = edited("handlerClasspath").split(File.pathSeparator).toVector.filter(_.nonEmpty)
    val handlerPrimaryFirst = handlerEntries.headOption.exists(_.contains("external-handler"))
    val dependencyPresent = handlerEntries.exists(_.contains("external-handler-runtime"))
    val handlerRuntimeIsolated = edited("runtimeIsolated") == "true"
    require(handlerPrimaryFirst, "primary handler is not first in the loader path")
    require(dependencyPresent, "handler runtime dependency is absent from the loader path")
    require(handlerRuntimeIsolated, "handler or dependency leaked onto consumer runtime classpath")

    val handlerSourceFile = new File(build, "handler/src/main/scala/GeneratedHandler.scala")
    val initialHandlerSource = read(handlerSourceFile)
    write(
      handlerSourceFile,
      initialHandlerSource.replace(
        "HandlerSupport.expand(input, \"generatedValue\", DependencyValue.current)",
        "HandlerSupport.expand(input, \"generatedValue\", \"handler-v2:\" + DependencyValue.current)"
      )
    )
    val handlerLog = new File(evidence, "handler-edited.log")
    require(runSbt(build, config, "handler-edited", Vector("core/run", "recordState"), handlerLog) == 0, "handler-only rebuild failed")
    val handlerEdited = readState(new File(evidence, "handler-edited.state"))
    require(runtimeValue(handlerLog) == "handler-v2:dependency-v2", "handler-only output mismatch")
    require(handlerEdited("markerSha256") == edited("markerSha256"), "handler-only edit changed marker bytes")
    require(handlerEdited("dependencySha256") == edited("dependencySha256"), "handler-only edit changed dependency bytes")
    require(handlerEdited("handlerSha256") != edited("handlerSha256"), "handler-only edit did not change handler bytes")
    require(handlerEdited("identity") != edited("identity"), "handler-only edit did not change identity")
    require(handlerEdited("consumerSha256") != edited("consumerSha256"), "handler-only edit did not regenerate consumer")

    val markerSourceFile = new File(build, "marker/src/main/scala/generated.scala")
    write(markerSourceFile, markerSource.replace("GeneratedHandler", "AlternateHandler"))
    val markerLog = new File(evidence, "marker-edited.log")
    require(runSbt(build, config, "marker-edited", Vector("core/run", "recordState"), markerLog) == 0, "marker-only rebuild failed")
    val markerEdited = readState(new File(evidence, "marker-edited.state"))
    require(runtimeValue(markerLog) == "marker-v2:dependency-v2", "marker-only output mismatch")
    require(markerEdited("markerSha256") != handlerEdited("markerSha256"), "marker-only edit did not change marker bytes")
    require(markerEdited("handlerSha256") == handlerEdited("handlerSha256"), "marker-only edit changed handler bytes")
    require(markerEdited("dependencySha256") == handlerEdited("dependencySha256"), "marker-only edit changed dependency bytes")
    require(markerEdited("identity") != handlerEdited("identity"), "marker-only edit did not change identity")
    require(markerEdited("consumerSha256") != handlerEdited("consumerSha256"), "marker-only edit did not regenerate consumer")

    write(markerSourceFile, markerSource.replace("GeneratedHandler", "MissingHandler"))
    val staleLog = new File(evidence, "stale-handler.log")
    require(runSbt(build, config, "stale-handler", Vector("core/compile"), staleLog) != 0, "stale handler unexpectedly compiled")
    write(markerSourceFile, markerSource.replace("GeneratedHandler", "AlternateHandler"))
    val repairedLog = new File(evidence, "repaired.log")
    require(runSbt(build, config, "repaired", Vector("core/run", "recordState"), repairedLog) == 0, "stale handler repair failed")
    require(runtimeValue(repairedLog) == "marker-v2:dependency-v2", "repaired output mismatch")

    val missingDependencyLog = new File(evidence, "missing-dependency.log")
    require(
      runSbt(
        build,
        config,
        "missing-dependency",
        Vector(
          "set core / macroParadiseHandlerClasspath := Seq(macroParadiseLabelled(\"handler-only\", (handler / Compile / packageBin).value))",
          "core/compile"
        ),
        missingDependencyLog
      ) != 0,
      "missing handler dependency unexpectedly compiled"
    )
    val dependencyRepairLog = new File(evidence, "dependency-repaired.log")
    require(runSbt(build, config, "dependency-repaired", Vector("core/run"), dependencyRepairLog) == 0, "dependency repair failed")
    require(runtimeValue(dependencyRepairLog) == "marker-v2:dependency-v2", "dependency repair output mismatch")

    val missingMarkerLog = new File(evidence, "missing-marker-role.log")
    val missingMarkerFailed = runSbt(
      build,
      config,
      "missing-marker-role",
      Vector("set core / macroParadiseMarkerArtifacts := Seq.empty", "core/macroParadiseValidate"),
      missingMarkerLog
    ) != 0
    val missingHandlerLog = new File(evidence, "missing-handler-role.log")
    val missingHandlerFailed = runSbt(
      build,
      config,
      "missing-handler-role",
      Vector("set core / macroParadiseHandlerClasspath := Seq.empty", "core/macroParadiseValidate"),
      missingHandlerLog
    ) != 0
    require(missingMarkerFailed && missingHandlerFailed, "missing role validation unexpectedly passed")

    val wrongCoordinateLog = new File(evidence, "wrong-coordinate.log")
    require(
      runSbt(
        build,
        config,
        "wrong-coordinate",
        Vector(
          "set core / macroParadiseCompilerPluginModule := (\"com.github.dmytromitin\" % \"macroparadise-scala3-plugin\" % \"9.9.9\").cross(CrossVersion.full)",
          "core/macroParadiseValidate"
        ),
        wrongCoordinateLog
      ) != 0,
      "wrong exact compiler-plugin coordinate unexpectedly passed"
    )
    val showInspectEvidence = {
      val text = read(baselineLog)
      text.contains("com.github.dmytromitin:macroparadise-scala3-plugin:" + config.projectVersion) &&
      text.contains("macroParadiseHandlerClasspath")
    }
    require(showInspectEvidence, "show/inspect evidence is missing")

    val result = VerificationResult(
      config.scalaVersion,
      transition,
      handlerEntries.size,
      handlerPrimaryFirst,
      dependencyPresent,
      handlerRuntimeIsolated,
      handlerOnlyInvalidation = true,
      markerOnlyInvalidation = true,
      staleFailureRepair = true,
      missingDependencyNegative = true,
      missingRoleNegatives = true,
      wrongCoordinateNegative = true,
      showInspectEvidence,
      evidence
    )
    write(new File(evidence, "summary.txt"), result.render + "\n")
    result
  }

  def verifyMultiLocal(
      repositoryRoot: File,
      pluginApiJar: File,
      pluginJar: File,
      pluginApiPom: File,
      pluginPom: File,
      taskRoot: File,
      config: Config
  ): MultiLocalVerificationResult = {
    require(Set("3.3.8", "3.8.4", "3.9.0")(config.scalaVersion), "unsupported exact Scala line")
    require(config.sbtVersion == "1.12.15", "unsupported sbt version")
    require(config.projectVersion == "0.2.0-SNAPSHOT", "unexpected product version")
    sbt.IO.delete(taskRoot)
    val evidence = new File(taskRoot, "evidence")
    val repository = new File(taskRoot, "repository")
    val build = new File(taskRoot, "build")
    sbt.IO.createDirectory(evidence)
    stageProduct(repository, pluginApiJar, pluginApiPom, "macroparadise-scala3-plugin-api", config)
    stageProduct(repository, pluginJar, pluginPom, "macroparadise-scala3-plugin", config)
    createMultiLocalBuild(repositoryRoot, repository, build, evidence, config)

    def execute(slot: String, commands: Vector[String], description: String): (MultiLocalSnapshot, File) = {
      val log = new File(evidence, slot + ".log")
      require(runSbt(build, config, slot, commands :+ "recordMultiLocalState", log) == 0, description)
      val state = readState(new File(evidence, slot + ".state"))
      MultiLocalSnapshot(
        state("markerASha256"),
        state("markerBSha256"),
        state("handlerASha256"),
        state("handlerBSha256"),
        state("sharedRuntimeSha256"),
        state("identity"),
        state("consumerMtime").toLong
      ) -> log
    }

    val (baseline, baselineLog) = execute(
      "multi-baseline",
      Vector("clean", "core/run"),
      "multi-local baseline failed"
    )
    require(multiRuntimeValue(baselineLog) == "A:runtime-v1;B:runtime-v1", "multi-local baseline output mismatch")
    val baselineState = readState(new File(evidence, "multi-baseline.state"))
    val markerLabels = splitList(baselineState("markerLabels"))
    val handlerLabels = splitList(baselineState("handlerLabels"))
    val handlerFiles = splitList(baselineState("handlerFiles"))
    require(markerLabels == Vector("local-marker-0000", "local-marker-0001"), "multi-local marker labels mismatch")
    val directPrimariesFirst = handlerLabels.take(2) == Vector("local-handler-0000", "local-handler-0001")
    require(directPrimariesFirst, "multi-local handler primaries are not direct-first")
    val sharedRuntimeCount = handlerFiles.count(_.contains("shared-handler-runtime"))
    require(sharedRuntimeCount == 1, "shared handler runtime dependency was not retained exactly once")
    require(handlerFiles.distinct == handlerFiles, "multi-local handler classpath was not canonically de-duplicated")
    val runtimeIsolated = baselineState("runtimeIsolated") == "true"
    require(runtimeIsolated, "multi-local marker or handler tool artifact leaked onto runtime classpath")

    val markerASource = new File(build, "marker-a/src/main/scala/MarkerA.scala")
    val markerBSource = new File(build, "marker-b/src/main/scala/MarkerB.scala")
    val handlerASource = new File(build, "handler-a/src/main/scala/HandlerA.scala")
    val handlerBSource = new File(build, "handler-b/src/main/scala/HandlerB.scala")
    val sharedSource = new File(build, "shared-handler-runtime/src/main/scala/SharedRuntime.scala")
    val originalMarkerA = read(markerASource)
    val originalMarkerB = read(markerBSource)
    val originalHandlerA = read(handlerASource)
    val originalHandlerB = read(handlerBSource)
    val originalShared = read(sharedSource)

    write(markerASource, originalMarkerA.replace("HandlerA\")", "AlternateHandlerA\")"))
    val (markerAEdited, markerALog) = execute("marker-a-edited", Vector("core/run"), "marker A incremental rebuild failed")
    require(multiRuntimeValue(markerALog) == "A:runtime-v1;B:runtime-v1", "marker A edit changed semantics")
    require(changedOnly(baseline, markerAEdited, "markerA").isEmpty, changedOnly(baseline, markerAEdited, "markerA").mkString("; "))

    write(markerBSource, originalMarkerB.replace("HandlerB\")", "AlternateHandlerB\")"))
    val (markerBEdited, markerBLog) = execute("marker-b-edited", Vector("core/run"), "marker B incremental rebuild failed")
    require(multiRuntimeValue(markerBLog) == "A:runtime-v1;B:runtime-v1", "marker B edit changed semantics")
    require(changedOnly(markerAEdited, markerBEdited, "markerB").isEmpty, changedOnly(markerAEdited, markerBEdited, "markerB").mkString("; "))

    write(handlerASource, originalHandlerA.replace("\"A:\" + SharedRuntime.current", "\"A2:\" + SharedRuntime.current"))
    val (handlerAEdited, handlerALog) = execute("handler-a-edited", Vector("core/run"), "handler A incremental rebuild failed")
    require(multiRuntimeValue(handlerALog) == "A2:runtime-v1;B:runtime-v1", "handler A edit output mismatch")
    require(changedOnly(markerBEdited, handlerAEdited, "handlerA").isEmpty, changedOnly(markerBEdited, handlerAEdited, "handlerA").mkString("; "))

    write(handlerBSource, originalHandlerB.replace("\"B:\" + SharedRuntime.current", "\"B2:\" + SharedRuntime.current"))
    val (handlerBEdited, handlerBLog) = execute("handler-b-edited", Vector("core/run"), "handler B incremental rebuild failed")
    require(multiRuntimeValue(handlerBLog) == "A2:runtime-v1;B2:runtime-v1", "handler B edit output mismatch")
    require(changedOnly(handlerAEdited, handlerBEdited, "handlerB").isEmpty, changedOnly(handlerAEdited, handlerBEdited, "handlerB").mkString("; "))

    write(sharedSource, originalShared.replace("runtime-v1", "runtime-v2"))
    val (sharedEdited, sharedLog) = execute("shared-runtime-edited", Vector("core/run"), "shared runtime incremental rebuild failed")
    require(multiRuntimeValue(sharedLog) == "A2:runtime-v2;B2:runtime-v2", "shared runtime edit output mismatch")
    require(changedOnly(handlerBEdited, sharedEdited, "sharedRuntime").isEmpty, changedOnly(handlerBEdited, sharedEdited, "sharedRuntime").mkString("; "))

    write(markerASource, originalMarkerA)
    write(markerBSource, originalMarkerB)
    write(handlerASource, originalHandlerA)
    write(handlerBSource, originalHandlerB)
    write(sharedSource, originalShared)
    val (restored, restoredLog) = execute("restored", Vector("core/run"), "multi-local restoration failed")
    require(multiRuntimeValue(restoredLog) == "A:runtime-v1;B:runtime-v1", "restored output mismatch")
    require(restored.copy(consumerMtime = baseline.consumerMtime) == baseline, "restored artifact identity did not return to baseline")
    val (noOp, noOpLog) = execute("noop", Vector("core/run"), "multi-local no-op failed")
    require(multiRuntimeValue(noOpLog) == "A:runtime-v1;B:runtime-v1", "no-op output mismatch")
    val noOpStable = noOp == restored
    require(noOpStable, "consecutive multi-local no-op changed identity or consumer output")

    def fails(slot: String, commands: Vector[String]): Boolean = {
      val log = new File(evidence, slot + ".log")
      runSbt(build, config, slot, commands, log) != 0
    }
    val emptyMarkerFailed = fails(
      "empty-marker-role",
      Vector("set core / macroParadiseMarkerArtifacts := Seq.empty", "core/macroParadiseValidate")
    )
    val emptyHandlerFailed = fails(
      "empty-handler-role",
      Vector("set core / macroParadiseHandlerClasspath := Seq.empty", "core/macroParadiseValidate")
    )
    val roleCollisionFailed = fails(
      "role-collision",
      Vector(
        "set core / macroParadiseMarkerArtifacts := Seq(macroParadiseLabelled(\"collision\", (handlerA / Compile / packageBin).value))",
        "core/macroParadiseValidate"
      )
    )
    val missingDependencyFailed = fails(
      "missing-shared-dependency",
      Vector(
        "set core / macroParadiseHandlerClasspath := Seq(macroParadiseLabelled(\"handler-a\", (handlerA / Compile / packageBin).value), macroParadiseLabelled(\"handler-b\", (handlerB / Compile / packageBin).value))",
        "core/clean",
        "core/compile"
      )
    )
    val missingHandlerFailed = fails(
      "missing-handler-b",
      Vector(
        "set core / macroParadiseHandlerClasspath := Seq(macroParadiseLabelled(\"handler-a\", (handlerA / Compile / packageBin).value), macroParadiseLabelled(\"runtime\", (sharedHandlerRuntime / Compile / packageBin).value))",
        "core/clean",
        "core/compile"
      )
    )
    require(emptyMarkerFailed && emptyHandlerFailed, "multi-local empty role validation unexpectedly passed")
    require(roleCollisionFailed, "multi-local marker/handler role collision unexpectedly passed")
    require(missingDependencyFailed, "multi-local missing shared handler dependency unexpectedly compiled")
    require(missingHandlerFailed, "multi-local missing handler B unexpectedly compiled")

    val result = MultiLocalVerificationResult(
      config.scalaVersion,
      markerLabels.size,
      handlerLabels.take(2).size,
      sharedRuntimeCount,
      directPrimariesFirst,
      runtimeIsolated,
      Vector("markerA", "markerB", "handlerA", "handlerB", "sharedRuntime"),
      missingRoleNegatives = true,
      roleCollisionNegative = true,
      missingDependencyNegative = true,
      missingHandlerNegative = true,
      noOpStable,
      evidence
    )
    write(new File(evidence, "summary.txt"), result.render + "\n")
    result
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
      "com/github/dmytromitin/" + module + "/" + config.projectVersion
    )
    sbt.IO.createDirectory(directory)
    Files.copy(
      jar.toPath,
      new File(directory, module + "-" + config.projectVersion + ".jar").toPath,
      StandardCopyOption.REPLACE_EXISTING
    )
    Files.copy(
      pom.toPath,
      new File(directory, module + "-" + config.projectVersion + ".pom").toPath,
      StandardCopyOption.REPLACE_EXISTING
    )
  }

  private def createBuild(
      repositoryRoot: File,
      repository: File,
      build: File,
      evidence: File,
      config: Config
  ): Unit = {
    Vector(
      "project",
      "handler-runtime/src/main/scala",
      "marker/src/main/scala",
      "handler/src/main/scala",
      "core/src/main/scala"
    ).foreach(path => sbt.IO.createDirectory(new File(build, path)))
    write(new File(build, "project/build.properties"), "sbt.version=" + config.sbtVersion + "\n")
    Files.copy(
      new File(repositoryRoot, "sbt-integration/src/main/scala/macroparadise/sbt/ArtifactIdentity.scala").toPath,
      new File(build, "project/ArtifactIdentity.scala").toPath,
      StandardCopyOption.REPLACE_EXISTING
    )
    Files.copy(
      new File(repositoryRoot, "sbt-integration/src/main/scala/macroparadise/sbt/MacroParadisePrecompiledPlugin.scala").toPath,
      new File(build, "project/MacroParadisePrecompiledPlugin.scala").toPath,
      StandardCopyOption.REPLACE_EXISTING
    )
    write(new File(build, "handler-runtime/src/main/scala/DependencyValue.scala"), dependencySource("dependency-v1"))
    write(new File(build, "marker/src/main/scala/generated.scala"), markerSource)
    write(new File(build, "handler/src/main/scala/GeneratedHandler.scala"), handlerSource)
    write(new File(build, "core/src/main/scala/Consumer.scala"), consumerSource)
    write(new File(build, "build.sbt"), buildText(repository, evidence, config))
  }

  private def createMultiLocalBuild(
      repositoryRoot: File,
      repository: File,
      build: File,
      evidence: File,
      config: Config
  ): Unit = {
    Vector(
      "project",
      "shared-handler-runtime/src/main/scala",
      "marker-a/src/main/scala",
      "marker-b/src/main/scala",
      "handler-a/src/main/scala",
      "handler-b/src/main/scala",
      "core/src/main/scala"
    ).foreach(path => sbt.IO.createDirectory(new File(build, path)))
    write(new File(build, "project/build.properties"), "sbt.version=" + config.sbtVersion + "\n")
    Files.copy(
      new File(repositoryRoot, "sbt-integration/src/main/scala/macroparadise/sbt/ArtifactIdentity.scala").toPath,
      new File(build, "project/ArtifactIdentity.scala").toPath,
      StandardCopyOption.REPLACE_EXISTING
    )
    Files.copy(
      new File(repositoryRoot, "sbt-integration/src/main/scala/macroparadise/sbt/MacroParadisePrecompiledPlugin.scala").toPath,
      new File(build, "project/MacroParadisePrecompiledPlugin.scala").toPath,
      StandardCopyOption.REPLACE_EXISTING
    )
    write(new File(build, "shared-handler-runtime/src/main/scala/SharedRuntime.scala"), multiSharedRuntimeSource("runtime-v1"))
    write(new File(build, "marker-a/src/main/scala/MarkerA.scala"), multiMarkerSource("A", "HandlerA"))
    write(new File(build, "marker-b/src/main/scala/MarkerB.scala"), multiMarkerSource("B", "HandlerB"))
    write(new File(build, "handler-a/src/main/scala/HandlerA.scala"), multiHandlerSource("A"))
    write(new File(build, "handler-b/src/main/scala/HandlerB.scala"), multiHandlerSource("B"))
    write(new File(build, "core/src/main/scala/Consumer.scala"), multiConsumerSource)
    write(new File(build, "build.sbt"), multiLocalBuildText(repository, evidence, config))
  }

  private def multiLocalBuildText(repository: File, evidence: File, config: Config): String =
    s"""import java.io.File
       |import java.nio.charset.StandardCharsets
       |import java.nio.file.Files
       |import java.security.MessageDigest
       |import macroparadise.sbt.{MacroParadiseIntegration, MacroParadisePrecompiledPlugin}
       |import MacroParadisePrecompiledPlugin.autoImport._
       |
       |ThisBuild / scalaVersion := "${config.scalaVersion}"
       |ThisBuild / version := "${config.projectVersion}"
       |ThisBuild / resolvers := Seq(
       |  "task-product-repository" at "${scalaString(repository.toURI.toString)}",
       |  Resolver.mavenCentral
       |)
       |ThisBuild / externalResolvers := Seq(
       |  "task-product-repository" at "${scalaString(repository.toURI.toString)}",
       |  Resolver.mavenCentral
       |)
       |ThisBuild / credentials := Nil
       |ThisBuild / publish / skip := true
       |
       |val mpVersion = "${config.projectVersion}"
       |val mpApi =
       |  ("com.github.dmytromitin" % "macroparadise-scala3-plugin-api" % mpVersion)
       |    .cross(CrossVersion.full)
       |
       |lazy val recordMultiLocalState = taskKey[Unit]("Record multi-local invalidation state")
       |
       |lazy val sharedHandlerRuntime = project.in(file("shared-handler-runtime"))
       |  .settings(name := "shared-handler-runtime")
       |
       |lazy val markerA = project.in(file("marker-a"))
       |  .settings(name := "multi-marker-a", libraryDependencies += mpApi)
       |
       |lazy val markerB = project.in(file("marker-b"))
       |  .settings(name := "multi-marker-b", libraryDependencies += mpApi)
       |
       |def handlerSettings(moduleName: String): Seq[Def.Setting[_]] = Seq(
       |  name := moduleName,
       |  libraryDependencies ++= Seq(
       |    mpApi,
       |    "org.scala-lang" %% "scala3-compiler" % scalaVersion.value
       |  ),
       |  Compile / unmanagedJars += Attributed.blank((sharedHandlerRuntime / Compile / packageBin).value),
       |  Runtime / unmanagedJars += Attributed.blank((sharedHandlerRuntime / Compile / packageBin).value)
       |)
       |
       |lazy val handlerA = project.in(file("handler-a")).settings(handlerSettings("multi-handler-a"))
       |lazy val handlerB = project.in(file("handler-b")).settings(handlerSettings("multi-handler-b"))
       |
       |lazy val core = project.in(file("core"))
       |  .dependsOn(markerA % "provided->compile", markerB % "provided->compile")
       |  .enablePlugins(MacroParadisePrecompiledPlugin)
       |  .settings(MacroParadiseIntegration.precompiledProjects(
       |    markers = Seq(markerA, markerB),
       |    handlers = Seq(handlerA, handlerB)
       |  ))
       |  .settings(name := "multi-local-consumer", macroParadiseCompilerProductVersion := mpVersion)
       |
       |def sha256(file: File): String =
       |  MessageDigest.getInstance("SHA-256")
       |    .digest(Files.readAllBytes(file.toPath))
       |    .map(value => f"$${value & 0xff}%02x")
       |    .mkString
       |
       |lazy val root = project.in(file("."))
       |  .aggregate(sharedHandlerRuntime, markerA, markerB, handlerA, handlerB, core)
       |  .settings(
       |    recordMultiLocalState := {
       |      val slot = sys.props("matrix.slot")
       |      val markerAJar = (markerA / Compile / packageBin).value.getCanonicalFile
       |      val markerBJar = (markerB / Compile / packageBin).value.getCanonicalFile
       |      val handlerAJar = (handlerA / Compile / packageBin).value.getCanonicalFile
       |      val handlerBJar = (handlerB / Compile / packageBin).value.getCanonicalFile
       |      val sharedJar = (sharedHandlerRuntime / Compile / packageBin).value.getCanonicalFile
       |      val markerAClasses = (markerA / Compile / classDirectory).value.getCanonicalFile
       |      val markerBClasses = (markerB / Compile / classDirectory).value.getCanonicalFile
       |      val markers = (core / macroParadiseMarkerArtifacts).value
       |      val handlers = (core / macroParadiseHandlerClasspath).value
       |      val compileClasspath = (core / Compile / fullClasspath).value.files.map(_.getCanonicalFile)
       |      val runtimeClasspath = (core / Runtime / fullClasspath).value.files.map(_.getCanonicalFile)
       |      val consumerA = (core / Compile / classDirectory).value / "fixture" / "SubjectA.class"
       |      val consumerB = (core / Compile / classDirectory).value / "fixture" / "SubjectB.class"
       |      val runtimeIsolated =
       |        compileClasspath.contains(markerAClasses) && compileClasspath.contains(markerBClasses) &&
       |        !runtimeClasspath.contains(markerAClasses) && !runtimeClasspath.contains(markerBClasses) &&
       |        !runtimeClasspath.contains(handlerAJar) && !runtimeClasspath.contains(handlerBJar) &&
       |        !runtimeClasspath.contains(sharedJar)
       |      IO.write(
       |        file("${scalaString(evidence.getAbsolutePath)}/" + slot + ".state"),
       |        "markerASha256=" + sha256(markerAJar) + "\\n" +
       |          "markerBSha256=" + sha256(markerBJar) + "\\n" +
       |          "handlerASha256=" + sha256(handlerAJar) + "\\n" +
       |          "handlerBSha256=" + sha256(handlerBJar) + "\\n" +
       |          "sharedRuntimeSha256=" + sha256(sharedJar) + "\\n" +
       |          "identity=" + (core / macroParadiseExternalArtifactIdentity).value + "\\n" +
       |          "consumerMtime=" + math.max(consumerA.lastModified, consumerB.lastModified) + "\\n" +
       |          "markerLabels=" + markers.map(_.label).mkString("|") + "\\n" +
       |          "handlerLabels=" + handlers.map(_.label).mkString("|") + "\\n" +
       |          "handlerFiles=" + handlers.map(_.file.getCanonicalPath).mkString("|") + "\\n" +
       |          "runtimeIsolated=" + runtimeIsolated + "\\n",
       |        StandardCharsets.UTF_8
       |      )
       |    }
       |  )
       |""".stripMargin

  private def multiSharedRuntimeSource(value: String): String =
    s"""package fixture.runtime
       |
       |object SharedRuntime:
       |  def current: String = "$value"
       |""".stripMargin

  private def multiMarkerSource(suffix: String, handler: String): String =
    s"""package fixture.marker
       |
       |import paradise3.api.expander
       |import scala.annotation.StaticAnnotation
       |
       |@expander("fixture.handler.$handler")
       |final class marker$suffix extends StaticAnnotation
       |""".stripMargin

  private def multiHandlerSource(suffix: String): String =
    s"""package fixture.handler
       |
       |import dotty.tools.dotc.ast.untpd.*
       |import dotty.tools.dotc.core.Constants.Constant
       |import dotty.tools.dotc.core.Contexts.Context
       |import dotty.tools.dotc.core.Names.*
       |import fixture.runtime.SharedRuntime
       |import paradise3.api.*
       |import paradise3.api.helpers.ExpansionHelpers
       |
       |private object HandlerSupport:
       |  def expand(input: ExpansionInput, name: String, value: String)(using Context): ExpansionOutcome =
       |    val member = DefDef(termName(name), Nil, Ident(typeName("String")), Literal(Constant(value)))
       |    ExpansionEdit.finish(ExpansionEdit.start(input).flatMap(edit => ExpansionHelpers.placeMemberInPrimary(edit, member)))
       |
       |final class Handler$suffix extends ExpansionHandler:
       |  override def annotationName: String = "fixture.marker.marker$suffix"
       |  override def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
       |    HandlerSupport.expand(input, "generated$suffix", "$suffix:" + SharedRuntime.current)
       |
       |final class AlternateHandler$suffix extends ExpansionHandler:
       |  override def annotationName: String = "fixture.marker.marker$suffix"
       |  override def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
       |    HandlerSupport.expand(input, "generated$suffix", "$suffix:" + SharedRuntime.current)
       |""".stripMargin

  private val multiConsumerSource =
    """package fixture
      |
      |import fixture.marker.{markerA, markerB}
      |
      |@markerA class SubjectA
      |@markerB class SubjectB
      |
      |object Consumer:
      |  def main(args: Array[String]): Unit =
      |    println("MULTI_VALUE=" + new SubjectA().generatedA + ";" + new SubjectB().generatedB)
      |""".stripMargin

  private def buildText(repository: File, evidence: File, config: Config): String =
    s"""import java.io.File
       |import java.nio.charset.StandardCharsets
       |import java.nio.file.Files
       |import java.security.MessageDigest
       |import macroparadise.sbt.{MacroParadiseIntegration, MacroParadisePrecompiledPlugin}
       |import MacroParadisePrecompiledPlugin.autoImport._
       |
       |ThisBuild / scalaVersion := "${config.scalaVersion}"
       |ThisBuild / version := "${config.projectVersion}"
       |ThisBuild / resolvers := Seq(
       |  "task-product-repository" at "${scalaString(repository.toURI.toString)}",
       |  Resolver.mavenCentral
       |)
       |ThisBuild / externalResolvers := Seq(
       |  "task-product-repository" at "${scalaString(repository.toURI.toString)}",
       |  Resolver.mavenCentral
       |)
       |ThisBuild / credentials := Nil
       |ThisBuild / publish / skip := true
       |
       |val mpVersion = "${config.projectVersion}"
       |val mpApi =
       |  ("com.github.dmytromitin" % "macroparadise-scala3-plugin-api" % mpVersion)
       |    .cross(CrossVersion.full)
       |
       |lazy val recordState = taskKey[Unit]("Record dependency-only invalidation state")
       |
       |lazy val handlerRuntime = project.in(file("handler-runtime"))
       |  .settings(name := "external-handler-runtime")
       |
       |lazy val marker = project.in(file("marker"))
       |  .settings(
       |    name := "external-marker",
       |    libraryDependencies += mpApi
       |  )
       |
       |lazy val handler = project.in(file("handler"))
       |  .settings(
       |    name := "external-handler",
       |    libraryDependencies ++= Seq(
       |      mpApi,
       |      "org.scala-lang" %% "scala3-compiler" % scalaVersion.value
       |    ),
       |    Compile / unmanagedJars += Attributed.blank((handlerRuntime / Compile / packageBin).value)
       |  )
       |
       |lazy val core = project.in(file("core"))
       |  .dependsOn(marker)
       |  .enablePlugins(MacroParadisePrecompiledPlugin)
       |  .settings(MacroParadiseIntegration.precompiledProjects(marker, handler))
       |  .settings(
       |    name := "external-consumer",
       |    macroParadiseCompilerProductVersion := mpVersion
       |  )
       |
       |def sha256(file: File): String =
       |  MessageDigest.getInstance("SHA-256")
       |    .digest(Files.readAllBytes(file.toPath))
       |    .map(value => f"$${value & 0xff}%02x")
       |    .mkString
       |
       |def sha256Text(value: String): String =
       |  MessageDigest.getInstance("SHA-256")
       |    .digest(value.getBytes(StandardCharsets.UTF_8))
       |    .map(value => f"$${value & 0xff}%02x")
       |    .mkString
       |
       |lazy val root = project.in(file("."))
       |  .aggregate(handlerRuntime, marker, handler, core)
       |  .settings(
       |    recordState := {
       |      val slot = sys.props("matrix.slot")
       |      val markerJar = (marker / Compile / packageBin).value.getCanonicalFile
       |      val handlerJar = (handler / Compile / packageBin).value.getCanonicalFile
       |      val dependencyJar = (handlerRuntime / Compile / packageBin).value.getCanonicalFile
       |      val identity = (core / macroParadiseExternalArtifactIdentity).value
       |      val handlerClasspath = (core / macroParadiseHandlerClasspath).value.map(_.file.getCanonicalFile)
       |      val runtimeClasspath = (core / Runtime / fullClasspath).value.files.map(_.getCanonicalFile)
       |      val consumerClass = (core / Compile / classDirectory).value / "fixture" / "Subject.class"
       |      val oldIdentity = sha256Text("marker=" + sha256(markerJar) + "\\nhandler=" + sha256(handlerJar) + "\\n")
       |      val runtimeIsolated = !runtimeClasspath.contains(handlerJar) && !runtimeClasspath.contains(dependencyJar)
       |      IO.write(
       |        file("${scalaString(evidence.getAbsolutePath)}/" + slot + ".state"),
       |        "markerSha256=" + sha256(markerJar) + "\\n" +
       |          "handlerSha256=" + sha256(handlerJar) + "\\n" +
       |          "dependencySha256=" + sha256(dependencyJar) + "\\n" +
       |          "identity=" + identity + "\\n" +
       |          "oldPrimaryOnlyIdentity=" + oldIdentity + "\\n" +
       |          "consumerSha256=" + sha256(consumerClass) + "\\n" +
       |          "consumerMtime=" + consumerClass.lastModified + "\\n" +
       |          "handlerClasspath=" + handlerClasspath.map(_.getAbsolutePath).mkString(File.pathSeparator) + "\\n" +
       |          "runtimeIsolated=" + runtimeIsolated + "\\n",
       |        StandardCharsets.UTF_8
       |      )
       |    }
       |  )
       |""".stripMargin

  private def dependencySource(value: String): String =
    s"""package fixture.runtime
       |
       |object DependencyValue:
       |  def current: String = "$value"
       |""".stripMargin

  private val markerSource =
    """package fixture.marker
      |
      |import paradise3.api.expander
      |import scala.annotation.StaticAnnotation
      |
      |@expander("fixture.handler.GeneratedHandler")
      |final class generated extends StaticAnnotation
      |""".stripMargin

  private val handlerSource =
    """package fixture.handler
      |
      |import dotty.tools.dotc.ast.untpd.*
      |import dotty.tools.dotc.core.Constants.Constant
      |import dotty.tools.dotc.core.Contexts.Context
      |import dotty.tools.dotc.core.Names.*
      |import fixture.runtime.DependencyValue
      |import paradise3.api.*
      |import paradise3.api.helpers.ExpansionHelpers
      |
      |private object HandlerSupport:
      |  def expand(input: ExpansionInput, name: String, value: String)(using Context): ExpansionOutcome =
      |    val member = DefDef(termName(name), Nil, Ident(typeName("String")), Literal(Constant(value)))
      |    ExpansionEdit.finish(ExpansionEdit.start(input).flatMap(edit => ExpansionHelpers.placeMemberInPrimary(edit, member)))
      |
      |final class GeneratedHandler extends ExpansionHandler:
      |  override def annotationName: String = "fixture.marker.generated"
      |  override def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
      |    HandlerSupport.expand(input, "generatedValue", DependencyValue.current)
      |
      |final class AlternateHandler extends ExpansionHandler:
      |  override def annotationName: String = "fixture.marker.generated"
      |  override def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
      |    HandlerSupport.expand(input, "generatedValue", "marker-v2:" + DependencyValue.current)
      |""".stripMargin

  private val consumerSource =
    """package fixture
      |
      |import fixture.marker.generated
      |
      |@generated class Subject
      |
      |object Consumer:
      |  def main(args: Array[String]): Unit =
      |    println("DEPENDENCY_VALUE=" + new Subject().generatedValue)
      |""".stripMargin

  private def runSbt(
      directory: File,
      config: Config,
      slot: String,
      commands: Vector[String],
      log: File
  ): Int = {
    val lines = ArrayBuffer.empty[String]
    val command = Vector(
      "sbt",
      "-batch",
      "-Dsbt.ivy.home=" + new File(directory, ".task-ivy-home").getCanonicalPath,
      "-Dmatrix.slot=" + slot,
      "-Dmacroparadise.exactScalaVersion=" + config.scalaVersion
    ) ++ commands
    val exit = Process(command, directory).!(ProcessLogger(
      line => lines += line,
      line => lines += line
    ))
    write(log, lines.mkString("", "\n", "\n"))
    exit
  }

  private def runtimeValue(log: File): String = {
    val values = read(log).split("\\r?\\n").toVector.filter(_.contains("DEPENDENCY_VALUE="))
    require(values.nonEmpty, "runtime value witness is absent")
    values.last.substring(values.last.indexOf("DEPENDENCY_VALUE=") + "DEPENDENCY_VALUE=".length).trim
  }

  private def multiRuntimeValue(log: File): String = {
    val values = read(log).split("\\r?\\n").toVector.filter(_.contains("MULTI_VALUE="))
    require(values.nonEmpty, "multi-local runtime value witness is absent")
    values.last.substring(values.last.indexOf("MULTI_VALUE=") + "MULTI_VALUE=".length).trim
  }

  private def splitList(value: String): Vector[String] =
    value.split("\\|", -1).toVector.filter(_.nonEmpty)

  private def readState(file: File): Map[String, String] =
    read(file).split("\\r?\\n").toVector.filter(_.contains("=")).map { line =>
      val index = line.indexOf('=')
      line.substring(0, index) -> line.substring(index + 1)
    }.toMap

  private def read(file: File): String =
    new String(Files.readAllBytes(file.toPath), StandardCharsets.UTF_8)

  private def write(file: File, value: String): Unit = {
    Option(file.getParentFile).foreach(sbt.IO.createDirectory)
    Files.write(file.toPath, value.getBytes(StandardCharsets.UTF_8))
  }

  private def scalaString(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")
}
