import java.io.File
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.util.jar.JarFile

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.sys.process.{Process, ProcessLogger}

object ExperimentalPluginApiSurface {
  val CombinedPreservedClassification =
    "COMBINED_EXPERIMENTAL_SURFACE_BASELINE_REMEASURED"
  val FormatVersion = "1"
  val ExpectedScalaVersion =
    "3.8.5-RC1-bin-20260405-9478256-NIGHTLY"
  val ExpectedSbtVersion = "1.12.8"
  val ExpectedProjectVersion = "0.1.0-SNAPSHOT"
  val MetadataCarrierEntry = "paradise3/api/expander.class"
  val ArtifactRole =
    "exact-build-experimental-precompiled-handler-contract-and-fixtures"
  val ForbiddenPolicyIdentity = "plugin-api-thin-ownership-v1"

  val FixtureSupportEntries = Set(
    "paradise3/MetadataInitializationProbe.class"
  )

  val FixtureMarkerEntries = Set(
    "paradise3/PreservedRuntimeMarker.class",
    "paradise3/companionInvocationLinkageError.class",
    "paradise3/companionInvocationNotApplicable.class",
    "paradise3/companionInvocationNullOutcome.class",
    "paradise3/companionInvocationRejected.class",
    "paradise3/companionInvocationThrows.class",
    "paradise3/companionInvocationWrongFallback.class",
    "paradise3/externalCompanionDebug.class",
    "paradise3/externalDebug.class",
    "paradise3/externalLabel.class",
    "paradise3/externalMarker.class",
    "paradise3/externalQuasiquotesTerm.class",
    "paradise3/externalRestrictedTraitApply.class",
    "paradise3/externalSiblingDebug.class",
    "paradise3/externalTypedLabel.class",
    "paradise3/invocationEmptyRejected.class",
    "paradise3/invocationLinkageError.class",
    "paradise3/invocationNotApplicable.class",
    "paradise3/invocationNullOutcome.class",
    "paradise3/invocationNullRejectedDiagnostics.class",
    "paradise3/invocationNullRejectedFallback.class",
    "paradise3/invocationThrows.class",
    "paradise3/invocationWrongFallback.class",
    "paradise3/malformedConflictingAdditional.class",
    "paradise3/malformedDuplicatePrimary.class",
    "paradise3/malformedEmptyOutput.class",
    "paradise3/malformedLateCompanion.class",
    "paradise3/malformedMissingPrimary.class",
    "paradise3/metadataEmpty.class",
    "paradise3/metadataMissing.class",
    "paradise3/structuredNullAdditionalElement.class",
    "paradise3/structuredNullAdditionalList.class",
    "paradise3/structuredNullCompanion.class",
    "paradise3/structuredNullCompanionOption.class",
    "paradise3/structuredNullOutput.class",
    "paradise3/structuredNullPrimary.class",
    "paradise3/structuredTopLevelConflict.class",
    "paradise3/structuredUnknownAdditional.class",
    "qualifiedlookalike/gen.class",
    "qualifiedone/audit.class",
    "qualifiedtwo/audit.class",
    "qualifiedunknown/audit.class",
    "qualifiedwrong/audit.class"
  )

  val FixtureMarkerTastyEntries =
    FixtureMarkerEntries.map(_.stripSuffix(".class") + ".tasty")

  val ForbiddenPrefixes = Vector(
    "macroparadise/",
    "demo/",
    "quasiquotes/",
    "dotty/",
    "scala/",
    "scala3/",
    "tasty/"
  )

  final case class Config(
      scalaVersion: String,
      sbtVersion: String,
      projectVersion: String
  )

  final case class Surface(
      bodyLines: Vector[String],
      manifestLines: Vector[String],
      normalizedSha256: String,
      handlerContractClassCount: Int,
      metadataCarrierCount: Int,
      fixtureMarkerCount: Int,
      fixtureSupportCount: Int,
      memberRecordCount: Int,
      resourceRecordCount: Int
  )

  final case class SurfaceDiff(
      added: Vector[String],
      removed: Vector[String],
      changed: Vector[(String, String, String)]
  ) {
    def isEmpty: Boolean =
      added.isEmpty && removed.isEmpty && changed.isEmpty

    def render: String = {
      val lines = mutable.ArrayBuffer.empty[String]
      changed.foreach {
        case (key, expected, actual) =>
          lines += s"CHANGED $key"
          lines += s"  expected: $expected"
          lines += s"  actual:   $actual"
      }
      removed.foreach(line => lines += s"REMOVED $line")
      added.foreach(line => lines += s"ADDED $line")
      if (lines.isEmpty) "no surface drift" else lines.mkString("\n")
    }
  }

