import java.io.{File, FileInputStream}
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{FileVisitResult, Files, Path, SimpleFileVisitor, StandardCopyOption}
import java.security.MessageDigest
import java.util.jar.JarFile
import java.util.zip.{ZipEntry, ZipFile, ZipOutputStream}
import javax.xml.parsers.DocumentBuilderFactory

import scala.collection.JavaConverters._
import scala.sys.process.{Process, ProcessLogger}

object IndependentExternalSbtConsumer {
  val StagingClassification = "TASK_OWNED_LOCAL_REPOSITORY_STAGING_READY"
  val ExternalBuildClassification = "INDEPENDENT_EXTERNAL_SBT_PRODUCER_AND_CONSUMER_READY"
  val CoordinateClassification = "COORDINATE_RESOLVED_METADATA_DISCOVERY_AND_HANDLER_INVOCATION_PROVEN"
  val PublicationClassification = "NO_GLOBAL_OR_REMOTE_PUBLICATION_PERFORMED"

  val RepositoryOrganization = "com.github.dmytromitin"
  val ProducerOrganization = "local.contractprobe.externalConsumer"
  val Version = "0.1.0"
  val ExpectedScalaVersion = "3.8.5-RC1-bin-20260405-9478256-NIGHTLY"
  val PluginApiModule = s"macroparadise-scala3-plugin-api_$ExpectedScalaVersion"
  val PluginModule = s"macroparadise-scala3-plugin_$ExpectedScalaVersion"
  val MarkerModule = "independent-marker_3"
  val HandlerModule = "independent-handler_3"
  val DuplicateApiModule = "duplicate-plugin-api_3"
  val ExpectedSbtVersion = "1.12.15"
  val ExpectedRuntimeOutput = "IndependentConsumerUser\n"
  val ExpectedHandler = "contractprobe.IndependentHandler"

  val forbiddenModuleFragments = Vector(
    "plugin-test-markers",
    "plugin-test-handlers",
    "plugin-tests",
    "legacy-metadata",
    "quasiquotes",
    "experimental-plugin-api-handler-contract"
  )

  val expectedMarkerPayload = Set(
    "contractprobe/IndependentMarker.class",
    "contractprobe/IndependentMarker.tasty"
  )

  val expectedHandlerPayload = Set(
    "contractprobe/IndependentHandler.class",
    "contractprobe/IndependentHandler.tasty"
  )

  final case class Config(
      scalaVersion: String,
      sbtVersion: String,
      projectVersion: String
  )

  final case class Coordinate(organization: String, module: String, version: String) {
    def render: String = s"$organization:$module:$version"
    def rootRelative: String = organization.replace('.', '/') + "/" + module
    def versionRelative: String = rootRelative + "/" + version
    def artifactRelative(extension: String): String =
      versionRelative + "/" + module + "-" + version + "." + extension
  }

  final case class ArtifactIdentity(
      coordinate: Coordinate,
      relativePath: String,
      bytes: Long,
      entries: Int,
      classCount: Int,
      tastyCount: Int,
      sha256: String
  ) {
    def render: String =
      s"coordinate=${coordinate.render} path=$relativePath bytes=$bytes entries=$entries classes=$classCount tasty=$tastyCount sha256=$sha256"
  }

  final case class PackageComparison(
      byteIdentical: Boolean,
      logicalEntriesEqual: Boolean,
      metadataDifferences: Vector[String]
  ) {
    def render: String =
      s"byteIdentical=$byteIdentical logicalEntriesEqual=$logicalEntriesEqual metadataDifferences=${metadataDifferences.mkString("[", ",", "]")}"
  }

  final case class NegativeEvidence(
      lane: String,
      exitCode: Int,
      diagnostic: String,
      outputFiles: Int
  ) {
    def render: String =
      s"$lane(exit=$exitCode diagnostic=$diagnostic outputFiles=$outputFiles)"
  }

  final case class VerificationResult(
      api: ArtifactIdentity,
      plugin: ArtifactIdentity,
      marker: ArtifactIdentity,
      handler: ArtifactIdentity,
      producerComparison: PackageComparison,
      stagedFiles: Int,
      pomFiles: Int,
      metadataSelections: Int,
      invocations: Int,
      generatedMethodPresent: Boolean,
      runtimeOutput: String,
      parentFirstApiIdentity: Boolean,
      multiProjectFirstUseProven: Boolean,
      handlerRuntimeIsolated: Boolean,
      negatives: Vector[NegativeEvidence],
      globalStateUnchanged: Boolean,
      cleanupVerified: Boolean,
      modelCases: Int,
      evidenceDirectory: File
  ) {
    def render: String =
      s"classification=$StagingClassification externalBuildClassification=$ExternalBuildClassification " +
        s"coordinateClassification=$CoordinateClassification publicationClassification=$PublicationClassification " +
        s"api={${api.render}} plugin={${plugin.render}} marker={${marker.render}} handler={${handler.render}} " +
        s"producerComparison={${producerComparison.render}} stagedFiles=$stagedFiles pomFiles=$pomFiles " +
        s"metadataSelections=$metadataSelections invocations=$invocations generatedMethodPresent=$generatedMethodPresent " +
        s"runtimeOutput=${runtimeOutput.trim} parentFirstApiIdentity=$parentFirstApiIdentity " +
        s"multiProjectFirstUseProven=$multiProjectFirstUseProven handlerRuntimeIsolated=$handlerRuntimeIsolated " +
        s"negatives=${negatives.map(_.render).mkString(",")} globalStateUnchanged=$globalStateUnchanged " +
        s"cleanupVerified=$cleanupVerified modelCases=$modelCases"
  }

  private final case class Layout(
      taskRoot: File,
      evidence: File,
      work: File,
      sourceCopy: File,
      repository: File,
      duplicateRepository: File,
      producer: File,
      consumer: File,
      multiProjectRed: File,
      multiProjectGreen: File,
      cache: File,
      coursier: File,
      ivy: File,
      boot: File,
      global: File,
      temporary: File
  )

  private final case class PomDependency(
      organization: String,
      module: String,
      version: String,
      scope: String
  ) {
    def render: String = s"$organization:$module:$version:$scope"
  }

  private final case class InternalResult(
      api: ArtifactIdentity,
      plugin: ArtifactIdentity,
      marker: ArtifactIdentity,
      handler: ArtifactIdentity,
      comparison: PackageComparison,
      stagedFiles: Int,
      pomFiles: Int,
      metadataSelections: Int,
      invocations: Int,
      generatedMethodPresent: Boolean,
      runtimeOutput: String,
      parentFirst: Boolean,
      multiProjectFirstUseProven: Boolean,
      handlerRuntimeIsolated: Boolean,
      negatives: Vector[NegativeEvidence]
  )

  private final case class MultiProjectFirstUseResult(
      negative: NegativeEvidence,
      generatedMethodPresent: Boolean,
      handlerRuntimeIsolated: Boolean
  )

  def repositoryCoordinate(module: String): Coordinate =
    Coordinate(RepositoryOrganization, module, Version)

  def markerCoordinate: Coordinate =
    Coordinate(ProducerOrganization, MarkerModule, Version)

  def handlerCoordinate: Coordinate =
    Coordinate(ProducerOrganization, HandlerModule, Version)

  def validateTaskCoordinate(coordinate: Coordinate): Vector[String] = {
    val errors = Vector.newBuilder[String]
    val expectedOrganization =
      if (coordinate.module == MarkerModule || coordinate.module == HandlerModule) ProducerOrganization
      else RepositoryOrganization
    if (coordinate.organization != expectedOrganization)
      errors += s"organization must equal $expectedOrganization"
    if (coordinate.organization.exists(_.isWhitespace))
      errors += "organization must not contain whitespace"
    if (coordinate.module.isEmpty || coordinate.module.exists(_.isWhitespace))
      errors += "module must be non-empty and contain no whitespace"
    if (coordinate.version != Version)
      errors += s"version must equal candidate $Version"
    errors.result()
  }

  def validateMavenRelativePath(coordinate: Coordinate, path: String): Boolean = {
    val normalized = path.replace('\\', '/')
    normalized.startsWith(coordinate.versionRelative + "/") &&
    !normalized.startsWith("/") &&
    !normalized.split('/').contains("..")
  }

  def pluginOptions(plugin: File, api: File, handler: Option[File]): Vector[String] =
    Vector(
      "-Xplugin:" + plugin.getAbsolutePath,
      "-Xplugin-require:macroparadise"
    ) ++ handler.toVector.map(file => "-P:macroparadise:handlerClasspath=" + file.getAbsolutePath)

  def validateResolvedGraph(
      modules: Vector[(String, String, String, String)],
      forbiddenWorkspaceFragments: Vector[String]
  ): Vector[String] = {
    val errors = Vector.newBuilder[String]
    forbiddenModuleFragments.foreach { fragment =>
      if (modules.exists(_._2.contains(fragment))) errors += s"forbidden module $fragment"
    }
    forbiddenWorkspaceFragments.foreach { fragment =>
      if (modules.exists(_._4.contains(fragment))) errors += s"forbidden workspace path $fragment"
    }
    def exactCount(organization: String, module: String): Int =
      modules.filter(value => value._1 == organization && value._2 == module && value._3 == Version).map(_._4).distinct.size
    if (exactCount(RepositoryOrganization, PluginModule) != 1) errors += "plugin coordinate is not singular"
    if (exactCount(RepositoryOrganization, PluginApiModule) != 1) errors += "pluginApi coordinate is not singular"
    if (exactCount(ProducerOrganization, MarkerModule) != 1) errors += "marker coordinate is not singular"
    if (exactCount(ProducerOrganization, HandlerModule) != 1) errors += "handler coordinate is not singular"
    errors.result()
  }

