import java.io.{ByteArrayOutputStream, File}
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardCopyOption}
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.jar.JarFile
import java.util.zip.{CRC32, ZipEntry, ZipOutputStream}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.sys.process.{Process, ProcessLogger}

object ExperimentalHandlerContractArtifact {
  val FeasibleClassification =
    "EXPERIMENTAL_HANDLER_CONTRACT_ONLY_ARTIFACT_FEASIBLE"
  val AllHandlersClassification =
    "ALL_CURRENT_PRECOMPILED_HANDLERS_COMPILE_AND_LINK_AGAINST_CONTRACT_ONLY_ARTIFACT"
  val SourceBuiltMatchClassification =
    "SOURCE_BUILT_PLUGIN_API_MATCHES_FILTERED_CONTRACT_CANDIDATE"
  val CandidateBasename =
    "macroparadise-scala3-plugin-api-handler-contract_3-0.1.0-SNAPSHOT.jar"
  val DeterministicTimestamp = LocalDateTime.of(1980, 1, 1, 0, 0)
  val DeterministicManifest =
    "Manifest-Version: 1.0\r\n" +
      "Created-By: macroparadise-scala3 deterministic handler-contract verifier\r\n" +
      "\r\n"
  val AllowedCategories = Set(
    "HANDLER_CONTRACT",
    "METADATA_CARRIER",
    "INTEGRATION_FIXTURE_MARKER",
    "INTEGRATION_FIXTURE_SUPPORT"
  )
  val ForbiddenPrefixes = Vector(
    "macroparadise/",
    "demo/",
    "quasiquotes/",
    "dotty/",
    "scala/",
    "scala3/",
    "tasty/"
  )
  val ExpectedMetadataRecord =
    "METADATA|paradise3/api/expander.class|retention=RUNTIME|targets=ANNOTATION_TYPE,TYPE|member=value|descriptor=()Ljava/lang/String;"

  final case class Config(
      scalaVersion: String,
      sbtVersion: String,
      projectVersion: String
  )

  final case class ContractPlan(
      handlerClasses: Vector[String],
      metadataCarrier: String,
      fixtureMarkers: Vector[String],
      fixtureSupport: Vector[String],
      requiredTasty: Vector[String],
      fixtureTasty: Vector[String],
      allowedEntries: Vector[String],
      metadataRecord: String
  ) {
    def includedClassCount: Int = handlerClasses.size + 1
    def excludedClassCount: Int = fixtureMarkers.size + fixtureSupport.size
  }

  final case class EntryDiff(added: Vector[String], removed: Vector[String]) {
    def isEmpty: Boolean = added.isEmpty && removed.isEmpty
    def render: String =
      (removed.map(value => s"REMOVED $value") ++
        added.map(value => s"ADDED $value")).mkString("\n")
  }

  final case class ArtifactIdentity(
      path: File,
      bytes: Long,
      entries: Int,
      classCount: Int,
      tastyCount: Int,
      sha256: String,
      handlerContractClasses: Int,
      metadataCarriers: Int,
      excludedFixtureMarkers: Int,
      excludedFixtureSupport: Int
  ) {
    def render: String =
      s"path=${path.getAbsolutePath} bytes=$bytes entries=$entries " +
        s"classes=$classCount tasty=$tastyCount sha256=$sha256 " +
        s"handlerContractClasses=$handlerContractClasses metadataCarriers=$metadataCarriers " +
        s"excludedFixtureMarkers=$excludedFixtureMarkers " +
        s"excludedFixtureSupport=$excludedFixtureSupport"
  }

  final case class CompileEvidence(
      sourceCount: Int,
      firstExit: Int,
      secondExit: Int,
      outputFiles: Vector[String],
      classCount: Int,
      tastyCount: Int,
      handlerImplementationClasses: Vector[String]
  ) {
    def render: String =
      s"sources=$sourceCount firstExit=$firstExit secondExit=$secondExit " +
        s"outputFiles=${outputFiles.size} classes=$classCount tasty=$tastyCount " +
        s"handlerImplementations=${handlerImplementationClasses.size}"
  }

  final case class RuntimeEvidence(
      linkedHandlers: Vector[String],
      safelyInstantiated: Vector[String],
      loadOnly: Vector[String],
      independentMetadataValue: String,
      independentHandlerLinked: Boolean,
      apiIdentityShared: Boolean,
      candidateAbsentAtRuntime: Boolean
  ) {
    def render: String =
      s"linkedHandlers=${linkedHandlers.size} safelyInstantiated=${safelyInstantiated.size} " +
        s"loadOnly=${loadOnly.size} independentMetadata=$independentMetadataValue " +
        s"independentHandlerLinked=$independentHandlerLinked " +
        s"apiIdentityShared=$apiIdentityShared candidateAbsentAtRuntime=$candidateAbsentAtRuntime"
  }