  final case class VerificationResult(
      surface: Surface,
      contractArtifact: File,
      markerArtifact: File,
      contractArtifactBytes: Long,
      markerArtifactBytes: Long,
      contractArtifactEntries: Int,
      markerArtifactEntries: Int,
      contractArtifactSha256: String,
      markerArtifactSha256: String,
      positiveCompileExit: Int,
      runtimeExit: Int,
      forbiddenImplementationExit: Int,
      missingPluginApiExit: Int,
      probeClassCount: Int,
      evidenceDirectory: File
  ) {
    def render: String =
      s"classification=EXPERIMENTAL_PLUGIN_API_SURFACE_BASELINE_READY " +
        s"combinedClassification=$CombinedPreservedClassification " +
        s"normalizedSha256=${surface.normalizedSha256} " +
        s"handlerContractClasses=${surface.handlerContractClassCount} " +
        s"metadataCarriers=${surface.metadataCarrierCount} " +
        s"fixtureMarkers=${surface.fixtureMarkerCount} " +
        s"fixtureSupport=${surface.fixtureSupportCount} " +
        s"members=${surface.memberRecordCount} resources=${surface.resourceRecordCount} " +
        s"contractArtifactBytes=$contractArtifactBytes markerArtifactBytes=$markerArtifactBytes " +
        s"contractArtifactEntries=$contractArtifactEntries markerArtifactEntries=$markerArtifactEntries " +
        s"contractArtifactSha256=$contractArtifactSha256 markerArtifactSha256=$markerArtifactSha256 " +
        s"positiveCompileExit=$positiveCompileExit " +
        s"runtimeExit=$runtimeExit forbiddenImplementationExit=$forbiddenImplementationExit " +
        s"missingPluginApiExit=$missingPluginApiExit probeClasses=$probeClassCount"
  }

  def renderBaselineCandidate(
      contractArtifact: File,
      markerArtifact: File,
      destination: File,
      config: Config,
      evidenceDirectory: File
  ): Surface = {
    recreateDirectory(evidenceDirectory.toPath)
    val surface =
      deriveCombinedSurface(contractArtifact, markerArtifact, config, Some(evidenceDirectory))
    writeLines(destination.toPath, surface.manifestLines)
    surface
  }