  def comparePackages(first: File, second: File): PackageComparison = {
    val byteIdentical = java.util.Arrays.equals(Files.readAllBytes(first.toPath), Files.readAllBytes(second.toPath))
    val firstEntries = zipEntryFacts(first)
    val secondEntries = zipEntryFacts(second)
    val logicalEqual = firstEntries.map(value => value._1 -> value._2) == secondEntries.map(value => value._1 -> value._2)
    val firstMetadata = firstEntries.map(value => value._1 -> (value._3, value._4, value._5)).toMap
    val secondMetadata = secondEntries.map(value => value._1 -> (value._3, value._4, value._5)).toMap
    val metadataDifferences =
      (firstMetadata.keySet ++ secondMetadata.keySet).toVector.sorted.flatMap { name =>
        val left = firstMetadata.get(name)
        val right = secondMetadata.get(name)
        if (left == right) None else Some(s"$name:$left->$right")
      }
    PackageComparison(byteIdentical, logicalEqual, metadataDifferences)
  }

  def verify(
      repositoryRoot: File,
      taskRoot: File,
      config: Config,
      modelCases: Int
  ): VerificationResult = {
    validateConfig(config)
    val layout = createLayout(taskRoot)
    val globalBefore = boundedGlobalSnapshot()
    val targetsBefore = repositoryTargetSnapshot(repositoryRoot)
    var internal: Option[InternalResult] = None
    var failure: Option[Throwable] = None

    try {
      internal = Some(runVerification(repositoryRoot, layout, config))
    } catch {
      case error: Throwable =>
        failure = Some(error)
        write(new File(layout.evidence, "failure.txt"), error.toString + "\n" + error.getStackTrace.mkString("\n") + "\n")
    } finally {
      deleteRecursively(layout.work.toPath)
    }

    val cleanupVerified = !layout.work.exists()
    val globalAfter = boundedGlobalSnapshot()
    val targetsAfter = repositoryTargetSnapshot(repositoryRoot)
    val globalUnchanged = globalBefore == globalAfter
    val targetsUnchanged = targetsBefore == targetsAfter
    val targetSnapshotRemoved = targetsBefore.filterNot(targetsAfter.contains)
    val targetSnapshotAdded = targetsAfter.filterNot(targetsBefore.contains)
    write(
      new File(layout.evidence, "global-state.txt"),
      "BEFORE\n" + globalBefore.mkString("\n") + "\nAFTER\n" + globalAfter.mkString("\n") +
        s"\nUNCHANGED=$globalUnchanged\nREPOSITORY_NON_TASK_TARGETS_UNCHANGED=$targetsUnchanged\n"
    )
    write(
      new File(layout.evidence, "repository-target-snapshot.txt"),
      "REMOVED_OR_CHANGED\n" + targetSnapshotRemoved.mkString("\n") +
        "\nADDED_OR_CHANGED\n" + targetSnapshotAdded.mkString("\n") + "\n"
    )
    write(
      new File(layout.evidence, "cleanup.txt"),
      s"workRoot=${layout.work.getAbsolutePath}\nworkRootExists=${layout.work.exists()}\ncleanupVerified=$cleanupVerified\n"
    )

    failure.foreach(error => throw error)
    require(globalUnchanged, "global Maven/Ivy/Coursier snapshot changed during isolated child builds")
    require(targetsUnchanged, "repository target directories outside the task root changed during isolated child builds")
    require(cleanupVerified, "task-owned work root was not deleted")

    val value = internal.getOrElse(throw new IllegalStateException("verification produced no result"))
    val result = VerificationResult(
      value.api,
      value.plugin,
      value.marker,
      value.handler,
      value.comparison,
      value.stagedFiles,
      value.pomFiles,
      value.metadataSelections,
      value.invocations,
      value.generatedMethodPresent,
      value.runtimeOutput,
      value.parentFirst,
      value.multiProjectFirstUseProven,
      value.handlerRuntimeIsolated,
      value.negatives,
      globalUnchanged,
      cleanupVerified,
      modelCases,
      layout.evidence
    )
    write(new File(layout.evidence, "summary.txt"), result.render + "\n")
    result
  }