  final case class NegativeEvidence(
      id: String,
      exitCode: Int,
      expectedFragment: String,
      outputFiles: Int
  ) {
    def render: String =
      s"$id(exit=$exitCode expected=$expectedFragment outputFiles=$outputFiles)"
  }

  final case class VerificationResult(
      classification: String,
      evidenceClassification: String,
      fullArtifact: ArtifactIdentity,
      markerArtifact: ArtifactIdentity,
      candidateArtifact: ArtifactIdentity,
      deterministicSecondRenderEqual: Boolean,
      sourceBuiltEntriesAndBytesMatchCandidate: Boolean,
      fullSurfaceSha256: String,
      modelCases: Int,
      allHandlers: CompileEvidence,
      independentProbeFiles: Vector[String],
      runtime: RuntimeEvidence,
      negatives: Vector[NegativeEvidence],
      evidenceDirectory: File
  ) {
    def render: String =
      s"classification=$classification evidenceClassification=$evidenceClassification " +
        s"sourceBuiltClassification=$SourceBuiltMatchClassification " +
        s"candidate={${candidateArtifact.render}} contract={${fullArtifact.render}} marker={${markerArtifact.render}} " +
        s"deterministicSecondRenderEqual=$deterministicSecondRenderEqual " +
        s"sourceBuiltEntriesAndBytesMatchCandidate=$sourceBuiltEntriesAndBytesMatchCandidate " +
        s"fullSurfaceSha256=$fullSurfaceSha256 modelCases=$modelCases " +
        s"allHandlers={${allHandlers.render}} independentProbeFiles=${independentProbeFiles.size} " +
        s"runtime={${runtime.render}} negatives=${negatives.map(_.render).mkString(",")}"
  }

  def readManifest(file: File): Vector[String] =
    Files.readAllLines(file.toPath, StandardCharsets.UTF_8).asScala.toVector

  def parsePlan(lines: Vector[String]): ContractPlan = {
    val body = ExperimentalPluginApiSurface.parseManifest(lines)
    val classRecords = body.filter(_.startsWith("CLASS|"))
    val parsed = classRecords.map { record =>
      val fields = record.split("\\|", 4)
      require(fields.length == 4, s"malformed class/category record `$record`")
      require(fields(1).endsWith(".class"), s"malformed class entry `${fields(1)}`")
      require(
        AllowedCategories.contains(fields(2)),
        s"unknown surface category `${fields(2)}` for `${fields(1)}`"
      )
      fields(1) -> fields(2)
    }
    val duplicateClasses = parsed.groupBy(_._1).collect {
      case (entry, values) if values.size > 1 => entry
    }.toVector.sorted
    require(
      duplicateClasses.isEmpty,
      s"duplicate class/category records: ${duplicateClasses.mkString(", ")}"
    )

    def classes(category: String): Vector[String] =
      parsed.collect { case (entry, `category`) => entry }.sorted

    val handlers = classes("HANDLER_CONTRACT")
    val metadata = classes("METADATA_CARRIER")
    val markers = classes("INTEGRATION_FIXTURE_MARKER")
    val support = classes("INTEGRATION_FIXTURE_SUPPORT")
    require(handlers.nonEmpty, "manifest has no HANDLER_CONTRACT class")
    require(
      metadata.size == 1,
      s"manifest must contain exactly one METADATA_CARRIER, found ${metadata.size}"
    )
    require(
      handlers.forall(_.startsWith("paradise3/api/")),
      s"handler contract escaped paradise3/api: ${handlers.filterNot(_.startsWith("paradise3/api/")).mkString(", ")}"
    )
    require(
      metadata.head == ExperimentalPluginApiSurface.MetadataCarrierEntry,
      s"unexpected metadata carrier `${metadata.head}`"
    )

    val resourceRecords = body.filter(_.startsWith("RESOURCE|"))
    val resources = resourceRecords.map { record =>
      val fields = record.split("\\|", -1)
      require(fields.length == 3, s"malformed resource record `$record`")
      fields(1) -> fields(2)
    }
    val tastyResources = resources.collect {
      case (entry, "SCALA_TASTY") => entry
    }.toSet
    val unsupportedResources = resources.collect {
      case (entry, category)
          if !(entry == "META-INF/MANIFEST.MF" && category == "STANDARD_JAR_METADATA") &&
            category != "SCALA_TASTY" =>
        s"$entry:$category"
    }
    require(
      unsupportedResources.isEmpty,
      s"unknown resource categories: ${unsupportedResources.mkString(", ")}"
    )

    val requiredTasty = handlers.map(classToTasty).distinct.sorted
    val missingTasty = requiredTasty.filterNot(tastyResources)
    require(
      missingTasty.isEmpty,
      s"required included TASTy is missing from manifest: ${missingTasty.mkString(", ")}"
    )
    val metadataRecords = body.filter(_.startsWith("METADATA|"))
    require(
      metadataRecords == Vector(ExpectedMetadataRecord),
      s"runtime expander metadata evidence changed: ${metadataRecords.mkString(", ")}"
    )

    val allowed =
      (Vector("META-INF/MANIFEST.MF") ++ handlers ++ metadata ++ requiredTasty).sorted
    val fixtureTasty = (tastyResources -- requiredTasty.toSet).toVector.sorted
    ContractPlan(
      handlers,
      metadata.head,
      markers,
      support,
      requiredTasty,
      fixtureTasty,
      allowed,
      metadataRecords.head
    )
  }