  def verify(
      repositoryRoot: File,
      contractArtifact: File,
      markerArtifact: File,
      dependencyClasspath: Seq[File],
      baselineFile: File,
      positiveSource: File,
      forbiddenImplementationSource: File,
      config: Config,
      evidenceDirectory: File
  ): VerificationResult = {
    recreateDirectory(evidenceDirectory.toPath)
    validateExactBuild(config)
    val surface =
      deriveCombinedSurface(contractArtifact, markerArtifact, config, Some(evidenceDirectory))
    writeLines(
      new File(evidenceDirectory, "current-surface.txt").toPath,
      surface.manifestLines
    )

    val expected = parseManifest(readLines(baselineFile.toPath))
    val actual = parseManifest(surface.manifestLines)
    val drift = compare(expected, actual)
    if (!drift.isEmpty)
      throw new IllegalStateException(
        s"experimental pluginApi surface drift:\n${drift.render}"
      )

    val contractEntries = jarEntries(contractArtifact)
    val markerEntries = jarEntries(markerArtifact)
    val entryErrors = splitOwnershipErrors(contractEntries, markerEntries)
    if (entryErrors.nonEmpty)
      throw new IllegalStateException(entryErrors.mkString("; "))

    val compilerJars = dependencyClasspath
      .filter(file => file.isFile && file.getName.endsWith(".jar"))
      .map(_.getAbsoluteFile)
      .distinct
      .sortBy(_.getAbsolutePath)
    require(
      compilerJars.exists(
        _.getName == s"scala3-compiler_3-${config.scalaVersion}.jar"
      ),
      s"isolated probe classpath is missing exact compiler ${config.scalaVersion}"
    )
    require(
      compilerJars.forall(file => !isWithin(repositoryRoot, file)),
      s"isolated probe dependency classpath leaked repository outputs: ${compilerJars.filter(isWithin(repositoryRoot, _)).mkString(", ")}"
    )

    val probeRoot = new File(evidenceDirectory, "isolated-probe")
    recreateDirectory(probeRoot.toPath)
    val positiveOutput = new File(probeRoot, "positive-classes")
    val forbiddenOutput = new File(probeRoot, "forbidden-implementation-classes")
    val missingApiOutput = new File(probeRoot, "missing-plugin-api-classes")
    Seq(positiveOutput, forbiddenOutput, missingApiOutput).foreach(file =>
      Files.createDirectories(file.toPath)
    )

    val javaCommand = javaTool("java")
    val compilerProcessClasspath = classpath(compilerJars)
    val isolatedCompileClasspath = classpath(contractArtifact +: compilerJars)
    val positiveCompileCommand = Seq(
      javaCommand,
      "-cp",
      compilerProcessClasspath,
      "dotty.tools.dotc.Main",
      "-classpath",
      isolatedCompileClasspath,
      "-d",
      positiveOutput.getAbsolutePath,
      positiveSource.getAbsolutePath
    )
    val positiveCompileExit = runProcess(
      positiveCompileCommand,
      repositoryRoot,
      new File(evidenceDirectory, "positive-source-compile.log")
    )
    require(
      positiveCompileExit == 0,
      s"isolated positive handler source failed with exit $positiveCompileExit"
    )

    val probeClasses = regularRelativeFiles(positiveOutput)
      .filter(_.endsWith(".class"))
    require(probeClasses.nonEmpty, "isolated positive probe emitted no classes")
    require(
      !probeClasses.exists(_.startsWith("paradise3/api/")),
      s"isolated probe copied pluginApi classes: ${probeClasses.filter(_.startsWith("paradise3/api/")).mkString(", ")}"
    )

    val runtimeCommand = Seq(
      javaCommand,
      "-cp",
      classpath(positiveOutput +: contractArtifact +: compilerJars),
      "surfaceprobe.IsolatedPluginApiSurfaceRuntime"
    )
    val runtimeLog = new File(evidenceDirectory, "runtime-linkage.log")
    val runtimeExit = runProcess(runtimeCommand, repositoryRoot, runtimeLog)
    val runtimeOutput = read(runtimeLog.toPath)
    require(runtimeExit == 0, s"isolated runtime linkage failed: $runtimeOutput")
    Vector(
      "annotationName=surfaceProbe",
      "defaultConsumesExistingCompanion=false",
      "overrideConsumesExistingCompanion=true",
      "apiIdentityShared=true",
      "expandDescriptor=(paradise3.api.ExpansionInput,dotty.tools.dotc.core.Contexts$Context)paradise3.api.ExpansionOutcome",
      s"apiCodeSource=${contractArtifact.getCanonicalPath}"
    ).foreach(fragment =>
      require(
        runtimeOutput.contains(fragment),
        s"runtime linkage output missing `$fragment`: $runtimeOutput"
      )
    )

    val forbiddenCommand = Seq(
      javaCommand,
      "-cp",
      compilerProcessClasspath,
      "dotty.tools.dotc.Main",
      "-classpath",
      isolatedCompileClasspath,
      "-d",
      forbiddenOutput.getAbsolutePath,
      forbiddenImplementationSource.getAbsolutePath
    )
    val forbiddenLog = new File(evidenceDirectory, "negative-forbidden-implementation.log")
    val forbiddenExit =
      runProcess(forbiddenCommand, repositoryRoot, forbiddenLog)
    require(forbiddenExit != 0, "forbidden implementation probe unexpectedly compiled")
    val forbiddenOutputText = read(forbiddenLog.toPath)
    require(
      forbiddenOutputText.contains("macroparadise") &&
        regularRelativeFiles(forbiddenOutput).isEmpty,
      s"forbidden implementation probe lacked focused isolation evidence: $forbiddenOutputText"
    )

    val missingApiCommand = Seq(
      javaCommand,
      "-cp",
      compilerProcessClasspath,
      "dotty.tools.dotc.Main",
      "-classpath",
      classpath(compilerJars),
      "-d",
      missingApiOutput.getAbsolutePath,
      positiveSource.getAbsolutePath
    )
    val missingApiLog = new File(evidenceDirectory, "negative-missing-plugin-api.log")
    val missingApiExit = runProcess(missingApiCommand, repositoryRoot, missingApiLog)
    require(missingApiExit != 0, "positive source compiled without packaged pluginApi")
    val missingApiText = read(missingApiLog.toPath)
    require(
      missingApiText.contains("paradise3") && regularRelativeFiles(missingApiOutput).isEmpty,
      s"missing-pluginApi probe lacked focused unresolved API evidence: $missingApiText"
    )

    val result = VerificationResult(
      surface,
      contractArtifact,
      markerArtifact,
      contractArtifact.length(),
      markerArtifact.length(),
      contractEntries.size,
      markerEntries.size,
      sha256File(contractArtifact),
      sha256File(markerArtifact),
      positiveCompileExit,
      runtimeExit,
      forbiddenExit,
      missingApiExit,
      probeClasses.size,
      evidenceDirectory
    )
    Files.write(
      new File(evidenceDirectory, "summary.txt").toPath,
      (result.render + "\n").getBytes(StandardCharsets.UTF_8)
    )
    result
  }