  private def runVerification(repositoryRoot: File, layout: Layout, config: Config): InternalResult = {
    copySourceRepository(repositoryRoot, layout.sourceCopy)
    require(!containsForbiddenCopiedDirectory(layout.sourceCopy), "disposable source repository contains excluded state")
    write(new File(layout.evidence, "source-copy.txt"), sourceCopyInventory(layout.sourceCopy))

    val publisherLog = new File(layout.evidence, "commands/01-source-publisher.log")
    val publisherCommands = Vector(
      s"""set ThisBuild / publishTo := Some(Resolver.file("externalConsumer-task-repository", file("${scalaString(layout.repository.getAbsolutePath)}"))(Resolver.mavenStylePatterns))""",
      "set ThisBuild / credentials := Nil",
      "pluginApi/publish",
      "plugin/publish"
    )
    require(runSbt(layout.sourceCopy, layout, publisherCommands, Map.empty, publisherLog) == 0, "source repository publisher failed")
    auditCommandLog(publisherLog, layout)

    val apiCoordinate = repositoryCoordinate(PluginApiModule)
    val pluginCoordinate = repositoryCoordinate(PluginModule)
    val sourceApi = singleFile(layout.sourceCopy, PluginApiModule + "-" + Version + ".jar")
    val sourcePlugin = singleFile(layout.sourceCopy, PluginModule + "-" + Version + ".jar")
    val stagedApi = new File(layout.repository, apiCoordinate.artifactRelative("jar"))
    val stagedPlugin = new File(layout.repository, pluginCoordinate.artifactRelative("jar"))
    require(stagedApi.isFile && stagedPlugin.isFile, "staged plugin/API artifacts are missing")
    require(sameBytes(sourceApi, stagedApi), "staged pluginApi differs from disposable source package")
    require(sameBytes(sourcePlugin, stagedPlugin), "staged plugin differs from disposable source package")
    auditSelfContainedPluginJar(stagedPlugin, stagedApi)

    val initialRepositoryFiles = auditRepository(layout.repository, Vector(apiCoordinate, pluginCoordinate))
    val initialPomAudit = auditPoms(layout.repository, Vector(apiCoordinate, pluginCoordinate))
    write(new File(layout.evidence, "staged-repository-initial.txt"), initialRepositoryFiles.mkString("\n") + "\n")
    write(new File(layout.evidence, "pom-audit-initial.txt"), initialPomAudit.mkString("\n") + "\n")

    val multiProjectFirstUse =
      runMultiProjectFirstUse(repositoryRoot, layout, config)

    createProducerBuild(layout, config)
    val producerFirstLog = new File(layout.evidence, "commands/02-producer-first-package.log")
    require(
      runSbt(layout.producer, layout, Vector("clean", "verifyNoCompilerPlugin", "marker/package", "handler/package"), Map.empty, producerFirstLog) == 0,
      "first marker/handler package failed"
    )
    val firstMarkerJar = singleFile(layout.producer, MarkerModule + "-" + Version + ".jar")
    val firstHandlerJar = singleFile(layout.producer, HandlerModule + "-" + Version + ".jar")
    val firstMarkerSnapshot = new File(layout.work, "marker-first.jar")
    val firstHandlerSnapshot = new File(layout.work, "handler-first.jar")
    Files.copy(firstMarkerJar.toPath, firstMarkerSnapshot.toPath, StandardCopyOption.REPLACE_EXISTING)
    Files.copy(firstHandlerJar.toPath, firstHandlerSnapshot.toPath, StandardCopyOption.REPLACE_EXISTING)

    val producerSecondLog = new File(layout.evidence, "commands/03-producer-second-package.log")
    require(
      runSbt(layout.producer, layout, Vector("clean", "verifyNoCompilerPlugin", "marker/package", "handler/package"), Map.empty, producerSecondLog) == 0,
      "second marker/handler package failed"
    )
    val secondMarkerJar = singleFile(layout.producer, MarkerModule + "-" + Version + ".jar")
    val secondHandlerJar = singleFile(layout.producer, HandlerModule + "-" + Version + ".jar")
    val markerComparison = comparePackages(firstMarkerSnapshot, secondMarkerJar)
    val handlerComparison = comparePackages(firstHandlerSnapshot, secondHandlerJar)
    require(markerComparison.logicalEntriesEqual, "marker package logical entries or uncompressed bytes differ")
    require(handlerComparison.logicalEntriesEqual, "handler package logical entries or uncompressed bytes differ")
    auditThinIndependentJar(secondMarkerJar, expectedMarkerPayload, "marker")
    auditThinIndependentJar(secondHandlerJar, expectedHandlerPayload, "handler")
    write(
      new File(layout.evidence, "producer-package-comparison.txt"),
      "marker=" + markerComparison.render + "\nhandler=" + handlerComparison.render + "\n"
    )

    val producerPublishLog = new File(layout.evidence, "commands/04-producer-publish.log")
    require(
      runSbt(layout.producer, layout, Vector("marker/publish", "handler/publish"), Map.empty, producerPublishLog) == 0,
      "marker/handler publish failed"
    )
    val markerCoordinateValue = markerCoordinate
    val handlerCoordinateValue = handlerCoordinate
    val stagedMarker = new File(layout.repository, markerCoordinateValue.artifactRelative("jar"))
    val stagedHandler = new File(layout.repository, handlerCoordinateValue.artifactRelative("jar"))
    require(stagedMarker.isFile && stagedHandler.isFile, "staged marker/handler artifacts are missing")
    require(sameBytes(secondMarkerJar, stagedMarker), "staged marker differs from producer package")
    require(sameBytes(secondHandlerJar, stagedHandler), "staged handler differs from producer package")

    val repositoryFiles = auditRepository(layout.repository, Vector(apiCoordinate, pluginCoordinate, markerCoordinateValue, handlerCoordinateValue))
    val pomAudit = auditPoms(layout.repository, Vector(apiCoordinate, pluginCoordinate, markerCoordinateValue, handlerCoordinateValue))
    write(new File(layout.evidence, "staged-repository-final.txt"), repositoryFiles.mkString("\n") + "\n")
    write(new File(layout.evidence, "pom-audit-final.txt"), pomAudit.mkString("\n") + "\n")

    createConsumerBuild(layout, repositoryRoot, config)
    val metadataTrace = new File(layout.work, "positive-metadata.trace")
    val invocationTrace = new File(layout.work, "positive-invocation.trace")
    val positiveLog = new File(layout.evidence, "commands/05-consumer-positive.log")
    val positiveProperties = Map(
      "externalConsumer.mode" -> "positive"
    )
    require(runSbt(layout.consumer, layout, Vector("clean", "externalConsumerAudit", "compile", "externalConsumerRuntime"), positiveProperties, positiveLog) == 0, "positive external consumer failed")
    val graphFile = new File(layout.consumer, "target/externalConsumer-resolved-graph.txt")
    val optionsFile = new File(layout.consumer, "target/externalConsumer-plugin-options.txt")
    val runtimeFile = new File(layout.consumer, "target/externalConsumer-runtime.txt")
    require(graphFile.isFile && optionsFile.isFile && runtimeFile.isFile, "consumer audit evidence is incomplete")
    val graph = Files.readAllLines(graphFile.toPath, StandardCharsets.UTF_8).asScala.toVector
    val options = Files.readAllLines(optionsFile.toPath, StandardCharsets.UTF_8).asScala.toVector
    val runtime = Files.readAllBytes(runtimeFile.toPath)
    val runtimeText = new String(runtime, StandardCharsets.UTF_8)
    require(runtimeText == ExpectedRuntimeOutput, s"runtime output was `${runtimeText.replace("\n", "\\n")}`")
    auditResolvedGraph(graph, repositoryRoot, layout)
    auditPluginOptions(options)
    write(new File(layout.evidence, "resolved-graph.txt"), graph.mkString("\n") + "\n")
    write(new File(layout.evidence, "plugin-options.txt"), options.mkString("\n") + "\n")
    write(new File(layout.evidence, "positive-runtime.txt"), runtimeText)

    val consumerClasses = singleDirectory(layout.consumer, "classes")
    val positiveOutputs = regularRelativeFiles(consumerClasses)
    require(positiveOutputs.count(_.endsWith(".class")) >= 3, "consumer emitted too few class files")
    require(positiveOutputs.count(_.endsWith(".tasty")) >= 2, "consumer emitted too few Tasty files")
    val javapLog = new File(layout.evidence, "positive-javap.txt")
    val javap = runProcess(
      Vector(javaTool("javap"), "-classpath", consumerClasses.getAbsolutePath, "contractprobeconsumer.IndependentConsumerUser"),
      layout.consumer,
      Map.empty,
      javapLog
    )
    require(javap._1 == 0 && javap._2.contains("java.lang.String independentHandlerName()"), "generated method is absent after external sbt compilation")
    val metadataLines = readLines(metadataTrace)
    val invocationLines = readLines(invocationTrace)
    val metadataSelections = metadataLines.count(line => line.contains("contractprobe.IndependentMarker") && line.contains("Found(contractprobe.IndependentHandler)"))
    val invocations = invocationLines.count(_.contains("handler=contractprobe.IndependentHandler"))
    require(metadataSelections == 1, s"expected one metadata selection, found $metadataSelections")
    require(invocations == 1, s"expected one handler invocation, found $invocations")
    val positiveText = Files.readAllBytes(positiveLog.toPath)
    val positiveRendered = new String(positiveText, StandardCharsets.UTF_8)
    require(!positiveRendered.contains("external handler failure: stage="), "positive lane emitted a stage diagnostic")
    require(!metadataLines.exists(line => line.contains("paradise3.external")), "repository fixture marker was selected")
    require(!invocationLines.exists(line => line.contains("demo.")), "repository fixture handler was invoked")
    write(new File(layout.evidence, "metadata.trace"), metadataLines.mkString("\n") + "\n")
    write(new File(layout.evidence, "invocation.trace"), invocationLines.mkString("\n") + "\n")
    write(new File(layout.evidence, "positive-output-inventory.txt"), positiveOutputs.mkString("\n") + "\n")

    val resolvedFiles = graph.flatMap(parseGraphFile).distinct
    val resolvedApi = exactlyOne(resolvedFiles.filter(_.getName == PluginApiModule + "-" + Version + ".jar"), "resolved pluginApi")
    val resolvedPlugin = exactlyOne(resolvedFiles.filter(_.getName == PluginModule + "-" + Version + ".jar"), "resolved plugin")
    val resolvedHandler = exactlyOne(resolvedFiles.filter(_.getName == HandlerModule + "-" + Version + ".jar"), "resolved independent handler")
    val compilerJars = resolvedFiles.filter(file =>
      file.getName.endsWith(".jar") && file != resolvedPlugin && file != resolvedApi && file != resolvedHandler
    )
    val parentFirst = verifyParentFirstIdentity(resolvedPlugin, resolvedHandler, compilerJars, new File(layout.evidence, "parent-first-identity.txt"))

    val missingHandlerLog = new File(layout.evidence, "commands/06-consumer-missing-handler.log")
    val missingHandlerExit = runSbt(layout.consumer, layout, Vector("clean", "compile"), Map("externalConsumer.mode" -> "missing-handler"), missingHandlerLog)
    val missingHandlerText = read(missingHandlerLog)
    require(missingHandlerExit != 0, "missing-handler external consumer unexpectedly compiled")
    require(missingHandlerText.contains("stage=loading") && missingHandlerText.contains("category=HANDLER_LOAD_FAILURE"), "missing-handler lane lacked controlled loading diagnostic")
    requireNoUncontrolledFailure(missingHandlerText, "missing-handler")
    val missingHandlerOutputs = outputFilesAfterFailure(layout.consumer)
    require(missingHandlerOutputs.isEmpty, "missing-handler lane emitted partial output")
    val missingHandler = NegativeEvidence("missing-handler", missingHandlerExit, "stage=loading category=HANDLER_LOAD_FAILURE", missingHandlerOutputs.size)

    val missingMarkerLog = new File(layout.evidence, "commands/07-consumer-missing-marker.log")
    val missingMarkerExit = runSbt(layout.consumer, layout, Vector("clean", "compile"), Map("externalConsumer.mode" -> "missing-marker"), missingMarkerLog)
    val missingMarkerText = read(missingMarkerLog)
    require(missingMarkerExit != 0, "missing-marker external consumer unexpectedly compiled")
    val missingMarkerDiagnostic = Vector("Not found: contractprobe", "value contractprobe", "Not found: type IndependentMarker", "Not found: IndependentMarker").find(missingMarkerText.contains).getOrElse(throw new IllegalStateException("missing-marker lane lacked ordinary missing-type diagnostic"))
    requireNoUncontrolledFailure(missingMarkerText, "missing-marker")
    val missingMarkerOutputs = outputFilesAfterFailure(layout.consumer)
    require(missingMarkerOutputs.isEmpty, "missing-marker lane emitted partial output")
    val missingMarker = NegativeEvidence("missing-marker", missingMarkerExit, missingMarkerDiagnostic, missingMarkerOutputs.size)

    createDuplicateApiRepository(stagedApi, layout.duplicateRepository)
    val duplicateLog = new File(layout.evidence, "commands/08-consumer-duplicate-api.log")
    val duplicateExit = runSbt(layout.consumer, layout, Vector("clean", "externalConsumerAudit"), Map("externalConsumer.mode" -> "duplicate-api"), duplicateLog)
    val duplicateText = read(duplicateLog)
    require(duplicateExit != 0, "duplicate-API external consumer unexpectedly passed graph audit")
    require(duplicateText.contains("DUPLICATE_API_PROVIDER"), "duplicate-API lane lacked controlled graph rejection")
    requireNoUncontrolledFailure(duplicateText, "duplicate-api")
    val duplicateOutputs = outputFilesAfterFailure(layout.consumer)
    require(duplicateOutputs.isEmpty, "duplicate-API lane emitted partial output")
    val duplicateApi = NegativeEvidence("duplicate-api", duplicateExit, "DUPLICATE_API_PROVIDER", duplicateOutputs.size)

    val apiIdentity = artifactIdentity(apiCoordinate, layout.repository, stagedApi)
    val pluginIdentity = artifactIdentity(pluginCoordinate, layout.repository, stagedPlugin)
    val markerIdentity = artifactIdentity(markerCoordinateValue, layout.repository, stagedMarker)
    val handlerIdentity = artifactIdentity(handlerCoordinateValue, layout.repository, stagedHandler)
    InternalResult(
      apiIdentity,
      pluginIdentity,
      markerIdentity,
      handlerIdentity,
      handlerComparison,
      repositoryFiles.size,
      repositoryFiles.count(_.endsWith(".pom")),
      metadataSelections,
      invocations,
      generatedMethodPresent = true,
      runtimeText,
      parentFirst,
      multiProjectFirstUseProven = multiProjectFirstUse.generatedMethodPresent,
      handlerRuntimeIsolated = multiProjectFirstUse.handlerRuntimeIsolated,
      negatives = Vector(multiProjectFirstUse.negative, missingHandler, missingMarker, duplicateApi)
    )
  }

  private def createLayout(taskRoot: File): Layout = {
    recreateDirectory(taskRoot.toPath)
    val evidence = new File(taskRoot, "evidence")
    val work = new File(taskRoot, "work")
    val cache = new File(work, "cache")
    val layout = Layout(
      taskRoot,
      evidence,
      work,
      new File(work, "source-repository"),
      new File(work, "maven-repository"),
      new File(work, "duplicate-api-repository"),
      new File(work, "producer-build"),
      new File(work, "consumer-build"),
      new File(work, "multi-project-red"),
      new File(work, "multi-project-green"),
      cache,
      new File(cache, "coursier"),
      new File(cache, "ivy"),
      new File(cache, "sbt-boot"),
      new File(cache, "sbt-global"),
      new File(work, "tmp")
    )
    Vector(layout.evidence, layout.work, layout.repository, layout.duplicateRepository, layout.cache, layout.coursier, layout.ivy, layout.boot, layout.global, layout.temporary).foreach(file => Files.createDirectories(file.toPath))
    layout
  }