  def validateSourceArtifacts(
      plan: ContractPlan,
      contractEntries: Vector[String],
      markerEntries: Vector[String]
  ): Unit = {
    val contractFiles = contractEntries.filterNot(_.endsWith("/")).toSet
    val expectedContract = plan.allowedEntries.toSet
    require(
      contractFiles == expectedContract,
      s"source-built pluginApi inventory differs from filtered contract candidate: ${entryDiff(expectedContract.toVector, contractFiles.toVector).render}"
    )
    val markerFiles = markerEntries.filterNot(_.endsWith("/")).toSet
    val expectedMarkers =
      (Vector("META-INF/MANIFEST.MF") ++ plan.fixtureMarkers ++
        plan.fixtureSupport ++ plan.fixtureTasty).toSet
    require(
      markerFiles == expectedMarkers,
      s"source-built pluginTestMarkers inventory differs from fixture manifest: ${entryDiff(expectedMarkers.toVector, markerFiles.toVector).render}"
    )
  }

  def validateCandidateEntries(
      plan: ContractPlan,
      entries: Seq[String]
  ): Vector[String] = {
    val actual = entries.toVector
    val actualSet = actual.toSet
    val expectedSet = plan.allowedEntries.toSet
    val errors = mutable.ArrayBuffer.empty[String]
    if (actual.distinct.size != actual.size)
      errors += "candidate contains duplicate entries"
    val missing = (expectedSet -- actualSet).toVector.sorted
    val unexpected = (actualSet -- expectedSet).toVector.sorted
    if (missing.nonEmpty)
      errors += s"missing required entries: ${missing.mkString(", ")}"
    if (unexpected.nonEmpty)
      errors += s"unexpected entries: ${unexpected.mkString(", ")}"
    val retainedMarkers = plan.fixtureMarkers.filter(actualSet)
    if (retainedMarkers.nonEmpty)
      errors += s"accidentally retained fixture marker: ${retainedMarkers.mkString(", ")}"
    val retainedSupport = plan.fixtureSupport.filter(actualSet)
    if (retainedSupport.nonEmpty)
      errors += s"accidentally retained fixture support: ${retainedSupport.mkString(", ")}"
    val forbidden = actual.filter(entry => ForbiddenPrefixes.exists(entry.startsWith))
    if (forbidden.nonEmpty)
      errors += s"forbidden implementation/dependency package: ${forbidden.distinct.sorted.mkString(", ")}"
    val candidateTasty = actual.filter(_.endsWith(".tasty")).toSet
    val missingTasty = plan.requiredTasty.toSet -- candidateTasty
    if (missingTasty.nonEmpty)
      errors += s"missing required TASTy: ${missingTasty.toVector.sorted.mkString(", ")}"
    val orphanTasty = candidateTasty -- plan.requiredTasty.toSet
    if (orphanTasty.nonEmpty)
      errors += s"orphan TASTy: ${orphanTasty.toVector.sorted.mkString(", ")}"
    errors.toVector.distinct
  }