  def deriveSurface(
      artifact: File,
      config: Config,
      evidenceDirectory: Option[File]
  ): Surface = {
    validateExactBuild(config)
    require(
      artifact.isFile && artifact.canRead,
      s"pluginApi artifact must be one regular readable JAR: ${artifact.getAbsolutePath}"
    )
    val entries = jarEntries(artifact)
    val policyErrors = entryPolicyErrors(entries)
    if (policyErrors.nonEmpty)
      throw new IllegalStateException(policyErrors.mkString("; "))

    val classEntries = entries.filter(_.endsWith(".class")).sorted
    val classRecords = classEntries.map { entry =>
      val category = classCategory(entry)
      val javap = javapOutput(artifact, entry)
      evidenceDirectory.foreach { directory =>
        val javapDirectory = new File(directory, "javap")
        Files.createDirectories(javapDirectory.toPath)
        val name = entry.stripSuffix(".class").replace('/', '_') + ".txt"
        Files.write(
          new File(javapDirectory, name).toPath,
          javap.getBytes(StandardCharsets.UTF_8)
        )
      }
      val declaration = classDeclaration(javap, binaryName(entry))
      s"CLASS|$entry|$category|${escape(declaration)}"
    }

    val contractEntries = classEntries.filter(entry =>
      entry.startsWith("paradise3/api/") || entry == MetadataCarrierEntry
    )
    val memberRecords = contractEntries.flatMap { entry =>
      publicProtectedMembers(javapOutput(artifact, entry), binaryName(entry))
        .map { member =>
          s"MEMBER|$entry|${member.kind}|${escape(member.name)}|${member.overloadIndex}|${member.visibility}|${member.modifiers}|${member.descriptor}"
        }
    }

    val metadataRecord = metadataCarrierRecord(artifact)
    val resourceRecords = entries
      .filterNot(entry => entry.endsWith("/") || entry.endsWith(".class"))
      .map {
        case "META-INF/MANIFEST.MF" =>
          "RESOURCE|META-INF/MANIFEST.MF|STANDARD_JAR_METADATA"
        case entry if entry.endsWith(".tasty") =>
          s"RESOURCE|$entry|SCALA_TASTY"
        case entry =>
          throw new IllegalStateException(s"unexpected pluginApi resource `$entry`")
      }

    val policyRecords = Vector(
      s"POLICY|forbidden-package-identity|$ForbiddenPolicyIdentity",
      s"POLICY|forbidden-prefixes|${ForbiddenPrefixes.sorted.mkString(",")}",
      "POLICY|standard-metadata|META-INF/MANIFEST.MF"
    )
    val records = canonicalizeRecords(
      classRecords ++ memberRecords ++ Vector(metadataRecord) ++ resourceRecords ++ policyRecords
    )
    val body = Vector(
      s"format-version=$FormatVersion",
      s"scala-compiler=${config.scalaVersion}",
      s"sbt=${config.sbtVersion}",
      "jdk-feature=25",
      s"project-version=${config.projectVersion}",
      s"artifact-role=$ArtifactRole"
    ) ++ records
    val digest = sha256Lines(body)
    val manifest = body :+ s"normalized-sha256=$digest"

    val classificationErrors = validateClassification(records)
    if (classificationErrors.nonEmpty)
      throw new IllegalStateException(classificationErrors.mkString("; "))

    Surface(
      body,
      manifest,
      digest,
      classRecords.count(_.contains("|HANDLER_CONTRACT|")),
      classRecords.count(_.contains("|METADATA_CARRIER|")),
      classRecords.count(_.contains("|INTEGRATION_FIXTURE_MARKER|")),
      classRecords.count(_.contains("|INTEGRATION_FIXTURE_SUPPORT|")),
      memberRecords.size,
      resourceRecords.size
    )
  }