  private def copySourceRepository(source: File, destination: File): Unit = {
    val excluded = Set(".git", "target", ".bsp", ".idea", ".metals", ".scala-build", ".bloop", ".agents", ".codex", "workspace", "cache", "logs", "out")
    Files.walkFileTree(source.toPath, new SimpleFileVisitor[Path] {
      override def preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult = {
        val relative = source.toPath.relativize(directory)
        val parts = relative.iterator().asScala.map(_.toString).toVector
        if (parts.exists(excluded.contains)) FileVisitResult.SKIP_SUBTREE
        else {
          Files.createDirectories(destination.toPath.resolve(relative))
          FileVisitResult.CONTINUE
        }
      }
      override def visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult = {
        val relative = source.toPath.relativize(file)
        Files.createDirectories(destination.toPath.resolve(relative).getParent)
        Files.copy(file, destination.toPath.resolve(relative), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
        FileVisitResult.CONTINUE
      }
    })
  }

  private def containsForbiddenCopiedDirectory(root: File): Boolean = {
    val forbidden = Set(".git", "target", ".bsp", ".idea", ".metals", ".scala-build", ".bloop", ".agents", ".codex")
    val stream = Files.walk(root.toPath)
    try stream.iterator().asScala.exists(path => Files.isDirectory(path) && forbidden.contains(path.getFileName.toString))
    finally stream.close()
  }

  private def sourceCopyInventory(root: File): String = {
    val files = regularRelativeFiles(root)
    s"root=${root.getAbsolutePath}\nfiles=${files.size}\ncontainsGit=false\ncontainsTarget=false\n" + files.mkString("\n") + "\n"
  }

  def multiProjectBuildText(
      repositoryUri: String,
      config: Config,
      positive: Boolean,
      evidenceDirectory: String
  ): String = {
    val dependency =
      if (positive) ".dependsOn(marker)"
      else ".dependsOn(marker, handler)"
    val handlerOption =
      if (positive)
        """      val handlerJar = (handler / Compile / packageBin).value
          |      base ++ Seq("-P:macroparadise:handlerClasspath=" + handlerJar.getAbsolutePath)""".stripMargin
      else "      base"
    val positiveLiteral = positive.toString
    s"""import sbt._
       |import Keys._
       |import java.io.File
       |import java.nio.charset.StandardCharsets
       |import scala.sys.process._
       |
       |ThisBuild / scalaVersion := "${config.scalaVersion}"
       |ThisBuild / version := "${config.projectVersion}"
       |ThisBuild / resolvers ++= Seq(
       |  "external-first-use-task-repository" at "${scalaString(repositoryUri)}",
       |  Resolver.scalaNightlyRepository
       |)
       |ThisBuild / credentials := Nil
       |
       |val mpOrganization = "$RepositoryOrganization"
       |val mpVersion = "${config.projectVersion}"
       |val mpApi =
       |  (mpOrganization % "macroparadise-scala3-plugin-api" % mpVersion)
       |    .cross(CrossVersion.full)
       |val mpPlugin =
       |  (mpOrganization % "macroparadise-scala3-plugin" % mpVersion)
       |    .cross(CrossVersion.full)
       |
       |lazy val firstUseAudit = taskKey[Unit]("Audit the external handler first-use graph")
       |lazy val firstUseRuntime = taskKey[Unit]("Run the generated-definition consumer")
       |
       |lazy val marker = project.in(file("marker"))
       |  .settings(
       |    name := "external-first-use-marker",
       |    libraryDependencies += mpApi
       |  )
       |
       |lazy val handler = project.in(file("handler"))
       |  .settings(
       |    name := "external-first-use-handler",
       |    libraryDependencies ++= Seq(
       |      mpApi,
       |      "org.scala-lang" %% "scala3-compiler" % scalaVersion.value
       |    )
       |  )
       |
       |lazy val core = project.in(file("core"))
       |  $dependency
       |  .settings(
       |    name := "external-first-use-core",
       |    libraryDependencies += compilerPlugin(mpPlugin),
       |    Compile / scalacOptions ++= {
       |      val base = Seq("-Xplugin-require:macroparadise")
       |$handlerOption
       |    }
       |  )
       |
       |lazy val root = project.in(file("."))
       |  .aggregate(marker, handler, core)
       |  .settings(
       |    publish / skip := true,
       |    firstUseAudit := {
       |      val options = (core / Compile / scalacOptions).value
       |      val pluginOptions = options.filter(_.startsWith("-Xplugin:"))
       |      val handlerOptions = options.filter(_.startsWith("-P:macroparadise:handlerClasspath="))
       |      val compileClasspath = (core / Compile / dependencyClasspath).value.files.map(_.getCanonicalFile).distinct
       |      val runtimeClasspath = (core / Runtime / fullClasspath).value.files.map(_.getCanonicalFile).distinct
       |      val handlerClasses = (handler / Compile / classDirectory).value.getCanonicalFile
       |      val handlerJar = (handler / Compile / packageBin).value.getCanonicalFile
       |      require(pluginOptions.size == 1, "expected exactly one compiler-plugin activation path, found " + pluginOptions)
       |      require(!pluginOptions.head.contains("plugin-api"), "compiler-plugin activation still appends plugin-api")
       |      if ($positiveLiteral) {
       |        require(handlerOptions == Seq("-P:macroparadise:handlerClasspath=" + handlerJar.getAbsolutePath), "handler option does not equal handler/packageBin")
       |        require(!compileClasspath.contains(handlerClasses), "handler implementation leaked onto the ordinary source compilation classpath")
       |        require(!runtimeClasspath.contains(handlerClasses) && !runtimeClasspath.contains(handlerJar), "handler implementation leaked onto the application runtime classpath")
       |      } else {
       |        require(handlerOptions.isEmpty, "negative first-use graph unexpectedly configures the handler loader")
       |        require(compileClasspath.contains(handlerClasses), "negative graph does not reproduce dependsOn(handler) ordinary-classpath visibility")
       |      }
       |      IO.write(
       |        file("${scalaString(evidenceDirectory)}/audit.txt"),
       |        "positive=$positiveLiteral\\n" +
       |          "pluginOptions=" + pluginOptions.mkString("|") + "\\n" +
       |          "handlerOptions=" + handlerOptions.mkString("|") + "\\n" +
       |          "ordinaryClasspathContainsHandlerClasses=" + compileClasspath.contains(handlerClasses) + "\\n" +
       |          "runtimeClasspathContainsHandlerClasses=" + runtimeClasspath.contains(handlerClasses) + "\\n",
       |        StandardCharsets.UTF_8
       |      )
       |    },
       |    firstUseRuntime := {
       |      val classpath = (core / Runtime / fullClasspath).value.files.map(_.getAbsolutePath).mkString(File.pathSeparator)
       |      val java = file(sys.props("java.home")) / "bin" / "java"
       |      val output = Process(Vector(java.getAbsolutePath, "-cp", classpath, "contractprobeconsumer.IndependentPackagedConsumer"), baseDirectory.value).!!
       |      require(output == "IndependentConsumerUser\\n", "unexpected runtime output: " + output.replace("\\n", "\\\\n"))
       |      IO.write(file("${scalaString(evidenceDirectory)}/runtime.txt"), output, StandardCharsets.UTF_8)
       |    }
       |  )
       |""".stripMargin
  }

  private def runMultiProjectFirstUse(
      repositoryRoot: File,
      layout: Layout,
      config: Config
  ): MultiProjectFirstUseResult = {
    val redEvidence = new File(layout.evidence, "multi-project-red")
    val greenEvidence = new File(layout.evidence, "multi-project-green")
    createMultiProjectBuild(layout, layout.multiProjectRed, redEvidence, config, positive = false)
    createMultiProjectBuild(layout, layout.multiProjectGreen, greenEvidence, config, positive = true)

    val redLog = new File(layout.evidence, "commands/02a-multi-project-red.log")
    val redExit = runSbt(
      layout.multiProjectRed,
      layout,
      Vector("clean", "firstUseAudit", "core/compile"),
      Map.empty,
      redLog
    )
    val redText = read(redLog)
    require(redExit != 0, "natural multi-project graph without handlerClasspath unexpectedly compiled")
    Vector(
      "stage=loading",
      "category=HANDLER_LOAD_FAILURE",
      "handlerClasspathConfigured=false",
      "handlerClasspathEntries=0",
      "-P:macroparadise:handlerClasspath=<handler-jar-or-path-list>",
      "ordinary source compilation classpath",
      "compiler-plugin loader classpath",
      "marker metadata"
    ).foreach(fragment => require(redText.contains(fragment), "natural multi-project negative lacked actionable fragment: " + fragment))
    requireNoUncontrolledFailure(redText, "natural-multi-project-missing-handler")
    val redOutputs = outputFilesAfterFailure(new File(layout.multiProjectRed, "core"))
    require(redOutputs.isEmpty, "natural multi-project negative emitted partial core output")

    val greenLog = new File(layout.evidence, "commands/02b-multi-project-green.log")
    require(
      runSbt(
        layout.multiProjectGreen,
        layout,
        Vector("clean", "firstUseAudit", "core/compile", "firstUseRuntime"),
        Map.empty,
        greenLog
      ) == 0,
      "corrected natural multi-project graph failed"
    )
    val greenText = read(greenLog)
    require(!greenText.contains("external handler failure: stage="), "corrected multi-project graph emitted a handler failure")
    val coreClasses = singleDirectory(new File(layout.multiProjectGreen, "core"), "classes")
    val javapLog = new File(greenEvidence, "core-javap.txt")
    val javap = runProcess(
      Vector(javaTool("javap"), "-classpath", coreClasses.getAbsolutePath, "contractprobeconsumer.IndependentConsumerUser"),
      layout.multiProjectGreen,
      Map.empty,
      javapLog
    )
    val generatedMethodPresent = javap._1 == 0 && javap._2.contains("java.lang.String independentHandlerName()")
    require(generatedMethodPresent, "corrected multi-project graph lacks the generated definition")
    val audit = read(new File(greenEvidence, "audit.txt"))
    val handlerRuntimeIsolated =
      audit.contains("ordinaryClasspathContainsHandlerClasses=false") &&
        audit.contains("runtimeClasspathContainsHandlerClasses=false")
    require(handlerRuntimeIsolated, "handler implementation polluted the corrected compile/runtime classpath")
    require(read(new File(greenEvidence, "runtime.txt")) == ExpectedRuntimeOutput, "corrected multi-project runtime proof changed")
    write(
      new File(layout.evidence, "multi-project-first-use-summary.txt"),
      "red=HANDLER_LOAD_FAILURE_ACTIONABLE\n" +
        "green=GENERATED_DEFINITION_AND_RUNTIME_READY\n" +
        "compilerPluginCoordinate=single-full-cross\n" +
        "manualPluginApiXplugin=false\n" +
        "handlerRelationship=task-packageBin-only\n" +
        "handlerRuntimeIsolated=" + handlerRuntimeIsolated + "\n"
    )
    MultiProjectFirstUseResult(
      NegativeEvidence(
        "natural-multi-project-missing-handler",
        redExit,
        "stage=loading category=HANDLER_LOAD_FAILURE handlerClasspathConfigured=false",
        redOutputs.size
      ),
      generatedMethodPresent,
      handlerRuntimeIsolated
    )
  }

  private def createMultiProjectBuild(
      layout: Layout,
      directory: File,
      evidence: File,
      config: Config,
      positive: Boolean
  ): Unit = {
    Files.createDirectories(new File(directory, "project").toPath)
    Files.createDirectories(new File(directory, "marker/src/main/scala").toPath)
    Files.createDirectories(new File(directory, "handler/src/main/scala").toPath)
    Files.createDirectories(new File(directory, "core/src/main/scala").toPath)
    Files.createDirectories(evidence.toPath)
    write(new File(directory, "project/build.properties"), "sbt.version=" + config.sbtVersion + "\n")
    val build = multiProjectBuildText(layout.repository.toURI.toString, config, positive, evidence.getAbsolutePath)
    write(new File(directory, "build.sbt"), build)
    write(new File(evidence, "build.sbt"), build)
    Files.copy(
      new File(layout.sourceCopy, "plugin-api-handler-contract-probe/three-stage/marker/IndependentMarker.scala").toPath,
      new File(directory, "marker/src/main/scala/IndependentMarker.scala").toPath,
      StandardCopyOption.REPLACE_EXISTING
    )
    Files.copy(
      new File(layout.sourceCopy, "plugin-api-handler-contract-probe/three-stage/handler/IndependentHandler.scala").toPath,
      new File(directory, "handler/src/main/scala/IndependentHandler.scala").toPath,
      StandardCopyOption.REPLACE_EXISTING
    )
    Files.copy(
      new File(layout.sourceCopy, "plugin-api-handler-contract-probe/e2e/IndependentPackagedConsumer.scala").toPath,
      new File(directory, "core/src/main/scala/IndependentPackagedConsumer.scala").toPath,
      StandardCopyOption.REPLACE_EXISTING
    )
  }

  private def createProducerBuild(layout: Layout, config: Config): Unit = {
    Files.createDirectories(new File(layout.producer, "project").toPath)
    Files.createDirectories(new File(layout.producer, "marker/src/main/scala").toPath)
    Files.createDirectories(new File(layout.producer, "handler/src/main/scala").toPath)
    write(new File(layout.producer, "project/build.properties"), "sbt.version=" + config.sbtVersion + "\n")
    val markerSource = new File(layout.sourceCopy, "plugin-api-handler-contract-probe/three-stage/marker/IndependentMarker.scala")
    val handlerSource = new File(layout.sourceCopy, "plugin-api-handler-contract-probe/three-stage/handler/IndependentHandler.scala")
    Files.copy(markerSource.toPath, new File(layout.producer, "marker/src/main/scala/IndependentMarker.scala").toPath, StandardCopyOption.REPLACE_EXISTING)
    Files.copy(handlerSource.toPath, new File(layout.producer, "handler/src/main/scala/IndependentHandler.scala").toPath, StandardCopyOption.REPLACE_EXISTING)
    val build =
      s"""import java.nio.charset.StandardCharsets
         |
         |ThisBuild / organization := \"$ProducerOrganization\"
         |ThisBuild / version := \"$Version\"
         |ThisBuild / scalaVersion := \"${config.scalaVersion}\"
         |ThisBuild / resolvers ++= Seq(
         |  \"externalConsumer-task-repository\" at \"${scalaString(layout.repository.toURI.toString)}\",
         |  Resolver.scalaNightlyRepository
         |)
         |ThisBuild / publishMavenStyle := true
         |ThisBuild / publishTo := Some(Resolver.file(\"externalConsumer-task-repository\", file(\"${scalaString(layout.repository.getAbsolutePath)}\"))(Resolver.mavenStylePatterns))
         |ThisBuild / credentials := Nil
         |Compile / packageSrc / publishArtifact := false
         |Compile / packageDoc / publishArtifact := false
         |Test / publishArtifact := false
         |
         |lazy val verifyNoCompilerPlugin = taskKey[Unit](\"Verify marker and handler compile without Macro Paradise\")
         |lazy val contractSettings = Seq(
         |  libraryDependencies += \"$RepositoryOrganization\" % \"$PluginApiModule\" % \"$Version\"
         |)
         |
         |lazy val marker = project.in(file(\"marker\"))
         |  .settings(contractSettings)
         |  .settings(name := \"independent-marker\")
         |
         |lazy val handler = project.in(file(\"handler\"))
         |  .settings(contractSettings)
         |  .settings(
         |    name := \"independent-handler\",
         |    libraryDependencies += \"org.scala-lang\" %% \"scala3-compiler\" % scalaVersion.value
         |  )
         |
         |lazy val root = project.in(file(\".\"))
         |  .aggregate(marker, handler)
         |  .settings(
         |    publish / skip := true,
         |    verifyNoCompilerPlugin := {
         |      val markerOptions = (marker / Compile / scalacOptions).value
         |      val handlerOptions = (handler / Compile / scalacOptions).value
         |      val allOptions = markerOptions ++ handlerOptions
         |      require(!allOptions.exists(_.startsWith(\"-Xplugin\")), \"marker/handler compilation activated a compiler plugin\")
         |      require(!allOptions.exists(_.startsWith(\"-P:macroparadise:\")), \"marker/handler compilation received Macro Paradise options\")
         |      IO.write(
         |        file(\"${scalaString(new File(layout.evidence, "producer-no-plugin.txt").getAbsolutePath)}\"),
         |        \"markerOptions=\" + markerOptions.mkString(\"[\", \",\", \"]\") + \"\\n\" +
         |          \"handlerOptions=\" + handlerOptions.mkString(\"[\", \",\", \"]\") + \"\\n\" +
         |          \"macroParadiseActive=false\\n\",
         |        StandardCharsets.UTF_8
         |      )
         |    }
         |  )
         |""".stripMargin.replace("\\\"", "\"")
    write(new File(layout.producer, "build.sbt"), build)
  }

  private def createConsumerBuild(layout: Layout, repositoryRoot: File, config: Config): Unit = {
    Files.createDirectories(new File(layout.consumer, "project").toPath)
    Files.createDirectories(new File(layout.consumer, "src/main/scala").toPath)
    write(new File(layout.consumer, "project/build.properties"), "sbt.version=" + config.sbtVersion + "\n")
    val source = new File(layout.sourceCopy, "plugin-api-handler-contract-probe/e2e/IndependentPackagedConsumer.scala")
    Files.copy(source.toPath, new File(layout.consumer, "src/main/scala/IndependentPackagedConsumer.scala").toPath, StandardCopyOption.REPLACE_EXISTING)
    val build = consumerBuildText(layout, repositoryRoot, config)
    write(new File(layout.consumer, "build.sbt"), build)
  }

  private def consumerBuildText(layout: Layout, repositoryRoot: File, config: Config): String = {
    s"""import sbt._
       |import Keys._
       |import java.io.File
       |import java.nio.charset.StandardCharsets
       |import java.nio.file.Files
       |import java.security.MessageDigest
       |import java.util.jar.JarFile
       |import scala.collection.JavaConverters._
       |import scala.sys.process._
       |
       |lazy val externalConsumerAudit = taskKey[Unit](\"Audit coordinate-resolved external consumer graph and plugin options\")
       |lazy val externalConsumerRuntime = taskKey[Unit](\"Run the external consumer consumer and capture exact raw output\")
       |
       |val externalConsumerMode = sys.props.getOrElse(\"externalConsumer.mode\", \"positive\")
       |val externalConsumerMetadataTrace = \"${scalaString(new File(layout.work, "positive-metadata.trace").getAbsolutePath)}\"
       |val externalConsumerInvocationTrace = \"${scalaString(new File(layout.work, "positive-invocation.trace").getAbsolutePath)}\"
       |val externalConsumerVersion = \"$Version\"
       |val externalConsumerRepoOrg = \"$RepositoryOrganization\"
       |val externalConsumerProducerOrg = \"$ProducerOrganization\"
       |val externalConsumerPluginModule = \"$PluginModule\"
       |val externalConsumerApiModule = \"$PluginApiModule\"
       |val externalConsumerMarkerModule = \"$MarkerModule\"
       |val externalConsumerHandlerModule = \"$HandlerModule\"
       |val externalConsumerDuplicateModule = \"$DuplicateApiModule\"
       |
       |def externalConsumerArtifacts(report: sbt.librarymanagement.UpdateReport): Vector[(String, String, String, File)] =
       |  report.configurations.toVector.flatMap(_.modules).flatMap { module =>
       |    module.artifacts.map { case (_, file) => (module.module.organization, module.module.name, module.module.revision, file.getCanonicalFile) }
       |  }.distinct
       |
       |def externalConsumerOnClasspath(report: sbt.librarymanagement.UpdateReport, files: Vector[File]): Vector[(String, String, String, File)] = {
       |  val allowed = files.map(_.getCanonicalFile).toSet
       |  externalConsumerArtifacts(report).filter(value => allowed(value._4))
       |}
       |
       |def externalConsumerExactlyOne(files: Vector[File], label: String): File = {
       |  val unique = files.map(_.getCanonicalFile).distinct
       |  require(unique.size == 1, label + \" must resolve exactly once, found \" + unique.mkString(\",\"))
       |  unique.head
       |}
       |
       |def externalConsumerContainsApi(file: File): Boolean = {
       |  if (!file.isFile || !file.getName.endsWith(\".jar\")) false
       |  else {
       |    val jar = new JarFile(file)
       |    try jar.getEntry(\"paradise3/api/ParadiseAnnotationExpander.class\") != null
       |    finally jar.close()
       |  }
       |}
       |
       |lazy val root = (project in file(\".\"))
       |  .settings(
       |    name := \"independent-external-consumer\",
       |    organization := \"local.contractprobe.externalConsumer.consumer\",
       |    version := externalConsumerVersion,
       |    scalaVersion := \"${config.scalaVersion}\",
       |    resolvers ++= Seq(
       |      \"externalConsumer-task-repository\" at \"${scalaString(layout.repository.toURI.toString)}\",
       |      \"externalConsumer-duplicate-api-repository\" at \"${scalaString(layout.duplicateRepository.toURI.toString)}\",
       |      Resolver.scalaNightlyRepository
       |    ),
       |    credentials := Nil,
       |    libraryDependencies ++= {
       |      val marker = if (externalConsumerMode == \"missing-marker\") Nil else List(externalConsumerProducerOrg % externalConsumerMarkerModule % externalConsumerVersion)
       |      val handler = if (externalConsumerMode == \"missing-handler\") Nil else List(externalConsumerProducerOrg % externalConsumerHandlerModule % externalConsumerVersion)
       |      val duplicate = if (externalConsumerMode == \"duplicate-api\") List(externalConsumerProducerOrg %% \"duplicate-plugin-api\" % externalConsumerVersion) else Nil
       |      marker ++ handler ++ duplicate ++ List(
       |        compilerPlugin((externalConsumerRepoOrg % \"macroparadise-scala3-plugin\" % externalConsumerVersion).cross(CrossVersion.full)),
       |        (externalConsumerRepoOrg % \"macroparadise-scala3-plugin-api\" % externalConsumerVersion).cross(CrossVersion.full)
       |      )
       |    },
       |    Compile / scalacOptions ++= {
       |      val updateArtifacts = externalConsumerArtifacts((Compile / update).value)
       |      val compileArtifacts = externalConsumerOnClasspath((Compile / update).value, (Compile / dependencyClasspath).value.files.toVector)
       |      val plugin = externalConsumerExactlyOne(updateArtifacts.collect { case (o, m, v, f) if o == externalConsumerRepoOrg && m == externalConsumerPluginModule && v == externalConsumerVersion => f }, \"plugin\")
       |      val api = externalConsumerExactlyOne(compileArtifacts.collect { case (o, m, v, f) if o == externalConsumerRepoOrg && m == externalConsumerApiModule && v == externalConsumerVersion => f }, \"pluginApi\")
       |      val handler = compileArtifacts.collect { case (o, m, v, f) if o == externalConsumerProducerOrg && m == externalConsumerHandlerModule && v == externalConsumerVersion => f }.map(_.getCanonicalFile).distinct
       |      val compileProviders = (Compile / dependencyClasspath).value.files.filter(externalConsumerContainsApi).map(_.getCanonicalFile).distinct
       |      val pluginProviders = Vector(plugin).filter(externalConsumerContainsApi).map(_.getCanonicalFile).distinct
       |      require(pluginProviders == Vector(plugin.getCanonicalFile), \"compiler plugin is not self-contained with respect to paradise3.api\")
       |      if (externalConsumerMode == \"duplicate-api\") require(compileProviders.size == 1, \"DUPLICATE_API_PROVIDER: expected one ordinary-classpath paradise3.api provider, found \" + compileProviders.mkString(\",\"))
       |      else if (externalConsumerMode != \"missing-marker\") require(compileProviders.size == 1, \"expected singular ordinary-classpath paradise3.api provider, found \" + compileProviders.mkString(\",\"))
       |      val base = Seq(
       |        \"-Xplugin-require:macroparadise\",
       |        \"-P:macroparadise:metadataReaderTrace=\" + externalConsumerMetadataTrace,
       |        \"-P:macroparadise:externalHandlerInvocationTrace=\" + externalConsumerInvocationTrace
       |      )
       |      if (externalConsumerMode == \"positive\" || externalConsumerMode == \"duplicate-api\") base ++ Seq(\"-P:macroparadise:handlerClasspath=\" + externalConsumerExactlyOne(handler, \"handler\").getAbsolutePath)
       |      else base
       |    },
       |    externalConsumerAudit := {
       |      val updateArtifacts = externalConsumerArtifacts((Compile / update).value)
       |      val compileArtifacts = externalConsumerOnClasspath((Compile / update).value, (Compile / dependencyClasspath).value.files.toVector)
       |      val pluginArtifacts = updateArtifacts.filter(value => value._1 == externalConsumerRepoOrg && value._2 == externalConsumerPluginModule && value._3 == externalConsumerVersion)
       |      val compileClasspath = (Compile / dependencyClasspath).value.files.map(_.getCanonicalFile).distinct
       |      val options = (Compile / scalacOptions).value
       |      val all = (compileArtifacts.map(value => (\"compile\", value)) ++ pluginArtifacts.map(value => (\"plugin\", value))).distinct
       |      val forbiddenModules = Vector(\"plugin-test-markers\", \"plugin-test-handlers\", \"plugin-tests\", \"legacy-metadata\", \"quasiquotes\", \"experimental-plugin-api-handler-contract\")
       |      forbiddenModules.foreach(fragment => require(!all.exists(_._2._2.contains(fragment)), \"forbidden module \" + fragment))
       |      require(!compileArtifacts.exists(_._2 == externalConsumerPluginModule), \"plugin implementation leaked onto ordinary compile classpath\")
       |      val forbiddenPaths = Vector(\"${scalaString(repositoryRoot.getAbsolutePath + "/plugin/target")}\", \"${scalaString(repositoryRoot.getAbsolutePath + "/plugin-api/target")}\", \"${scalaString(layout.sourceCopy.getAbsolutePath)}\")
       |      forbiddenPaths.foreach(fragment => require(!all.exists(_._2._4.getAbsolutePath.contains(fragment)), \"forbidden workspace path \" + fragment))
       |      val scalaCompilerVersions = all.collect { case (_, (o, m, v, _)) if o == \"org.scala-lang\" && m == \"scala3-compiler_3\" => v }.distinct
       |      require(scalaCompilerVersions == Vector(\"${config.scalaVersion}\"), \"exact Scala compiler universe mismatch: \" + scalaCompilerVersions)
       |      val graphLines = all.sortBy(value => (value._1, value._2._1, value._2._2, value._2._4.getAbsolutePath)).map { case (lane, (o, m, v, f)) => Seq(lane, o, m, v, f.getAbsolutePath).mkString(\"|\") }
       |      IO.write(target.value / \"externalConsumer-resolved-graph.txt\", graphLines.mkString(\"\\n\") + \"\\n\")
       |      IO.write(target.value / \"externalConsumer-plugin-options.txt\", options.mkString(\"\\n\") + \"\\n\")
       |      IO.write(target.value / \"externalConsumer-compile-classpath.txt\", compileClasspath.map(_.getAbsolutePath).sorted.mkString(\"\\n\") + \"\\n\")
       |    },
       |    externalConsumerRuntime := {
       |      val classpath = (Compile / fullClasspath).value.files.map(_.getAbsolutePath).mkString(File.pathSeparator)
       |      val java = new File(new File(System.getProperty(\"java.home\"), \"bin\"), \"java\").getAbsolutePath
       |      val output = Process(Vector(java, \"-cp\", classpath, \"contractprobeconsumer.IndependentPackagedConsumer\"), baseDirectory.value).!!
       |      require(output == \"IndependentConsumerUser\\n\", \"unexpected runtime output: \" + output.replace(\"\\n\", \"\\\\n\"))
       |      IO.write(target.value / \"externalConsumer-runtime.txt\", output)
       |    }
       |  )
       |""".stripMargin.replace("\\\"", "\"")
  }

  private def runSbt(
      directory: File,
      layout: Layout,
      commands: Vector[String],
      properties: Map[String, String],
      log: File
  ): Int = {
    val base = Vector(
      "sbt",
      "-batch",
      "-Dsbt.boot.directory=" + layout.boot.getAbsolutePath,
      "-Dsbt.global.base=" + layout.global.getAbsolutePath,
      "-Dsbt.ivy.home=" + layout.ivy.getAbsolutePath,
      "-Djava.io.tmpdir=" + layout.temporary.getAbsolutePath
    )
    val command = base ++ properties.toVector.sortBy(_._1).map { case (name, value) => "-D" + name + "=" + value } ++ commands
    val environment = Map(
      "COURSIER_CACHE" -> layout.coursier.getAbsolutePath,
      "IVY_HOME" -> layout.ivy.getAbsolutePath
    )
    runProcess(command, directory, environment, log)._1
  }

  private def runProcess(
      command: Seq[String],
      directory: File,
      environment: Map[String, String],
      log: File
  ): (Int, String) = {
    Files.createDirectories(log.toPath.getParent)
    val output = new StringBuilder
    val logger = ProcessLogger(line => output.append(line).append('\n'), line => output.append(line).append('\n'))
    val exit = Process(command, directory, environment.toSeq: _*).!(logger)
    val rendered = output.result()
    val safeEnvironment = environment.toVector.sortBy(_._1).map { case (name, value) => name + "=" + value }.mkString("\n")
    write(log, "DIRECTORY\n" + directory.getAbsolutePath + "\nENVIRONMENT\n" + safeEnvironment + "\nCOMMAND\n" + command.mkString("\n") + "\nOUTPUT\n" + rendered)
    (exit, rendered)
  }

  private def auditCommandLog(log: File, layout: Layout): Unit = {
    val value = read(log)
    val command = value.split("\nOUTPUT\n", 2).head
    require(!command.contains("publishLocal") && !command.contains("publishM2"), "forbidden global publication command was recorded")
    require(command.contains(layout.repository.getAbsolutePath), "publisher did not name the task-owned repository")
    require(!command.contains("sonatype") && !command.contains("deploy"), "publisher command named a deploy endpoint")
  }

  private def auditRepository(root: File, coordinates: Vector[Coordinate]): Vector[String] = {
    coordinates.foreach(coordinate => require(validateTaskCoordinate(coordinate).isEmpty, s"invalid coordinate ${coordinate.render}"))
    val files = regularRelativeFiles(root)
    require(files.nonEmpty, "task-owned Maven repository is empty")
    val allowedExtensions = Set("jar", "pom", "xml", "module", "sha1", "md5", "sha256")
    files.foreach { relative =>
      require(!relative.endsWith(".asc"), s"signature is forbidden: $relative")
      val coordinate = coordinates.find(value => relative.startsWith(value.rootRelative + "/")).getOrElse(throw new IllegalStateException(s"unrelated staged file: $relative"))
      val versionPath = coordinate.versionRelative + "/"
      val metadataPath = coordinate.rootRelative + "/maven-metadata"
      require(relative.startsWith(versionPath) || relative.startsWith(metadataPath), s"unexpected staged layout: $relative")
      val extension = relative.split('.').lastOption.getOrElse("")
      require(allowedExtensions.contains(extension), s"unexpected staged extension: $relative")
      if (relative.endsWith(".sha1")) verifyChecksum(root, relative, "SHA-1")
      if (relative.endsWith(".md5")) verifyChecksum(root, relative, "MD5")
      if (relative.endsWith(".sha256")) verifyChecksum(root, relative, "SHA-256")
    }
    coordinates.foreach { coordinate =>
      val versions = files.filter(_.startsWith(coordinate.rootRelative + "/")).flatMap { relative =>
        val suffix = relative.stripPrefix(coordinate.rootRelative + "/")
        suffix.split('/').headOption.filterNot(_.startsWith("maven-metadata"))
      }.distinct
      require(versions == Vector(Version), s"duplicate or unexpected versions for ${coordinate.render}: $versions")
      require(files.contains(coordinate.artifactRelative("jar")), s"missing staged JAR for ${coordinate.render}")
      require(files.contains(coordinate.artifactRelative("pom")), s"missing staged POM for ${coordinate.render}")
    }
    files
  }

  private def auditPoms(root: File, coordinates: Vector[Coordinate]): Vector[String] = {
    coordinates.map { coordinate =>
      val pom = new File(root, coordinate.artifactRelative("pom"))
      val dependencies = pomDependencies(pom)
      forbiddenModuleFragments.foreach(fragment => require(!dependencies.exists(_.module.contains(fragment)), s"POM ${coordinate.render} contains forbidden $fragment"))
      coordinate.module match {
        case PluginApiModule =>
          require(dependencies.exists(value => value.organization == "org.scala-lang" && value.module == "scala3-compiler_3" && value.version == ExpectedScalaVersion), "pluginApi POM lacks exact compiler dependency")
        case PluginModule =>
          require(!dependencies.exists(value => value.organization == RepositoryOrganization && value.module == PluginApiModule), "self-contained plugin POM retains an unnecessary pluginApi dependency")
        case MarkerModule =>
          require(dependencies.exists(value => value.organization == RepositoryOrganization && value.module == PluginApiModule && value.version == Version), "marker POM lacks selected pluginApi dependency")
          require(!dependencies.exists(_.module == PluginModule), "marker POM depends on plugin implementation")
        case HandlerModule =>
          require(dependencies.exists(value => value.organization == RepositoryOrganization && value.module == PluginApiModule && value.version == Version), "handler POM lacks selected pluginApi dependency")
          require(dependencies.exists(value => value.organization == "org.scala-lang" && value.module == "scala3-compiler_3" && value.version == ExpectedScalaVersion), "handler POM lacks exact compiler dependency")
          require(!dependencies.exists(_.module == PluginModule), "handler POM depends on plugin implementation")
        case other => throw new IllegalStateException("unclassified POM " + other)
      }
      coordinate.render + " dependencies=" + dependencies.map(_.render).sorted.mkString("[", ",", "]")
    }
  }

  private def pomDependencies(file: File): Vector[PomDependency] = {
    val factory = DocumentBuilderFactory.newInstance()
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    val document = factory.newDocumentBuilder().parse(file)
    val nodes = document.getElementsByTagName("dependency")
    (0 until nodes.getLength).toVector.map { index =>
      val element = nodes.item(index).asInstanceOf[org.w3c.dom.Element]
      def text(name: String): String = {
        val values = element.getElementsByTagName(name)
        if (values.getLength == 0) "" else values.item(0).getTextContent.trim
      }
      PomDependency(text("groupId"), text("artifactId"), text("version"), text("scope"))
    }
  }

  private def auditThinIndependentJar(file: File, expectedPayload: Set[String], label: String): Unit = {
    val entries = jarEntries(file)
    val payload = entries.filterNot(name => name.endsWith("/") || name == "META-INF/MANIFEST.MF").toSet
    require(payload == expectedPayload, s"$label producer payload changed: ${payload.toVector.sorted.mkString(",")}")
    require(entries.forall(name => !name.startsWith("paradise3/") && !name.startsWith("macroparadise/") && !name.startsWith("dotty/") && !name.startsWith("scala/")), s"$label producer bundled a forbidden universe")
  }

  private def auditSelfContainedPluginJar(plugin: File, api: File): Unit = {
    val pluginEntries = jarEntries(plugin)
    val embeddedApi = pluginEntries.filter(_.startsWith("paradise3/api/")).toSet
    val separateApi = jarEntries(api).filter(_.startsWith("paradise3/api/")).toSet
    require(embeddedApi == separateApi, "plugin does not embed the exact unshaded pluginApi inventory")
    require(
      !pluginEntries.exists(name => name.startsWith("shaded/") || name.contains("/shaded/")),
      "plugin introduced a shaded API identity"
    )
  }

  private def auditResolvedGraph(graph: Vector[String], repositoryRoot: File, layout: Layout): Unit = {
    val modules = graph.flatMap { line =>
      line.split("\\|", -1).toVector match {
        case Vector(_, organization, module, version, path) => Some((organization, module, version, path))
        case _ => None
      }
    }
    val errors = validateResolvedGraph(
      modules,
      Vector(
        new File(repositoryRoot, "plugin/target").getAbsolutePath,
        new File(repositoryRoot, "plugin-api/target").getAbsolutePath,
        layout.sourceCopy.getAbsolutePath
      )
    )
    require(errors.isEmpty, "resolved graph audit failed: " + errors.mkString("; "))
    val compilerVersions = modules.collect { case ("org.scala-lang", "scala3-compiler_3", version, _) => version }.distinct
    require(compilerVersions == Vector(ExpectedScalaVersion), s"compiler universe is not exact: $compilerVersions")
  }

  private def auditPluginOptions(options: Vector[String]): Unit = {
    val plugin = options.filter(_.startsWith("-Xplugin:"))
    val handler = options.filter(_.startsWith("-P:macroparadise:handlerClasspath="))
    val metadataTrace = options.filter(_.startsWith("-P:macroparadise:metadataReaderTrace="))
    val invocationTrace = options.filter(_.startsWith("-P:macroparadise:externalHandlerInvocationTrace="))
    require(plugin.size == 1 && plugin.head.contains(PluginModule) && !plugin.head.contains(PluginApiModule), "coordinate-resolved plugin option is invalid")
    require(options.count(_ == "-Xplugin-require:macroparadise") == 1, "plugin require option is invalid")
    require(handler.size == 1 && handler.head.contains(HandlerModule), "coordinate-resolved handler option is invalid")
    require(metadataTrace.size == 1, "coordinate-resolved metadata trace option is invalid")
    require(invocationTrace.size == 1, "coordinate-resolved invocation trace option is invalid")
    val rendered = options.mkString("\n")
    forbiddenModuleFragments.foreach(fragment => require(!rendered.contains(fragment), s"plugin options leaked $fragment"))
  }

  private def verifyParentFirstIdentity(plugin: File, handler: File, compilerJars: Vector[File], evidence: File): Boolean = {
    val parent = new URLClassLoader((plugin +: compilerJars).map(_.toURI.toURL).toArray, null)
    val child = new URLClassLoader(Array(handler.toURI.toURL), parent)
    try {
      val expectedApi = Class.forName("paradise3.api.ParadiseAnnotationExpander", false, parent)
      val handlerClass = Class.forName(ExpectedHandler, false, child)
      val childApi = Class.forName("paradise3.api.ParadiseAnnotationExpander", false, child)
      val singular = expectedApi eq childApi
      val assignable = expectedApi.isAssignableFrom(handlerClass)
      val codeSource = expectedApi.getProtectionDomain.getCodeSource.getLocation.toURI
      val pluginOwned = new File(codeSource).getCanonicalFile == plugin.getCanonicalFile
      require(singular && assignable && pluginOwned, "handler did not resolve the plugin-owned API identity")
      write(evidence, s"parentFirst=true\napiSingular=$singular\nhandlerAssignable=$assignable\npluginOwned=$pluginOwned\napiCodeSource=$codeSource\napiLoader=${loaderIdentity(expectedApi.getClassLoader)}\nhandlerLoader=${loaderIdentity(handlerClass.getClassLoader)}\n")
      true
    } finally {
      child.close()
      parent.close()
    }
  }

  private def createDuplicateApiRepository(api: File, root: File): Unit = {
    recreateDirectory(root.toPath)
    val coordinate = Coordinate(ProducerOrganization, DuplicateApiModule, Version)
    val jar = new File(root, coordinate.artifactRelative("jar"))
    Files.createDirectories(jar.toPath.getParent)
    val input = new ZipFile(api)
    val output = new ZipOutputStream(Files.newOutputStream(jar.toPath))
    try {
      input.entries().asScala.toVector.filter(entry => entry.getName.startsWith("paradise3/api/")).sortBy(_.getName).foreach { entry =>
        val copied = new ZipEntry(entry.getName)
        copied.setTime(0L)
        output.putNextEntry(copied)
        if (!entry.isDirectory) {
          val stream = input.getInputStream(entry)
          try copyStream(stream, output)
          finally stream.close()
        }
        output.closeEntry()
      }
    } finally {
      output.close()
      input.close()
    }
    val pom =
      s"""<project xmlns=\"http://maven.apache.org/POM/4.0.0\">
         |  <modelVersion>4.0.0</modelVersion>
         |  <groupId>$ProducerOrganization</groupId>
         |  <artifactId>$DuplicateApiModule</artifactId>
         |  <version>$Version</version>
         |  <packaging>jar</packaging>
         |</project>
         |""".stripMargin.replace("\\\"", "\"")
    write(new File(root, coordinate.artifactRelative("pom")), pom)
  }

  private def outputFilesAfterFailure(consumer: File): Vector[String] = {
    val candidates = findDirectoriesNamed(consumer, "classes")
    candidates.flatMap(regularRelativeFiles).distinct.sorted
  }

  private def requireNoUncontrolledFailure(value: String, lane: String): Unit = {
    Vector("internal compiler error", "ClassCastException", "Exception in thread", "java.lang.AssertionError").foreach(fragment =>
      require(!value.contains(fragment), s"$lane exposed uncontrolled $fragment")
    )
  }

  private def parseGraphFile(line: String): Option[File] =
    line.split("\\|", -1).toVector match {
      case Vector(_, _, _, _, path) => Some(new File(path))
      case _ => None
    }

  private def artifactIdentity(coordinate: Coordinate, root: File, file: File): ArtifactIdentity = {
    val entries = jarEntries(file)
    ArtifactIdentity(
      coordinate,
      root.toPath.relativize(file.toPath).toString.replace(File.separatorChar, '/'),
      file.length(),
      entries.size,
      entries.count(_.endsWith(".class")),
      entries.count(_.endsWith(".tasty")),
      digest(file, "SHA-256")
    )
  }

  private def validateConfig(config: Config): Unit = {
    require(config.scalaVersion == ExpectedScalaVersion, s"requires exact Scala $ExpectedScalaVersion")
    require(config.sbtVersion == ExpectedSbtVersion, s"requires sbt $ExpectedSbtVersion")
    require(config.projectVersion == Version, s"requires project $Version")
    require(Runtime.version().feature() == 25, "requires JDK 25")
    Vector(repositoryCoordinate(PluginApiModule), repositoryCoordinate(PluginModule), markerCoordinate, handlerCoordinate).foreach(coordinate =>
      require(validateTaskCoordinate(coordinate).isEmpty, s"invalid task coordinate ${coordinate.render}")
    )
  }

  private def boundedGlobalSnapshot(): Vector[String] = {
    val roots = Vector(
      new File(System.getProperty("user.home"), ".m2/repository"),
      new File(System.getProperty("user.home"), ".ivy2/local"),
      new File(System.getProperty("user.home"), ".cache/coursier/v1")
    )
    roots.flatMap { root =>
      if (!root.exists()) Vector(root.getAbsolutePath + "|ABSENT")
      else {
        val rootFact = root.getAbsolutePath + "|" + root.length() + "|" + root.lastModified()
        val children = Option(root.listFiles()).toVector.flatten.sortBy(_.getName).map(file => file.getAbsolutePath + "|" + file.length() + "|" + file.lastModified())
        rootFact +: children
      }
    }
  }

  private def repositoryTargetSnapshot(repositoryRoot: File): Vector[String] = {
    val targets = Vector.newBuilder[Path]
    Files.walkFileTree(repositoryRoot.toPath, new SimpleFileVisitor[Path] {
      override def preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult = {
        if (directory == repositoryRoot.toPath.resolve(".git")) FileVisitResult.SKIP_SUBTREE
        else if (directory.getFileName != null && directory.getFileName.toString == "target") {
          if (directory != repositoryRoot.toPath.resolve("target"))
            targets += directory
          FileVisitResult.SKIP_SUBTREE
        } else FileVisitResult.CONTINUE
      }
    })
    targets.result().map { directory =>
      val stream = Files.walk(directory)
      val facts = try stream.iterator().asScala.filter(Files.isRegularFile(_)).flatMap { file =>
        val relative = directory.relativize(file).toString.replace(File.separatorChar, '/')
        if (isStableTargetPayload(relative))
          Some(
            relative + "|" + Files.size(file) + "|" +
              hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)))
          )
        else None
      }.toVector.sorted
      finally stream.close()
      val relative = repositoryRoot.toPath.relativize(directory).toString.replace(File.separatorChar, '/')
      relative + "|files=" + facts.size + "|sha256=" + sha256Text(facts.mkString("\n"))
    }.sorted
  }

  def isStableTargetPayload(relativePath: String): Boolean = {
    val normalized = relativePath.replace('\\', '/')
    val segments = normalized.split('/').toSet
    val volatileSegments = Set(
      "streams",
      "zinc",
      "update",
      "sync",
      "global-logging",
      "task-temp-directory",
      "resolution-cache",
      "test-reports"
    )
    val stableSuffixes = Vector(".class", ".tasty", ".jar", ".pom", ".sjsir")
    segments.intersect(volatileSegments).isEmpty && stableSuffixes.exists(normalized.endsWith)
  }

  private def sha256Text(value: String): String =
    hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)))

  private def verifyChecksum(root: File, relative: String, algorithm: String): Unit = {
    val checksum = new File(root, relative)
    val suffix = "." + relative.split('.').last
    val subject = new File(root, relative.stripSuffix(suffix))
    require(subject.isFile, s"checksum subject missing: $relative")
    require(read(checksum).trim.equalsIgnoreCase(digest(subject, algorithm)), s"checksum mismatch: $relative")
  }

  private def zipEntryFacts(file: File): Vector[(String, String, Long, Long, Int)] = {
    val zip = new ZipFile(file)
    try zip.entries().asScala.toVector.sortBy(_.getName).map { entry =>
      val stream = zip.getInputStream(entry)
      val bytes = try readAll(stream) finally stream.close()
      (entry.getName, hex(MessageDigest.getInstance("SHA-256").digest(bytes)), entry.getTime, entry.getCrc, entry.getMethod)
    }
    finally zip.close()
  }

  private def singleFile(root: File, name: String): File = {
    val stream = Files.walk(root.toPath)
    try exactlyOne(stream.iterator().asScala.filter(path => Files.isRegularFile(path) && path.getFileName.toString == name).map(_.toFile).toVector, name)
    finally stream.close()
  }

  private def singleDirectory(root: File, name: String): File =
    exactlyOne(findDirectoriesNamed(root, name).filter(file => file.getAbsolutePath.contains("scala-")), name)

  private def findDirectoriesNamed(root: File, name: String): Vector[File] = {
    if (!root.exists()) Vector.empty
    else {
      val stream = Files.walk(root.toPath)
      try stream.iterator().asScala.filter(path => Files.isDirectory(path) && path.getFileName.toString == name).map(_.toFile).toVector
      finally stream.close()
    }
  }

  private def exactlyOne[A](values: Vector[A], label: String): A = {
    require(values.size == 1, s"$label expected exactly once, found ${values.size}: ${values.mkString(",")}")
    values.head
  }

  private def jarEntries(file: File): Vector[String] = {
    val jar = new JarFile(file)
    try jar.entries().asScala.map(_.getName).toVector
    finally jar.close()
  }

  private def regularRelativeFiles(root: File): Vector[String] = {
    if (!root.exists()) Vector.empty
    else {
      val stream = Files.walk(root.toPath)
      try stream.iterator().asScala.filter(Files.isRegularFile(_)).map(path => root.toPath.relativize(path).toString.replace(File.separatorChar, '/')).toVector.sorted
      finally stream.close()
    }
  }

  private def sameBytes(left: File, right: File): Boolean =
    java.util.Arrays.equals(Files.readAllBytes(left.toPath), Files.readAllBytes(right.toPath))

  private def read(file: File): String =
    new String(Files.readAllBytes(file.toPath), StandardCharsets.UTF_8)

  private def readLines(file: File): Vector[String] =
    if (file.isFile) Files.readAllLines(file.toPath, StandardCharsets.UTF_8).asScala.toVector else Vector.empty

  private def write(file: File, value: String): Unit = {
    Files.createDirectories(file.toPath.getParent)
    Files.write(file.toPath, value.getBytes(StandardCharsets.UTF_8))
  }

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

  private def copyStream(input: java.io.InputStream, output: java.io.OutputStream): Unit = {
    val buffer = new Array[Byte](8192)
    var count = input.read(buffer)
    while (count >= 0) {
      if (count > 0) output.write(buffer, 0, count)
      count = input.read(buffer)
    }
  }

  private def readAll(input: java.io.InputStream): Array[Byte] = {
    val output = new java.io.ByteArrayOutputStream()
    copyStream(input, output)
    output.toByteArray
  }

  private def digest(file: File, algorithm: String): String = {
    val digest = MessageDigest.getInstance(algorithm)
    val input = new FileInputStream(file)
    try {
      val buffer = new Array[Byte](8192)
      var count = input.read(buffer)
      while (count >= 0) {
        if (count > 0) digest.update(buffer, 0, count)
        count = input.read(buffer)
      }
    } finally input.close()
    hex(digest.digest())
  }

  private def hex(bytes: Array[Byte]): String =
    bytes.map(byte => f"${byte & 0xff}%02x").mkString

  private def scalaString(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")

  private def javaTool(name: String): String =
    new File(new File(System.getProperty("java.home"), "bin"), name).getAbsolutePath

  private def loaderIdentity(loader: ClassLoader): String =
    if (loader == null) "bootstrap" else loader.getClass.getName + "@" + Integer.toHexString(System.identityHashCode(loader))
}