  def entryDiff(expected: Seq[String], actual: Seq[String]): EntryDiff = {
    val expectedSet = expected.toSet
    val actualSet = actual.toSet
    EntryDiff(
      (actualSet -- expectedSet).toVector.sorted,
      (expectedSet -- actualSet).toVector.sorted
    )
  }

  def render(
      contractArtifact: File,
      markerArtifact: File,
      baselineFile: File,
      destination: File,
      config: Config
  ): ArtifactIdentity = {
    validateExactBuild(config)
    val surface = verifyFullSurface(contractArtifact, markerArtifact, baselineFile, config)
    val plan = parsePlan(readManifest(baselineFile))
    validateSourceArtifacts(plan, jarEntries(contractArtifact), jarEntries(markerArtifact))
    val sourceBytes = readJarBytes(contractArtifact, plan.allowedEntries)
    writeDeterministicJar(destination, sourceBytes)
    val identity = candidateIdentity(destination, plan)
    require(
      surface.normalizedSha256 ==
        "6194ab649847ed6ff42344c8eb054443f7ead655cfa50f1da9ca4e3aacb31a84",
      s"reviewed normalized surface SHA changed to ${surface.normalizedSha256}"
    )
    identity
  }

  def verify(
      repositoryRoot: File,
      contractArtifact: File,
      markerArtifact: File,
      dependencyClasspath: Seq[File],
      baselineFile: File,
      handlerSourceRoot: File,
      independentProbeSource: File,
      negativeSources: Vector[(String, File, String)],
      destination: File,
      evidenceDirectory: File,
      config: Config,
      modelCases: Int
  ): VerificationResult = {
    validateExactBuild(config)
    recreateDirectory(evidenceDirectory.toPath)
    val surface = verifyFullSurface(contractArtifact, markerArtifact, baselineFile, config)
    val plan = parsePlan(readManifest(baselineFile))
    val contractEntries = jarEntries(contractArtifact)
    val markerEntries = jarEntries(markerArtifact)
    validateSourceArtifacts(plan, contractEntries, markerEntries)

    val renderOne = new File(evidenceDirectory, "render-one.jar")
    val renderTwo = new File(evidenceDirectory, "render-two.jar")
    val first = render(contractArtifact, markerArtifact, baselineFile, renderOne, config)
    val second = render(contractArtifact, markerArtifact, baselineFile, renderTwo, config)
    val firstBytes = Files.readAllBytes(renderOne.toPath)
    val secondBytes = Files.readAllBytes(renderTwo.toPath)
    val deterministic = java.util.Arrays.equals(firstBytes, secondBytes)
    require(
      deterministic && first.sha256 == second.sha256,
      s"candidate double rendering is not byte-identical: ${first.sha256} != ${second.sha256}"
    )
    Files.createDirectories(destination.toPath.getParent)
    Files.copy(renderOne.toPath, destination.toPath, StandardCopyOption.REPLACE_EXISTING)
    val candidate = candidateIdentity(destination, plan)
    require(
      candidate.sha256 == first.sha256 && candidate.bytes == first.bytes,
      "final candidate identity differs from deterministic evidence render"
    )
    val sourceBytes = readJarBytes(contractArtifact, plan.allowedEntries)
    val candidateBytes = readJarBytes(candidate.path, plan.allowedEntries)
    val sourceBuiltMatches =
      sourceBytes.keySet == candidateBytes.keySet && sourceBytes.forall {
        case (entry, bytes) => java.util.Arrays.equals(bytes, candidateBytes(entry))
      }
    require(
      sourceBuiltMatches,
      "source-built pluginApi entry bytes differ from freshly rendered candidate"
    )

    val compilerJars = compilerClasspath(repositoryRoot, dependencyClasspath, config)
    val compileEvidence = compileAllHandlers(
      repositoryRoot,
      handlerSourceRoot,
      candidate.path,
      compilerJars,
      new File(evidenceDirectory, "all-handlers")
    )
    val probeOutput = new File(evidenceDirectory, "independent-probe/classes")
    recreateDirectory(probeOutput.toPath)
    val probeExit = compile(
      repositoryRoot,
      Vector(independentProbeSource),
      probeOutput,
      candidate.path,
      compilerJars,
      new File(evidenceDirectory, "independent-probe/compile.log")
    )
    require(probeExit == 0, s"independent marker/handler probe failed with exit $probeExit")
    val probeFiles = regularRelativeFiles(probeOutput)
    require(
      probeFiles.contains("contractprobe/IndependentMarker.class") &&
        probeFiles.contains("contractprobe/IndependentHandler.class"),
      s"independent probe emitted unexpected inventory: ${probeFiles.mkString(", ")}"
    )
    require(
      !probeFiles.exists(isForbiddenOutput),
      s"independent probe copied forbidden classes: ${probeFiles.filter(isForbiddenOutput).mkString(", ")}"
    )

    val negatives = negativeSources.map {
      case (id, source, expectedFragment) =>
        val output = new File(evidenceDirectory, s"negative-$id/classes")
        recreateDirectory(output.toPath)
        val log = new File(evidenceDirectory, s"negative-$id/compile.log")
        val exit = compile(
          repositoryRoot,
          Vector(source),
          output,
          candidate.path,
          compilerJars,
          log
        )
        val logText = read(log.toPath)
        val outputs = regularRelativeFiles(output)
        require(exit != 0, s"negative probe $id unexpectedly compiled")
        require(
          logText.contains(expectedFragment),
          s"negative probe $id lacked expected `$expectedFragment` evidence: $logText"
        )
        require(outputs.isEmpty, s"negative probe $id emitted output: ${outputs.mkString(", ")}")
        NegativeEvidence(id, exit, expectedFragment, outputs.size)
    }

    val runtime = verifyRuntimeLinkage(
      contractArtifact,
      candidate.path,
      compilerJars,
      new File(evidenceDirectory, "all-handlers/first-classes"),
      probeOutput
    )
    verifyMetadataCarrier(candidate.path)

    val fullIdentity = artifactIdentity(
      contractArtifact,
      handlerContractClasses = surface.handlerContractClassCount,
      metadataCarriers = surface.metadataCarrierCount,
      excludedFixtureMarkers = 0,
      excludedFixtureSupport = 0
    )
    val markerIdentity = artifactIdentity(
      markerArtifact,
      handlerContractClasses = 0,
      metadataCarriers = 0,
      excludedFixtureMarkers = 0,
      excludedFixtureSupport = 0
    )
    val result = VerificationResult(
      FeasibleClassification,
      AllHandlersClassification,
      fullIdentity,
      markerIdentity,
      candidate,
      deterministic,
      sourceBuiltMatches,
      surface.normalizedSha256,
      modelCases,
      compileEvidence,
      probeFiles,
      runtime,
      negatives,
      evidenceDirectory
    )
    write(
      new File(evidenceDirectory, "summary.txt").toPath,
      result.render + "\n"
    )
    result
  }