  def deriveCombinedSurface(
      contractArtifact: File,
      markerArtifact: File,
      config: Config,
      evidenceDirectory: Option[File]
  ): Surface = {
    validateExactBuild(config)
    Vector(contractArtifact, markerArtifact).foreach { artifact =>
      require(
        artifact.isFile && artifact.canRead,
        s"split surface artifact must be one regular readable JAR: ${artifact.getAbsolutePath}"
      )
    }
    val contractEntries = jarEntries(contractArtifact)
    val markerEntries = jarEntries(markerArtifact)
    val policyErrors = splitOwnershipErrors(contractEntries, markerEntries)
    if (policyErrors.nonEmpty)
      throw new IllegalStateException(policyErrors.mkString("; "))

    val classOwners =
      (contractEntries.filter(_.endsWith(".class")).map(_ -> contractArtifact) ++
        markerEntries.filter(_.endsWith(".class")).map(_ -> markerArtifact)).toMap
    val classEntries = classOwners.keys.toVector.sorted
    val classRecords = classEntries.map { entry =>
      val artifact = classOwners(entry)
      val category = classCategory(entry)
      val javap = javapOutput(artifact, entry)
      evidenceDirectory.foreach { directory =>
        val javapDirectory = new File(directory, "javap")
        Files.createDirectories(javapDirectory.toPath)
        val name = entry.stripSuffix(".class").replace('/', '_') + ".txt"
        Files.write(
          new File(javapDirectory, name).toPath,
          javap.getBytes(StandardCharsets.UTF_8)
        )
      }
      val declaration = classDeclaration(javap, binaryName(entry))
      s"CLASS|$entry|$category|${escape(declaration)}"
    }

    val contractClasses = contractEntries.filter(_.endsWith(".class")).sorted
    val memberRecords = contractClasses.flatMap { entry =>
      publicProtectedMembers(
        javapOutput(contractArtifact, entry),
        binaryName(entry)
      ).map { member =>
        s"MEMBER|$entry|${member.kind}|${escape(member.name)}|${member.overloadIndex}|${member.visibility}|${member.modifiers}|${member.descriptor}"
      }
    }

    val combinedResources =
      (contractEntries ++ markerEntries)
        .filterNot(entry => entry.endsWith("/") || entry.endsWith(".class"))
        .distinct
        .sorted
    val resourceRecords = combinedResources.map {
      case "META-INF/MANIFEST.MF" =>
        "RESOURCE|META-INF/MANIFEST.MF|STANDARD_JAR_METADATA"
      case entry if entry.endsWith(".tasty") =>
        s"RESOURCE|$entry|SCALA_TASTY"
      case entry =>
        throw new IllegalStateException(s"unexpected split surface resource `$entry`")
    }
    val policyRecords = Vector(
      s"POLICY|forbidden-package-identity|$ForbiddenPolicyIdentity",
      s"POLICY|forbidden-prefixes|${ForbiddenPrefixes.sorted.mkString(",")}",
      "POLICY|standard-metadata|META-INF/MANIFEST.MF"
    )
    val records = canonicalizeRecords(
      classRecords ++ memberRecords ++
        Vector(metadataCarrierRecord(contractArtifact)) ++ resourceRecords ++ policyRecords
    )
    val body = Vector(
      s"format-version=$FormatVersion",
      s"scala-compiler=${config.scalaVersion}",
      s"sbt=${config.sbtVersion}",
      "jdk-feature=25",
      s"project-version=${config.projectVersion}",
      s"artifact-role=$ArtifactRole"
    ) ++ records
    val digest = sha256Lines(body)
    val manifest = body :+ s"normalized-sha256=$digest"
    val classificationErrors = validateClassification(records)
    if (classificationErrors.nonEmpty)
      throw new IllegalStateException(classificationErrors.mkString("; "))
    Surface(
      body,
      manifest,
      digest,
      classRecords.count(_.contains("|HANDLER_CONTRACT|")),
      classRecords.count(_.contains("|METADATA_CARRIER|")),
      classRecords.count(_.contains("|INTEGRATION_FIXTURE_MARKER|")),
      classRecords.count(_.contains("|INTEGRATION_FIXTURE_SUPPORT|")),
      memberRecords.size,
      resourceRecords.size
    )
  }

  def splitOwnershipErrors(
      contractEntries: Seq[String],
      markerEntries: Seq[String]
  ): Vector[String] = {
    val errors = mutable.ArrayBuffer.empty[String]
    val contractFiles = contractEntries.filterNot(_.endsWith("/")).toSet
    val markerFiles = markerEntries.filterNot(_.endsWith("/")).toSet
    val duplicates =
      (contractFiles intersect markerFiles) - "META-INF/MANIFEST.MF"
    if (duplicates.nonEmpty)
      errors += s"duplicate entries across split artifacts: ${duplicates.toVector.sorted.mkString(", ")}"

    contractFiles.foreach { entry =>
      val allowed =
        entry == "META-INF/MANIFEST.MF" ||
          entry.startsWith("paradise3/api/") &&
            (entry.endsWith(".class") || entry.endsWith(".tasty"))
      if (!allowed)
        errors += s"pluginApi contract artifact owns non-contract entry `$entry`"
    }
    markerFiles.foreach { entry =>
      val allowed =
        entry == "META-INF/MANIFEST.MF" ||
          FixtureMarkerEntries.contains(entry) ||
          FixtureSupportEntries.contains(entry) ||
          FixtureMarkerTastyEntries.contains(entry) ||
          entry.startsWith("paradise3/") && entry.endsWith(".tasty")
      if (!allowed)
        errors += s"pluginTestMarkers artifact owns non-fixture entry `$entry`"
      if (entry.startsWith("paradise3/api/"))
        errors += s"pluginTestMarkers artifact copied contract entry `$entry`"
    }
    val contractClasses = contractFiles.filter(_.endsWith(".class"))
    val expectedContractClasses =
      contractClasses.filter(_.startsWith("paradise3/api/"))
    if (contractClasses != expectedContractClasses)
      errors += "pluginApi contract artifact contains fixture classes"
    val actualMarkers = markerFiles.filter(_.endsWith(".class"))
    val expectedMarkers = FixtureMarkerEntries ++ FixtureSupportEntries
    val missingMarkers = expectedMarkers -- actualMarkers
    val extraMarkers = actualMarkers -- expectedMarkers
    if (missingMarkers.nonEmpty)
      errors += s"pluginTestMarkers artifact is missing fixtures: ${missingMarkers.toVector.sorted.mkString(", ")}"
    if (extraMarkers.nonEmpty)
      errors += s"pluginTestMarkers artifact has unexpected classes: ${extraMarkers.toVector.sorted.mkString(", ")}"
    errors.toVector.distinct
  }