  private def verifyFullSurface(
      contractArtifact: File,
      markerArtifact: File,
      baselineFile: File,
      config: Config
  ): ExperimentalPluginApiSurface.Surface = {
    val surface = ExperimentalPluginApiSurface.deriveCombinedSurface(
      contractArtifact,
      markerArtifact,
      ExperimentalPluginApiSurface.Config(
        config.scalaVersion,
        config.sbtVersion,
        config.projectVersion
      ),
      None
    )
    val expected = ExperimentalPluginApiSurface.parseManifest(readManifest(baselineFile))
    val actual = ExperimentalPluginApiSurface.parseManifest(surface.manifestLines)
    val drift = ExperimentalPluginApiSurface.compare(expected, actual)
    require(drift.isEmpty, s"full pluginApi no longer matches the experimental API baseline:\n${drift.render}")
    surface
  }

  private def compileAllHandlers(
      repositoryRoot: File,
      sourceRoot: File,
      candidate: File,
      compilerJars: Vector[File],
      evidenceDirectory: File
  ): CompileEvidence = {
    recreateDirectory(evidenceDirectory.toPath)
    val sources = regularFiles(sourceRoot).filter(_.getName.endsWith(".scala"))
    require(sources.nonEmpty, s"handler source root is empty: ${sourceRoot.getAbsolutePath}")
    val firstOutput = new File(evidenceDirectory, "first-classes")
    val secondOutput = new File(evidenceDirectory, "second-classes")
    Seq(firstOutput, secondOutput).foreach(file => recreateDirectory(file.toPath))
    val firstExit = compile(
      repositoryRoot,
      sources,
      firstOutput,
      candidate,
      compilerJars,
      new File(evidenceDirectory, "first-compile.log")
    )
    val secondExit = compile(
      repositoryRoot,
      sources,
      secondOutput,
      candidate,
      compilerJars,
      new File(evidenceDirectory, "second-compile.log")
    )
    require(firstExit == 0 && secondExit == 0, s"all-handler compile exits were $firstExit and $secondExit")
    val firstFiles = regularRelativeFiles(firstOutput)
    val secondFiles = regularRelativeFiles(secondOutput)
    require(firstFiles.nonEmpty, "all-handler compile emitted no files")
    val diff = entryDiff(firstFiles, secondFiles)
    require(diff.isEmpty, s"clean all-handler output inventory drifted:\n${diff.render}")
    val forbidden = firstFiles.filter(isForbiddenOutput)
    require(forbidden.isEmpty, s"all-handler compile copied forbidden classes: ${forbidden.mkString(", ")}")
    Vector(
      "demo/ExternalDebugExpander.class",
      "demo/ExternalCompanionDebugExpander.class",
      "demo/ExternalTypedLabelExpander.class",
      "demo/ThrowingExpander.class"
    ).foreach(entry => require(firstFiles.contains(entry), s"missing expected handler output $entry"))

    val handlerImplementations = discoverHandlerImplementations(
      firstOutput,
      candidate,
      compilerJars
    )
    require(handlerImplementations.nonEmpty, "compiled output contains no ParadiseAnnotationExpander implementations")
    CompileEvidence(
      sources.size,
      firstExit,
      secondExit,
      firstFiles,
      firstFiles.count(_.endsWith(".class")),
      firstFiles.count(_.endsWith(".tasty")),
      handlerImplementations
    )
  }

  private def compile(
      repositoryRoot: File,
      sources: Vector[File],
      output: File,
      candidate: File,
      compilerJars: Vector[File],
      log: File
  ): Int = {
    val command = Vector(
      javaTool("java"),
      "-cp",
      classpath(compilerJars),
      "dotty.tools.dotc.Main",
      "-classpath",
      classpath(candidate +: compilerJars),
      "-d",
      output.getAbsolutePath
    ) ++ sources.map(_.getAbsolutePath)
    runProcess(command, repositoryRoot, log)
  }

  private def discoverHandlerImplementations(
      output: File,
      candidate: File,
      compilerJars: Vector[File]
  ): Vector[String] = {
    val parent = new URLClassLoader((candidate +: compilerJars).map(_.toURI.toURL).toArray, null)
    val child = new URLClassLoader(Array(output.toURI.toURL), parent)
    try {
      val api = Class.forName("paradise3.api.ParadiseAnnotationExpander", false, parent)
      classNames(output).filter { name =>
        val value = Class.forName(name, false, child)
        api.isAssignableFrom(value) && value != api && !value.isInterface
      }
    } finally {
      child.close()
      parent.close()
    }
  }

  private def verifyRuntimeLinkage(
      contractArtifact: File,
      candidate: File,
      compilerJars: Vector[File],
      handlerOutput: File,
      probeOutput: File
  ): RuntimeEvidence = {
    val parentUrls = (contractArtifact +: compilerJars).map(_.toURI.toURL).toArray
    val childUrls = Array(handlerOutput.toURI.toURL, probeOutput.toURI.toURL)
    require(
      !parentUrls.contains(candidate.toURI.toURL) && !childUrls.contains(candidate.toURI.toURL),
      "candidate contract JAR leaked onto runtime loader URLs"
    )
    val parent = new URLClassLoader(parentUrls, null)
    val child = new URLClassLoader(childUrls, parent)
    try {
      val api = Class.forName("paradise3.api.ParadiseAnnotationExpander", false, parent)
      val linked = classNames(handlerOutput).filter { name =>
        val value = Class.forName(name, false, child)
        api.isAssignableFrom(value) && value != api && !value.isInterface
      }
      require(linked.nonEmpty, "runtime loader found no handler implementations")
      linked.foreach { name =>
        val value = Class.forName(name, false, child)
        require(api.isAssignableFrom(value), s"$name does not share the parent API identity")
      }
      require(linked.contains("demo.ThrowingExpander"), "hostile constructor handler was not load-only linked")

      val safeExpected = Vector(
        ("demo.ExternalDebugExpander", "externalDebug", false),
        ("demo.ExternalCompanionDebugExpander", "externalCompanionDebug", true)
      )
      safeExpected.foreach {
        case (name, annotationName, consumes) =>
          val value = Class.forName(name, false, child).getDeclaredConstructor().newInstance()
          val actualName = value.getClass.getMethod("annotationName").invoke(value)
          val actualConsumes = value.getClass.getMethod("consumesExistingCompanion").invoke(value)
          require(actualName == annotationName, s"$name annotationName was $actualName")
          require(actualConsumes == java.lang.Boolean.valueOf(consumes), s"$name consumesExistingCompanion was $actualConsumes")
      }

      val carrier = Class.forName("paradise3.api.expander", false, parent)
      val marker = Class.forName("contractprobe.IndependentMarker", false, child)
      val annotation = marker.getAnnotations.toVector.find(_.annotationType() == carrier).getOrElse {
        throw new IllegalStateException("independent marker has no parent expander metadata")
      }
      val metadataValue = carrier.getMethod("value").invoke(annotation).toString
      require(
        metadataValue == "contractprobe.IndependentHandler",
        s"independent marker metadata value was `$metadataValue`"
      )
      val independent = Class.forName("contractprobe.IndependentHandler", false, child)
      require(api.isAssignableFrom(independent), "independent handler does not share parent API identity")
      val independentValue = independent.getDeclaredConstructor().newInstance()
      require(
        independent.getMethod("annotationName").invoke(independentValue) == "IndependentMarker",
        "independent handler annotationName changed"
      )
      require(
        api.getProtectionDomain.getCodeSource.getLocation.toURI == contractArtifact.toURI,
        "runtime API identity did not come from the source-built pluginApi contract JAR"
      )
      RuntimeEvidence(
        linked,
        safeExpected.map(_._1) :+ "contractprobe.IndependentHandler",
        linked.filterNot(name => safeExpected.exists(_._1 == name)),
        metadataValue,
        independentHandlerLinked = true,
        apiIdentityShared = true,
        candidateAbsentAtRuntime = true
      )
    } finally {
      child.close()
      parent.close()
    }
  }