  final case class MemberRecord(
      kind: String,
      name: String,
      overloadIndex: Int,
      visibility: String,
      modifiers: String,
      descriptor: String
  )

  def publicProtectedMembers(
      javap: String,
      binaryClassName: String
  ): Vector[MemberRecord] = {
    val raw = mutable.ArrayBuffer.empty[(String, String, String, String, String)]
    var pending: Option[String] = None
    javap.split("\\r?\\n", -1).foreach { original =>
      val line = original.trim
      if (
        (line.startsWith("public ") || line.startsWith("protected ")) &&
        !line.endsWith("{")
      ) pending = Some(line)
      else if (line.startsWith("descriptor:") && pending.nonEmpty) {
        val declaration = pending.get
        val descriptor = line.stripPrefix("descriptor:").trim
        val (kind, name) = memberKindAndName(declaration, binaryClassName)
        val visibility = declaration.takeWhile(!_.isWhitespace)
        val modifiers = memberModifiers(declaration, visibility)
        raw += ((kind, name, visibility, modifiers, descriptor))
        pending = None
      }
    }
    raw
      .groupBy { case (kind, name, _, _, _) => (kind, name) }
      .toVector
      .sortBy { case ((kind, name), _) => (kind, name) }
      .flatMap {
        case ((kind, name), members) =>
          members.toVector
            .sortBy { case (_, _, visibility, modifiers, descriptor) =>
              (descriptor, visibility, modifiers)
            }
            .zipWithIndex
            .map {
              case ((_, _, visibility, modifiers, descriptor), index) =>
                MemberRecord(
                  kind,
                  name,
                  index,
                  visibility,
                  modifiers,
                  descriptor
                )
            }
      }
  }

  def canonicalizeRecords(records: Seq[String]): Vector[String] = {
    val vector = records.toVector
    require(
      vector.distinct.size == vector.size,
      s"duplicate surface records: ${vector.groupBy(identity).collect { case (line, values) if values.size > 1 => line }.toList.sorted.mkString(", ")}"
    )
    vector.sorted
  }

  def withIntegrity(body: Vector[String]): Vector[String] =
    body :+ s"normalized-sha256=${sha256Lines(body)}"

  def parseManifest(lines: Vector[String]): Vector[String] = {
    require(lines.nonEmpty, "surface manifest is empty")
    require(
      lines.head.startsWith("format-version="),
      "surface manifest is missing the format-version header"
    )
    require(
      lines.head == s"format-version=$FormatVersion",
      s"unsupported surface manifest ${lines.head}"
    )
    require(
      lines.last.startsWith("normalized-sha256="),
      "surface manifest is missing its normalized-sha256 integrity line"
    )
    val body = lines.dropRight(1)
    val expected = lines.last.stripPrefix("normalized-sha256=")
    val actual = sha256Lines(body)
    require(
      expected == actual,
      s"surface manifest integrity mismatch: expected $expected, derived $actual"
    )
    require(
      body.distinct.size == body.size,
      "surface manifest contains duplicate records"
    )
    val keys = body.map(recordKey)
    require(keys.distinct.size == keys.size, "surface manifest contains duplicate record keys")
    body
  }

  def compare(expected: Vector[String], actual: Vector[String]): SurfaceDiff = {
    val expectedByKey = expected.map(line => recordKey(line) -> line).toMap
    val actualByKey = actual.map(line => recordKey(line) -> line).toMap
    val expectedKeys = expectedByKey.keySet
    val actualKeys = actualByKey.keySet
    val changed = (expectedKeys intersect actualKeys).toVector.sorted.flatMap { key =>
      val expectedLine = expectedByKey(key)
      val actualLine = actualByKey(key)
      if (expectedLine == actualLine) None
      else Some((key, expectedLine, actualLine))
    }
    SurfaceDiff(
      (actualKeys -- expectedKeys).toVector.sorted.map(actualByKey),
      (expectedKeys -- actualKeys).toVector.sorted.map(expectedByKey),
      changed
    )
  }

  def validateClassification(records: Seq[String]): Vector[String] = {
    val classes = records.filter(_.startsWith("CLASS|")).map { record =>
      val fields = record.split("\\|", 4)
      fields(1) -> fields(2)
    }.toMap
    val errors = mutable.ArrayBuffer.empty[String]
    if (classes.get(MetadataCarrierEntry) != Some("METADATA_CARRIER"))
      errors += s"missing metadata carrier classification for $MetadataCarrierEntry"
    FixtureMarkerEntries.toVector.sorted.foreach { entry =>
      if (classes.get(entry) != Some("INTEGRATION_FIXTURE_MARKER"))
        errors += s"fixture marker category mismatch for $entry"
    }
    FixtureSupportEntries.toVector.sorted.foreach { entry =>
      if (classes.get(entry) != Some("INTEGRATION_FIXTURE_SUPPORT"))
        errors += s"fixture support category mismatch for $entry"
    }
    classes.foreach {
      case (entry, "HANDLER_CONTRACT")
          if !entry.startsWith("paradise3/api/") || entry == MetadataCarrierEntry =>
        errors += s"handler contract category leaked to $entry"
      case (entry, "INTEGRATION_FIXTURE_MARKER")
          if !FixtureMarkerEntries.contains(entry) =>
        errors += s"unexpected fixture marker classification for $entry"
      case _ => ()
    }
    errors.toVector
  }