  private def verifyMetadataCarrier(candidate: File): Unit = {
    val loader = new URLClassLoader(Array(candidate.toURI.toURL), null)
    try {
      val carrier = Class.forName("paradise3.api.expander", false, loader)
      val retention = carrier.getAnnotation(classOf[java.lang.annotation.Retention])
      val target = carrier.getAnnotation(classOf[java.lang.annotation.Target])
      require(retention != null && retention.value().name() == "RUNTIME", "candidate expander retention changed")
      require(
        target != null && target.value().map(_.name()).sorted.toVector == Vector("ANNOTATION_TYPE", "TYPE"),
        "candidate expander targets changed"
      )
      val methods = carrier.getDeclaredMethods.toVector
      require(
        methods.size == 1 && methods.head.getName == "value" &&
          methods.head.getReturnType == classOf[String] && methods.head.getParameterCount == 0,
        "candidate expander member/descriptor changed"
      )
    } finally loader.close()
  }

  private def candidateIdentity(file: File, plan: ContractPlan): ArtifactIdentity = {
    val entries = jarEntries(file)
    val errors = validateCandidateEntries(plan, entries)
    require(errors.isEmpty, errors.mkString("; "))
    require(entries == entries.sorted, "candidate central-directory entry order is not lexical")
    ArtifactIdentity(
      file,
      file.length(),
      entries.size,
      entries.count(_.endsWith(".class")),
      entries.count(_.endsWith(".tasty")),
      sha256File(file),
      plan.handlerClasses.size,
      1,
      plan.fixtureMarkers.size,
      plan.fixtureSupport.size
    )
  }

  private def artifactIdentity(
      file: File,
      handlerContractClasses: Int,
      metadataCarriers: Int,
      excludedFixtureMarkers: Int,
      excludedFixtureSupport: Int
  ): ArtifactIdentity = {
    val entries = jarEntries(file)
    ArtifactIdentity(
      file,
      file.length(),
      entries.size,
      entries.count(_.endsWith(".class")),
      entries.count(_.endsWith(".tasty")),
      sha256File(file),
      handlerContractClasses,
      metadataCarriers,
      excludedFixtureMarkers,
      excludedFixtureSupport
    )
  }

  private def compilerClasspath(
      repositoryRoot: File,
      dependencyClasspath: Seq[File],
      config: Config
  ): Vector[File] = {
    val jars = dependencyClasspath
      .filter(file => file.isFile && file.getName.endsWith(".jar"))
      .map(_.getAbsoluteFile)
      .distinct
      .sortBy(_.getAbsolutePath)
      .toVector
    require(
      jars.exists(_.getName == s"scala3-compiler_3-${config.scalaVersion}.jar"),
      s"compiler universe is missing exact Scala ${config.scalaVersion}"
    )
    require(
      jars.forall(file => !isWithin(repositoryRoot, file)),
      s"compiler universe leaked repository outputs: ${jars.filter(isWithin(repositoryRoot, _)).mkString(", ")}"
    )
    jars
  }

  private def classToTasty(entry: String): String = {
    val withoutClass = entry.stripSuffix(".class")
    val dollar = withoutClass.indexOf('$')
    val sourceStem = if (dollar >= 0) withoutClass.substring(0, dollar) else withoutClass
    sourceStem + ".tasty"
  }

  private def readJarBytes(
      artifact: File,
      entries: Seq[String]
  ): Map[String, Array[Byte]] = {
    val jar = new JarFile(artifact)
    try entries.map { name =>
      val entry = Option(jar.getJarEntry(name)).getOrElse {
        throw new IllegalStateException(s"full pluginApi JAR is missing required entry `$name`")
      }
      val input = jar.getInputStream(entry)
      try name -> readAllBytes(input)
      finally input.close()
    }.toMap
    finally jar.close()
  }

  private def readAllBytes(input: java.io.InputStream): Array[Byte] = {
    val output = new ByteArrayOutputStream()
    val buffer = new Array[Byte](8192)
    var count = input.read(buffer)
    while (count >= 0) {
      if (count > 0) output.write(buffer, 0, count)
      count = input.read(buffer)
    }
    output.toByteArray
  }

  def writeDeterministicJar(
      destination: File,
      entries: Map[String, Array[Byte]]
  ): Unit = {
    Files.createDirectories(destination.toPath.getParent)
    val output = new ZipOutputStream(Files.newOutputStream(destination.toPath))
    try entries.toVector.sortBy(_._1).foreach {
      case (name, bytes) =>
        val crc = new CRC32()
        crc.update(bytes)
        val entry = new ZipEntry(name)
        entry.setMethod(ZipEntry.STORED)
        entry.setSize(bytes.length.toLong)
        entry.setCompressedSize(bytes.length.toLong)
        entry.setCrc(crc.getValue)
        entry.setTimeLocal(DeterministicTimestamp)
        output.putNextEntry(entry)
        output.write(bytes)
        output.closeEntry()
    }
    finally output.close()
  }

  private def jarEntries(artifact: File): Vector[String] = {
    val jar = new JarFile(artifact)
    try jar.entries().asScala.map(_.getName).toVector
    finally jar.close()
  }

  private def classNames(root: File): Vector[String] =
    regularRelativeFiles(root)
      .filter(name => name.endsWith(".class") && !name.endsWith("module-info.class"))
      .map(_.stripSuffix(".class").replace('/', '.'))

  private def isForbiddenOutput(entry: String): Boolean =
    entry.startsWith("paradise3/api/") ||
      entry.startsWith("paradise3/MetadataInitializationProbe") ||
      entry.startsWith("dotty/") ||
      entry.startsWith("scala/") ||
      entry.startsWith("macroparadise/")

  private def regularFiles(root: File): Vector[File] = {
    if (!root.exists()) Vector.empty
    else {
      val stream = Files.walk(root.toPath)
      try stream.iterator().asScala
        .filter(Files.isRegularFile(_))
        .map(_.toFile)
        .toVector
        .sortBy(_.getAbsolutePath)
      finally stream.close()
    }
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
    write(
      logFile.toPath,
      "COMMAND\n" + command.mkString("\n") + "\nOUTPUT\n" + output.result()
    )
    exit
  }

  private def validateExactBuild(config: Config): Unit = {
    require(
      config.scalaVersion == ExperimentalPluginApiSurface.ExpectedScalaVersion,
      s"candidate requires Scala ${ExperimentalPluginApiSurface.ExpectedScalaVersion}"
    )
    require(
      config.sbtVersion == ExperimentalPluginApiSurface.ExpectedSbtVersion,
      s"candidate requires sbt ${ExperimentalPluginApiSurface.ExpectedSbtVersion}"
    )
    require(
      config.projectVersion == ExperimentalPluginApiSurface.ExpectedProjectVersion,
      s"candidate requires project ${ExperimentalPluginApiSurface.ExpectedProjectVersion}"
    )
    require(Runtime.version().feature() == 25, "candidate requires JDK 25")
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

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def write(path: Path, value: String): Unit = {
    Files.createDirectories(path.getParent)
    Files.write(path, value.getBytes(StandardCharsets.UTF_8))
  }

  private def sha256File(file: File): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(Files.readAllBytes(file.toPath))
      .map(byte => f"${byte & 0xff}%02x")
      .mkString
}