  def entryPolicyErrors(entries: Seq[String]): Vector[String] = {
    val errors = mutable.ArrayBuffer.empty[String]
    entries.foreach { entry =>
      ForbiddenPrefixes.find(entry.startsWith).foreach(prefix =>
        errors += s"forbidden packaged ownership `$prefix` in `$entry`"
      )
      val allowed =
        entry == "META-INF/MANIFEST.MF" ||
          entry == "paradise3/" ||
          entry == "paradise3/api/" ||
          entry == "paradise3/api/helpers/" ||
          entry.endsWith(".tasty") && entry.startsWith("paradise3/") ||
          entry.endsWith(".class") && (
            entry.startsWith("paradise3/api/") ||
              FixtureMarkerEntries.contains(entry) ||
              FixtureSupportEntries.contains(entry)
          )
      if (!allowed)
        errors += s"unexpected pluginApi packaged entry `$entry`"
    }
    errors.toVector.distinct
  }

  def normalizePresentation(
      value: String,
      absolutePaths: Seq[String]
  ): String = {
    val pathNormalized = absolutePaths
      .filter(_.nonEmpty)
      .sortBy(path => -path.length)
      .foldLeft(value)((current, path) => current.replace(path, "<ABSOLUTE_PATH>"))
    if (pathNormalized.toLowerCase.startsWith("javap ")) "javap <TOOL_BANNER>"
    else pathNormalized
  }

  def sha256Lines(lines: Seq[String]): String =
    sha256Bytes(lines.mkString("", "\n", "\n").getBytes(StandardCharsets.UTF_8))

  private def validateExactBuild(config: Config): Unit = {
    require(
      config.scalaVersion == ExpectedScalaVersion,
      s"surface baseline requires Scala $ExpectedScalaVersion, found ${config.scalaVersion}"
    )
    require(
      config.sbtVersion == ExpectedSbtVersion,
      s"surface baseline requires sbt $ExpectedSbtVersion, found ${config.sbtVersion}"
    )
    require(
      config.projectVersion == ExpectedProjectVersion,
      s"surface baseline requires project version $ExpectedProjectVersion, found ${config.projectVersion}"
    )
    val feature = Runtime.version().feature()
    require(feature == 25, s"surface baseline requires JDK 25, found feature $feature")
  }

  private def classCategory(entry: String): String =
    if (entry == MetadataCarrierEntry) "METADATA_CARRIER"
    else if (entry.startsWith("paradise3/api/")) "HANDLER_CONTRACT"
    else if (FixtureMarkerEntries.contains(entry)) "INTEGRATION_FIXTURE_MARKER"
    else if (FixtureSupportEntries.contains(entry)) "INTEGRATION_FIXTURE_SUPPORT"
    else throw new IllegalStateException(s"unclassified pluginApi class `$entry`")

  private def metadataCarrierRecord(artifact: File): String = {
    val loader = new URLClassLoader(Array(artifact.toURI.toURL), null)
    try {
      val carrier = Class.forName("paradise3.api.expander", false, loader)
      val retention = carrier.getAnnotation(classOf[java.lang.annotation.Retention])
      val target = carrier.getAnnotation(classOf[java.lang.annotation.Target])
      require(retention != null, "expander metadata carrier has no Retention")
      require(target != null, "expander metadata carrier has no Target")
      val methods = carrier.getDeclaredMethods.toVector
      require(
        methods.size == 1 &&
          methods.head.getName == "value" &&
          methods.head.getParameterCount == 0 &&
          methods.head.getReturnType == classOf[String],
        s"unexpected expander metadata members: ${methods.toList}"
      )
      val targets = target.value().map(_.name()).sorted.mkString(",")
      s"METADATA|$MetadataCarrierEntry|retention=${retention.value().name()}|targets=$targets|member=value|descriptor=()Ljava/lang/String;"
    } finally loader.close()
  }

  private def javapOutput(artifact: File, entry: String): String = {
    val output = new StringBuilder
    val command = Seq(
      javaTool("javap"),
      "-classpath",
      artifact.getAbsolutePath,
      "-protected",
      "-s",
      "-constants",
      binaryName(entry)
    )
    val exit = Process(command).!(ProcessLogger(
      line => output.append(line).append('\n'),
      line => output.append(line).append('\n')
    ))
    require(exit == 0, s"javap failed for $entry:\n${output.result()}")
    output.result()
  }

  private def classDeclaration(javap: String, binaryClassName: String): String =
    javap
      .split("\\r?\\n", -1)
      .map(_.trim)
      .find(line =>
        (line.startsWith("public ") || line.startsWith("protected ")) &&
          line.contains(binaryClassName) && line.endsWith("{")
      )
      .getOrElse(
        throw new IllegalStateException(
          s"javap output has no public/protected declaration for $binaryClassName"
        )
      )
      .stripSuffix("{")
      .trim

  private def memberKindAndName(
      declaration: String,
      binaryClassName: String
  ): (String, String) = {
    if (declaration.contains("{}")) ("STATIC_INITIALIZER", "<clinit>")
    else if (declaration.contains("(")) {
      val prefix = declaration.takeWhile(_ != '(').trim
      val name = prefix.split("\\s+").last
      if (name == binaryClassName) ("CONSTRUCTOR", "<init>")
      else ("METHOD", name)
    } else {
      val beforeValue = declaration.takeWhile(_ != '=').stripSuffix(";").trim
      ("FIELD", beforeValue.split("\\s+").last)
    }
  }

  private def memberModifiers(declaration: String, visibility: String): String = {
    val tokens = declaration
      .replace('(', ' ')
      .replace(')', ' ')
      .replace(';', ' ')
      .split("\\s+")
      .toSet
    val known = Vector(
      "static",
      "final",
      "abstract",
      "default",
      "synchronized",
      "native",
      "strictfp",
      "transient",
      "volatile"
    ).filter(tokens.contains)
    if (known.isEmpty) "-" else known.mkString(",")
  }

  private def recordKey(line: String): String = {
    val fields = line.split("\\|", -1)
    fields.headOption.getOrElse("") match {
      case "CLASS" if fields.length >= 2 => s"CLASS|${fields(1)}"
      case "MEMBER" if fields.length >= 5 =>
        s"MEMBER|${fields(1)}|${fields(2)}|${fields(3)}|${fields(4)}"
      case "METADATA" if fields.length >= 2 => s"METADATA|${fields(1)}"
      case "RESOURCE" if fields.length >= 2 => s"RESOURCE|${fields(1)}"
      case "POLICY" if fields.length >= 2 => s"POLICY|${fields(1)}"
      case _ if line.contains("=") => line.takeWhile(_ != '=')
      case _ => line
    }
  }

  private def binaryName(entry: String): String =
    entry.stripSuffix(".class").replace('/', '.')

  private def escape(value: String): String =
    value.replace("|", "\\u007c")

  private def jarEntries(artifact: File): Vector[String] = {
    val jar = new JarFile(artifact)
    try jar.entries().asScala.map(_.getName).toVector.sorted
    finally jar.close()
  }

  private def regularRelativeFiles(root: File): Vector[String] = {
    if (!root.exists()) Vector.empty
    else {
      val stream = Files.walk(root.toPath)
      try stream.iterator().asScala
        .filter(Files.isRegularFile(_))
        .map(path => root.toPath.relativize(path).toString.replace(File.separatorChar, '/'))
        .toVector
        .sorted
      finally stream.close()
    }
  }

  private def runProcess(
      command: Seq[String],
      workingDirectory: File,
      logFile: File
  ): Int = {
    Files.createDirectories(logFile.toPath.getParent)
    val output = new StringBuilder
    val exit = Process(command, workingDirectory).!(ProcessLogger(
      line => output.append(line).append('\n'),
      line => output.append(line).append('\n')
    ))
    val rendered =
      "COMMAND\n" + command.mkString("\n") + "\nOUTPUT\n" + output.result()
    Files.write(logFile.toPath, rendered.getBytes(StandardCharsets.UTF_8))
    exit
  }

  private def classpath(files: Seq[File]): String =
    files.map(_.getAbsolutePath).distinct.mkString(File.pathSeparator)

  private def javaTool(name: String): String =
    new File(new File(System.getProperty("java.home"), "bin"), name).getAbsolutePath

  private def isWithin(root: File, candidate: File): Boolean =
    candidate.toPath.toAbsolutePath.normalize
      .startsWith(root.toPath.toAbsolutePath.normalize)

  private def recreateDirectory(path: Path): Unit = {
    deleteRecursively(path)
    Files.createDirectories(path)
  }

  private def deleteRecursively(path: Path): Unit = {
    if (Files.exists(path)) {
      val stream = Files.walk(path)
      try stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.delete)
      finally stream.close()
    }
  }

  private def readLines(path: Path): Vector[String] =
    Files.readAllLines(path, StandardCharsets.UTF_8).asScala.toVector

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def writeLines(path: Path, lines: Seq[String]): Unit = {
    Files.createDirectories(path.getParent)
    Files.write(
      path,
      lines.mkString("", "\n", "\n").getBytes(StandardCharsets.UTF_8)
    )
  }

  private def sha256File(file: File): String =
    sha256Bytes(Files.readAllBytes(file.toPath))

  private def sha256Bytes(bytes: Array[Byte]): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .map(byte => f"${byte & 0xff}%02x")
      .mkString
}
