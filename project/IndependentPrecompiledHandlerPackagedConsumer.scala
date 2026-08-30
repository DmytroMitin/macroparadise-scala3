import java.io.{ByteArrayOutputStream, File, InputStream}
import java.net.{URL, URLClassLoader}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.jar.JarFile
import java.util.zip.{CRC32, ZipEntry, ZipOutputStream}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.sys.process.{Process, ProcessLogger}

object IndependentPrecompiledHandlerPackagedConsumer {
  val ReadyClassification = "INDEPENDENT_PRECOMPILED_HANDLER_END_TO_END_PACKAGED_CONSUMER_READY"
  val MetadataClassification = "INDEPENDENT_MARKER_METADATA_DISCOVERY_AND_HANDLER_INVOCATION_PROVEN"
  val ContractClassification = "CONTRACT_ONLY_PLUGIN_API_SUFFICIENT_FOR_EXTERNAL_END_TO_END_USE"
  val ExpectedScalaVersion = ExactBuildIdentity.SelectedScalaVersion
  val ExpectedSbtVersion = "1.12.15"
  val ExpectedProjectVersion = ExactBuildIdentity.DevelopmentVersion
  val IndependentArtifactBasename =
    s"independent-marker-handler_3-$ExpectedProjectVersion.jar"
  val BodyViewIndependentArtifactBasename =
    s"independent-body-view-marker-handler_3-$ExpectedProjectVersion.jar"
  val TypePlacementIndependentArtifactBasename =
    s"independent-type-placement-marker-handler_3-$ExpectedProjectVersion.jar"
  val ModulePlacementIndependentArtifactBasename =
    s"independent-module-placement-marker-handler_3-$ExpectedProjectVersion.jar"
  val SelfTraitIndependentArtifactBasename =
    s"independent-self-trait-marker-handler_3-$ExpectedProjectVersion.jar"
  val MetadataValue = "contractprobe.IndependentHandler"
  val HandlerAnnotationName = "IndependentMarker"
  val ExpectedRuntimeOutput = "IndependentConsumerUser\n"
  val ExpectedBodyViewRuntimeOutput = "show\n"
  val ExpectedTypePlacementRuntimeOutput = "true\ntrue\n7\npreserved\n"
  val ExpectedModulePlacementRuntimeOutput = "placed\nplaced\n7\npreserved\n"
  val ExpectedSelfTraitRuntimeOutput =
    "anonymous|original\nexisting|original\ncollision|original\n"
  val deterministicTimestamp = LocalDateTime.of(1980, 1, 1, 0, 0)
  val deterministicManifest =
    "Manifest-Version: 1.0\r\n" +
      "Created-By: macroparadise-scala3 independent packaged consumer verifier\r\n" +
      "\r\n"

  val expectedCompiledEntries = Set(
    "contractprobe/IndependentHandler.class",
    "contractprobe/IndependentHandler.tasty",
    "contractprobe/IndependentMarker.class",
    "contractprobe/IndependentMarker.tasty"
  )
  val expectedBodyViewCompiledEntries = Set(
    "contractprobebody/IndependentBodyViewHandler.class",
    "contractprobebody/IndependentBodyViewHandler.tasty",
    "contractprobebody/IndependentBodyViewMarker.class",
    "contractprobebody/IndependentBodyViewMarker.tasty"
  )
  val expectedTypePlacementCompiledEntries = Set(
    "contractprobetype/IndependentTypePlacementHandler.class",
    "contractprobetype/IndependentTypePlacementHandler.tasty",
    "contractprobetype/IndependentTypePlacementMarker.class",
    "contractprobetype/IndependentTypePlacementMarker.tasty",
    "contractprobetype/IndependentTypePlacementRejectHandler.class",
    "contractprobetype/IndependentTypePlacementRejectHandler.tasty",
    "contractprobetype/IndependentTypePlacementRejectMarker.class",
    "contractprobetype/IndependentTypePlacementRejectMarker.tasty"
  )
  val expectedModulePlacementCompiledEntries = Set(
    "contractprobemodule/IndependentModulePlacementHandler.class",
    "contractprobemodule/IndependentModulePlacementHandler.tasty",
    "contractprobemodule/IndependentModulePlacementMarker.class",
    "contractprobemodule/IndependentModulePlacementMarker.tasty",
    "contractprobemodule/IndependentModulePlacementRejectHandler.class",
    "contractprobemodule/IndependentModulePlacementRejectHandler.tasty",
    "contractprobemodule/IndependentModulePlacementRejectMarker.class",
    "contractprobemodule/IndependentModulePlacementRejectMarker.tasty"
  )
  val expectedSelfTraitCompiledEntries = Set(
    "contractprobeself/IndependentSelfTraitHandler.class",
    "contractprobeself/IndependentSelfTraitHandler.tasty",
    "contractprobeself/IndependentSelfTraitMarker.class",
    "contractprobeself/IndependentSelfTraitMarker.tasty"
  )

  val forbiddenClasspathFragments = Vector(
    "plugin-test-markers",
    "plugin-test-handlers",
    "plugin-tests/target",
    "legacy-metadata",
    "quasiquotes",
    "experimental-plugin-api-handler-contract"
  )

  final case class Config(scalaVersion: String, sbtVersion: String, projectVersion: String)

  final case class ArtifactIdentity(
      path: File,
      bytes: Long,
      entries: Int,
      classCount: Int,
      tastyCount: Int,
      sha256: String
  ) {
    def render: String =
      s"path=${path.getAbsolutePath} bytes=$bytes entries=$entries classes=$classCount tasty=$tastyCount sha256=$sha256"
  }

  final case class CompileEvidence(
      firstExit: Int,
      secondExit: Int,
      outputFiles: Vector[String],
      inventoriesEqual: Boolean
  ) {
    def render: String =
      s"firstExit=$firstExit secondExit=$secondExit outputFiles=${outputFiles.mkString(",")} inventoriesEqual=$inventoriesEqual"
  }

  final case class MetadataEvidence(
      annotationClass: String,
      value: String,
      handlerParent: String,
      markerLoadedWithoutInitialization: Boolean,
      handlerNotLoadedDuringLookup: Boolean,
      apiIdentityShared: Boolean
  ) {
    def render: String =
      s"annotationClass=$annotationClass value=$value handlerParent=$handlerParent markerLoadedWithoutInitialization=$markerLoadedWithoutInitialization handlerNotLoadedDuringLookup=$handlerNotLoadedDuringLookup apiIdentityShared=$apiIdentityShared"
  }

  final case class PositiveEvidence(
      exitCode: Int,
      outputFiles: Vector[String],
      metadataSelectionCount: Int,
      invocationCount: Int,
      generatedMethodPresent: Boolean
  ) {
    def render: String =
      s"exit=$exitCode outputFiles=${outputFiles.mkString(",")} metadataSelectionCount=$metadataSelectionCount invocationCount=$invocationCount generatedMethodPresent=$generatedMethodPresent"
  }

  final case class BodyViewEvidence(
      artifact: ArtifactIdentity,
      compile: CompileEvidence,
      exitCode: Int,
      outputFiles: Vector[String],
      metadataSelectionCount: Int,
      invocationCount: Int,
      runtimeExit: Int,
      runtimeOutput: String,
      generatedCompanionMethodPresent: Boolean
  ) {
    def render: String =
      s"artifact={${artifact.render}} compile={${compile.render}} exit=$exitCode " +
        s"outputFiles=${outputFiles.mkString(",")} metadataSelectionCount=$metadataSelectionCount " +
        s"invocationCount=$invocationCount runtimeExit=$runtimeExit " +
        s"runtimeOutput=${runtimeOutput.trim} generatedCompanionMethodPresent=$generatedCompanionMethodPresent"
  }

  final case class TypePlacementPositiveEvidence(
      exitCode: Int,
      outputFiles: Vector[String],
      metadataSelectionCount: Int,
      invocationCount: Int,
      existingCompanionMemberPresent: Boolean,
      generatedAliasesTypechecked: Boolean
  ) {
    def render: String =
      s"exit=$exitCode outputFiles=${outputFiles.mkString(",")} metadataSelectionCount=$metadataSelectionCount " +
        s"invocationCount=$invocationCount existingCompanionMemberPresent=$existingCompanionMemberPresent " +
        s"generatedAliasesTypechecked=$generatedAliasesTypechecked"
  }

  final case class TypePlacementEvidence(
      artifact: ArtifactIdentity,
      compile: CompileEvidence,
      positive: TypePlacementPositiveEvidence,
      runtimeExit: Int,
      runtimeOutput: String,
      rejection: NegativeEvidence
  ) {
    def render: String =
      s"artifact={${artifact.render}} compile={${compile.render}} positive={${positive.render}} " +
      s"runtimeExit=$runtimeExit runtimeOutput=${runtimeOutput.trim.replace("\n", "|")} rejection={${rejection.render}}"
  }

  final case class ModulePlacementPositiveEvidence(
      exitCode: Int,
      outputFiles: Vector[String],
      metadataSelectionCount: Int,
      invocationCount: Int,
      existingCompanionMemberPresent: Boolean,
      generatedModulesTypechecked: Boolean
  ) {
    def render: String =
      s"exit=$exitCode outputFiles=${outputFiles.mkString(",")} metadataSelectionCount=$metadataSelectionCount " +
        s"invocationCount=$invocationCount existingCompanionMemberPresent=$existingCompanionMemberPresent " +
        s"generatedModulesTypechecked=$generatedModulesTypechecked"
  }

  final case class ModulePlacementEvidence(
      artifact: ArtifactIdentity,
      compile: CompileEvidence,
      positive: ModulePlacementPositiveEvidence,
      runtimeExit: Int,
      runtimeOutput: String,
      rejection: NegativeEvidence
  ) {
    def render: String =
      s"artifact={${artifact.render}} compile={${compile.render}} positive={${positive.render}} " +
        s"runtimeExit=$runtimeExit runtimeOutput=${runtimeOutput.trim.replace("\n", "|")} rejection={${rejection.render}}"
  }

  final case class SelfTraitEvidence(
      artifact: ArtifactIdentity,
      compile: CompileEvidence,
      exitCode: Int,
      outputFiles: Vector[String],
      metadataSelectionCount: Int,
      invocationCount: Int,
      runtimeExit: Int,
      runtimeOutput: String,
      rejection: NegativeEvidence
  ) {
    def render: String =
      s"artifact={${artifact.render}} compile={${compile.render}} exit=$exitCode " +
        s"outputFiles=${outputFiles.mkString(",")} metadataSelectionCount=$metadataSelectionCount " +
        s"invocationCount=$invocationCount runtimeExit=$runtimeExit " +
        s"runtimeOutput=${runtimeOutput.trim.replace("\n", "|")} rejection={${rejection.render}}"
  }

  final case class NegativeEvidence(id: String, exitCode: Int, diagnostic: String, outputFiles: Int) {
    def render: String = s"$id(exit=$exitCode diagnostic=$diagnostic outputFiles=$outputFiles)"
  }

  final case class ClassloaderEvidence(
      positiveParentFirst: Boolean,
      duplicateApiRejectedByIdentity: Boolean,
      duplicateHandlerImplementsDuplicateApi: Boolean
  ) {
    def render: String =
      s"positiveParentFirst=$positiveParentFirst duplicateApiRejectedByIdentity=$duplicateApiRejectedByIdentity duplicateHandlerImplementsDuplicateApi=$duplicateHandlerImplementsDuplicateApi"
  }

  final case class VerificationResult(
      apiArtifact: ArtifactIdentity,
      pluginArtifact: ArtifactIdentity,
      independentArtifact: ArtifactIdentity,
      compile: CompileEvidence,
      metadata: MetadataEvidence,
      positive: PositiveEvidence,
      bodyView: BodyViewEvidence,
      typePlacement: TypePlacementEvidence,
      modulePlacement: ModulePlacementEvidence,
      selfTrait: SelfTraitEvidence,
      runtimeExit: Int,
      runtimeOutput: String,
      runtimeUsesIndependentArtifact: Boolean,
      negatives: Vector[NegativeEvidence],
      classloader: ClassloaderEvidence,
      modelCases: Int,
      evidenceDirectory: File
  ) {
    def render: String =
      s"classification=$ReadyClassification metadataClassification=$MetadataClassification contractClassification=$ContractClassification " +
        s"api={${apiArtifact.render}} plugin={${pluginArtifact.render}} independent={${independentArtifact.render}} " +
        s"compile={${compile.render}} metadata={${metadata.render}} positive={${positive.render}} bodyView={${bodyView.render}} " +
        s"typePlacement={${typePlacement.render}} modulePlacement={${modulePlacement.render}} selfTrait={${selfTrait.render}} " +
        s"runtimeExit=$runtimeExit runtimeOutput=${runtimeOutput.trim} runtimeUsesIndependentArtifact=$runtimeUsesIndependentArtifact " +
        s"negatives=${negatives.map(_.render).mkString(",")} classloader={${classloader.render}} modelCases=$modelCases"
  }

  def verify(
      repositoryRoot: File,
      apiArtifact: File,
      pluginArtifact: File,
      apiDependencyClasspath: Seq[File],
      pluginDependencyClasspath: Seq[File],
      independentSource: File,
      consumerSource: File,
      evidenceDirectory: File,
      config: Config,
      modelCases: Int
  ): VerificationResult = {
    validateConfig(config)
    recreateDirectory(evidenceDirectory.toPath)
    require(independentSource.isFile, s"missing independent source: $independentSource")
    require(consumerSource.isFile, s"missing consumer source: $consumerSource")
    val bodyViewConsumerSource = new File(
      repositoryRoot,
      "plugin-api-handler-contract-probe/e2e-body-view/IndependentBodyViewConsumer.scala"
    )
    val bodyViewHandlerSource = new File(
      repositoryRoot,
      "plugin-api-handler-contract-probe/body-view/IndependentBodyViewMarkerAndHandler.scala"
    )
    val bodyViewNegativeSource = new File(
      repositoryRoot,
      "plugin-api-handler-contract-probe/e2e-body-view-negative/UnsupportedBodyViewConsumer.scala"
    )
    val typePlacementHandlerSource = new File(
      repositoryRoot,
      "plugin-api-handler-contract-probe/type-placement/IndependentTypePlacementMarkerAndHandler.scala"
    )
    val typePlacementConsumerSource = new File(
      repositoryRoot,
      "plugin-api-handler-contract-probe/e2e-type-placement/IndependentTypePlacementConsumer.scala"
    )
    val typePlacementRejectSource = new File(
      repositoryRoot,
      "plugin-api-handler-contract-probe/e2e-type-placement-reject/IndependentTypePlacementRejectConsumer.scala"
    )
    val modulePlacementHandlerSource = new File(
      repositoryRoot,
      "plugin-api-handler-contract-probe/module-placement/IndependentModulePlacementMarkerAndHandler.scala"
    )
    val modulePlacementConsumerSource = new File(
      repositoryRoot,
      "plugin-api-handler-contract-probe/e2e-module-placement/IndependentModulePlacementConsumer.scala"
    )
    val modulePlacementRejectSource = new File(
      repositoryRoot,
      "plugin-api-handler-contract-probe/e2e-module-placement-reject/IndependentModulePlacementRejectConsumer.scala"
    )
    val selfTraitHandlerSource = new File(
      repositoryRoot,
      "plugin-api-handler-contract-probe/self-trait/IndependentSelfTraitMarkerAndHandler.scala"
    )
    val selfTraitConsumerSource = new File(
      repositoryRoot,
      "plugin-api-handler-contract-probe/e2e-self-trait/IndependentSelfTraitConsumer.scala"
    )
    val selfTraitRejectSource = new File(
      repositoryRoot,
      "plugin-api-handler-contract-probe/e2e-self-trait-reject/IndependentSelfTraitRejectConsumer.scala"
    )
    require(bodyViewHandlerSource.isFile, s"missing body-view handler source: $bodyViewHandlerSource")
    require(bodyViewConsumerSource.isFile, s"missing body-view consumer source: $bodyViewConsumerSource")
    require(bodyViewNegativeSource.isFile, s"missing body-view negative source: $bodyViewNegativeSource")
    require(typePlacementHandlerSource.isFile, s"missing type-placement handler source: $typePlacementHandlerSource")
    require(typePlacementConsumerSource.isFile, s"missing type-placement consumer source: $typePlacementConsumerSource")
    require(typePlacementRejectSource.isFile, s"missing type-placement reject source: $typePlacementRejectSource")
    require(modulePlacementHandlerSource.isFile, s"missing module-placement handler source: $modulePlacementHandlerSource")
    require(modulePlacementConsumerSource.isFile, s"missing module-placement consumer source: $modulePlacementConsumerSource")
    require(modulePlacementRejectSource.isFile, s"missing module-placement reject source: $modulePlacementRejectSource")
    require(selfTraitHandlerSource.isFile, s"missing self-trait handler source: $selfTraitHandlerSource")
    require(selfTraitConsumerSource.isFile, s"missing self-trait consumer source: $selfTraitConsumerSource")
    require(selfTraitRejectSource.isFile, s"missing self-trait reject source: $selfTraitRejectSource")
    val compilerJars = compilerClasspath(repositoryRoot, apiDependencyClasspath ++ pluginDependencyClasspath, config)
    validateClasspath("compiler universe", compilerJars, allowApi = false)
    validateClasspath("independent compile", apiArtifact +: compilerJars, allowApi = true)

    val firstOutput = new File(evidenceDirectory, "independent-compile/first-classes")
    val secondOutput = new File(evidenceDirectory, "independent-compile/second-classes")
    recreateDirectory(firstOutput.toPath)
    recreateDirectory(secondOutput.toPath)
    val firstExit = compilePlain(repositoryRoot, compilerJars, apiArtifact, independentSource, firstOutput, new File(evidenceDirectory, "independent-compile/first.log"))
    val secondExit = compilePlain(repositoryRoot, compilerJars, apiArtifact, independentSource, secondOutput, new File(evidenceDirectory, "independent-compile/second.log"))
    require(firstExit == 0 && secondExit == 0, s"independent compile exits were $firstExit and $secondExit")
    val firstFiles = regularRelativeFiles(firstOutput)
    val secondFiles = regularRelativeFiles(secondOutput)
    require(firstFiles.toSet == expectedCompiledEntries, s"unexpected independent output: ${firstFiles.mkString(", ")}")
    require(secondFiles == firstFiles, s"independent output inventory drifted: ${secondFiles.mkString(", ")}")
    val compileEvidence = CompileEvidence(firstExit, secondExit, firstFiles, inventoriesEqual = true)

    val firstJar = new File(evidenceDirectory, s"independent-artifact/render-one/$IndependentArtifactBasename")
    val secondJar = new File(evidenceDirectory, s"independent-artifact/render-two/$IndependentArtifactBasename")
    renderThinArtifact(firstOutput, firstJar, expectedCompiledEntries, "contractprobe/")
    renderThinArtifact(firstOutput, secondJar, expectedCompiledEntries, "contractprobe/")
    require(java.util.Arrays.equals(Files.readAllBytes(firstJar.toPath), Files.readAllBytes(secondJar.toPath)), "thin artifact renders are not byte-identical")
    val independentIdentity = thinArtifactIdentity(firstJar, expectedCompiledEntries, "contractprobe/")

    val bodyFirstOutput = new File(evidenceDirectory, "body-view-handler-compile/first-classes")
    val bodySecondOutput = new File(evidenceDirectory, "body-view-handler-compile/second-classes")
    recreateDirectory(bodyFirstOutput.toPath)
    recreateDirectory(bodySecondOutput.toPath)
    val bodyFirstExit = compilePlain(repositoryRoot, compilerJars, apiArtifact, bodyViewHandlerSource, bodyFirstOutput, new File(evidenceDirectory, "body-view-handler-compile/first.log"))
    val bodySecondExit = compilePlain(repositoryRoot, compilerJars, apiArtifact, bodyViewHandlerSource, bodySecondOutput, new File(evidenceDirectory, "body-view-handler-compile/second.log"))
    require(bodyFirstExit == 0 && bodySecondExit == 0, s"independent body-view handler compile exits were $bodyFirstExit and $bodySecondExit")
    val bodyFirstFiles = regularRelativeFiles(bodyFirstOutput)
    val bodySecondFiles = regularRelativeFiles(bodySecondOutput)
    require(bodyFirstFiles.toSet == expectedBodyViewCompiledEntries, s"unexpected body-view handler output: ${bodyFirstFiles.mkString(", ")}")
    require(bodySecondFiles == bodyFirstFiles, s"body-view handler output inventory drifted: ${bodySecondFiles.mkString(", ")}")
    val bodyCompileEvidence = CompileEvidence(bodyFirstExit, bodySecondExit, bodyFirstFiles, inventoriesEqual = true)
    val bodyFirstJar = new File(evidenceDirectory, s"body-view-handler-artifact/render-one/$BodyViewIndependentArtifactBasename")
    val bodySecondJar = new File(evidenceDirectory, s"body-view-handler-artifact/render-two/$BodyViewIndependentArtifactBasename")
    renderThinArtifact(bodyFirstOutput, bodyFirstJar, expectedBodyViewCompiledEntries, "contractprobebody/")
    renderThinArtifact(bodyFirstOutput, bodySecondJar, expectedBodyViewCompiledEntries, "contractprobebody/")
    require(java.util.Arrays.equals(Files.readAllBytes(bodyFirstJar.toPath), Files.readAllBytes(bodySecondJar.toPath)), "body-view thin artifact renders are not byte-identical")
    val bodyIndependentIdentity = thinArtifactIdentity(bodyFirstJar, expectedBodyViewCompiledEntries, "contractprobebody/")

    val typeFirstOutput = new File(evidenceDirectory, "type-placement-handler-compile/first-classes")
    val typeSecondOutput = new File(evidenceDirectory, "type-placement-handler-compile/second-classes")
    recreateDirectory(typeFirstOutput.toPath)
    recreateDirectory(typeSecondOutput.toPath)
    val typeFirstExit = compilePlain(repositoryRoot, compilerJars, apiArtifact, typePlacementHandlerSource, typeFirstOutput, new File(evidenceDirectory, "type-placement-handler-compile/first.log"))
    val typeSecondExit = compilePlain(repositoryRoot, compilerJars, apiArtifact, typePlacementHandlerSource, typeSecondOutput, new File(evidenceDirectory, "type-placement-handler-compile/second.log"))
    require(typeFirstExit == 0 && typeSecondExit == 0, s"independent type-placement handler compile exits were $typeFirstExit and $typeSecondExit")
    val typeFirstFiles = regularRelativeFiles(typeFirstOutput)
    val typeSecondFiles = regularRelativeFiles(typeSecondOutput)
    require(typeFirstFiles.toSet == expectedTypePlacementCompiledEntries, s"unexpected type-placement handler output: ${typeFirstFiles.mkString(", ")}")
    require(typeSecondFiles == typeFirstFiles, s"type-placement handler output inventory drifted: ${typeSecondFiles.mkString(", ")}")
    val typeCompileEvidence = CompileEvidence(typeFirstExit, typeSecondExit, typeFirstFiles, inventoriesEqual = true)
    val typeFirstJar = new File(evidenceDirectory, s"type-placement-handler-artifact/render-one/$TypePlacementIndependentArtifactBasename")
    val typeSecondJar = new File(evidenceDirectory, s"type-placement-handler-artifact/render-two/$TypePlacementIndependentArtifactBasename")
    renderThinArtifact(typeFirstOutput, typeFirstJar, expectedTypePlacementCompiledEntries, "contractprobetype/")
    renderThinArtifact(typeFirstOutput, typeSecondJar, expectedTypePlacementCompiledEntries, "contractprobetype/")
    require(java.util.Arrays.equals(Files.readAllBytes(typeFirstJar.toPath), Files.readAllBytes(typeSecondJar.toPath)), "type-placement thin artifact renders are not byte-identical")
    val typeIndependentIdentity = thinArtifactIdentity(typeFirstJar, expectedTypePlacementCompiledEntries, "contractprobetype/")

    val moduleFirstOutput = new File(evidenceDirectory, "module-placement-handler-compile/first-classes")
    val moduleSecondOutput = new File(evidenceDirectory, "module-placement-handler-compile/second-classes")
    recreateDirectory(moduleFirstOutput.toPath)
    recreateDirectory(moduleSecondOutput.toPath)
    val moduleFirstExit = compilePlain(repositoryRoot, compilerJars, apiArtifact, modulePlacementHandlerSource, moduleFirstOutput, new File(evidenceDirectory, "module-placement-handler-compile/first.log"))
    val moduleSecondExit = compilePlain(repositoryRoot, compilerJars, apiArtifact, modulePlacementHandlerSource, moduleSecondOutput, new File(evidenceDirectory, "module-placement-handler-compile/second.log"))
    require(moduleFirstExit == 0 && moduleSecondExit == 0, s"independent module-placement handler compile exits were $moduleFirstExit and $moduleSecondExit")
    val moduleFirstFiles = regularRelativeFiles(moduleFirstOutput)
    val moduleSecondFiles = regularRelativeFiles(moduleSecondOutput)
    require(moduleFirstFiles.toSet == expectedModulePlacementCompiledEntries, s"unexpected module-placement handler output: ${moduleFirstFiles.mkString(", ")}")
    require(moduleSecondFiles == moduleFirstFiles, s"module-placement handler output inventory drifted: ${moduleSecondFiles.mkString(", ")}")
    val moduleCompileEvidence = CompileEvidence(moduleFirstExit, moduleSecondExit, moduleFirstFiles, inventoriesEqual = true)
    val moduleFirstJar = new File(evidenceDirectory, s"module-placement-handler-artifact/render-one/$ModulePlacementIndependentArtifactBasename")
    val moduleSecondJar = new File(evidenceDirectory, s"module-placement-handler-artifact/render-two/$ModulePlacementIndependentArtifactBasename")
    renderThinArtifact(moduleFirstOutput, moduleFirstJar, expectedModulePlacementCompiledEntries, "contractprobemodule/")
    renderThinArtifact(moduleFirstOutput, moduleSecondJar, expectedModulePlacementCompiledEntries, "contractprobemodule/")
    require(java.util.Arrays.equals(Files.readAllBytes(moduleFirstJar.toPath), Files.readAllBytes(moduleSecondJar.toPath)), "module-placement thin artifact renders are not byte-identical")
    val moduleIndependentIdentity = thinArtifactIdentity(moduleFirstJar, expectedModulePlacementCompiledEntries, "contractprobemodule/")

    val selfFirstOutput = new File(evidenceDirectory, "self-trait-handler-compile/first-classes")
    val selfSecondOutput = new File(evidenceDirectory, "self-trait-handler-compile/second-classes")
    recreateDirectory(selfFirstOutput.toPath)
    recreateDirectory(selfSecondOutput.toPath)
    val selfFirstExit = compilePlain(repositoryRoot, compilerJars, apiArtifact, selfTraitHandlerSource, selfFirstOutput, new File(evidenceDirectory, "self-trait-handler-compile/first.log"))
    val selfSecondExit = compilePlain(repositoryRoot, compilerJars, apiArtifact, selfTraitHandlerSource, selfSecondOutput, new File(evidenceDirectory, "self-trait-handler-compile/second.log"))
    require(selfFirstExit == 0 && selfSecondExit == 0, s"independent self-trait handler compile exits were $selfFirstExit and $selfSecondExit")
    val selfFirstFiles = regularRelativeFiles(selfFirstOutput)
    val selfSecondFiles = regularRelativeFiles(selfSecondOutput)
    require(selfFirstFiles.toSet == expectedSelfTraitCompiledEntries, s"unexpected self-trait handler output: ${selfFirstFiles.mkString(", ")}")
    require(selfSecondFiles == selfFirstFiles, s"self-trait handler output inventory drifted: ${selfSecondFiles.mkString(", ")}")
    val selfCompileEvidence = CompileEvidence(selfFirstExit, selfSecondExit, selfFirstFiles, inventoriesEqual = true)
    val selfFirstJar = new File(evidenceDirectory, s"self-trait-handler-artifact/render-one/$SelfTraitIndependentArtifactBasename")
    val selfSecondJar = new File(evidenceDirectory, s"self-trait-handler-artifact/render-two/$SelfTraitIndependentArtifactBasename")
    renderThinArtifact(selfFirstOutput, selfFirstJar, expectedSelfTraitCompiledEntries, "contractprobeself/")
    renderThinArtifact(selfFirstOutput, selfSecondJar, expectedSelfTraitCompiledEntries, "contractprobeself/")
    require(java.util.Arrays.equals(Files.readAllBytes(selfFirstJar.toPath), Files.readAllBytes(selfSecondJar.toPath)), "self-trait thin artifact renders are not byte-identical")
    val selfIndependentIdentity = thinArtifactIdentity(selfFirstJar, expectedSelfTraitCompiledEntries, "contractprobeself/")

    val apiIdentity = artifactIdentity(apiArtifact)
    val pluginIdentity = artifactIdentity(pluginArtifact)
    val metadata = verifyMetadataAndIdentity(apiArtifact, independentIdentity.path, compilerJars)

    val positive = compilePositive(repositoryRoot, compilerJars, apiArtifact, pluginArtifact, independentIdentity.path, consumerSource, evidenceDirectory)
    val runtime = runRuntime(repositoryRoot, compilerJars, apiArtifact, positiveOutput(evidenceDirectory), evidenceDirectory)
    val bodyView = compileBodyViewPositive(
      repositoryRoot,
      compilerJars,
      apiArtifact,
      pluginArtifact,
      bodyIndependentIdentity,
      bodyCompileEvidence,
      bodyViewConsumerSource,
      evidenceDirectory
    )
    val typePlacementPositive = compileTypePlacementPositive(
      repositoryRoot,
      compilerJars,
      apiArtifact,
      pluginArtifact,
      typeIndependentIdentity.path,
      typePlacementConsumerSource,
      evidenceDirectory
    )
    val typePlacementRuntime = runMain(
      repositoryRoot,
      compilerJars,
      apiArtifact,
      typePlacementPositiveOutput(evidenceDirectory),
      "contractprobetypeconsumer.IndependentTypePlacementConsumer",
      ExpectedTypePlacementRuntimeOutput,
      new File(evidenceDirectory, "type-placement-positive/runtime.log")
    )
    val typePlacementReject = compileTypePlacementReject(
      repositoryRoot,
      compilerJars,
      apiArtifact,
      pluginArtifact,
      typeIndependentIdentity.path,
      typePlacementRejectSource,
      evidenceDirectory
    )
    val typePlacement = TypePlacementEvidence(
      typeIndependentIdentity,
      typeCompileEvidence,
      typePlacementPositive,
      typePlacementRuntime._1,
      typePlacementRuntime._2,
      typePlacementReject
    )
    val modulePlacementPositive = compileModulePlacementPositive(
      repositoryRoot,
      compilerJars,
      apiArtifact,
      pluginArtifact,
      moduleIndependentIdentity.path,
      modulePlacementConsumerSource,
      evidenceDirectory
    )
    val modulePlacementRuntime = runMain(
      repositoryRoot,
      compilerJars,
      apiArtifact,
      modulePlacementPositiveOutput(evidenceDirectory),
      "contractprobemoduleconsumer.IndependentModulePlacementConsumer",
      ExpectedModulePlacementRuntimeOutput,
      new File(evidenceDirectory, "module-placement-positive/runtime.log")
    )
    val modulePlacementReject = compileModulePlacementReject(
      repositoryRoot,
      compilerJars,
      apiArtifact,
      pluginArtifact,
      moduleIndependentIdentity.path,
      modulePlacementRejectSource,
      evidenceDirectory
    )
    val modulePlacement = ModulePlacementEvidence(
      moduleIndependentIdentity,
      moduleCompileEvidence,
      modulePlacementPositive,
      modulePlacementRuntime._1,
      modulePlacementRuntime._2,
      modulePlacementReject
    )
    val selfTraitPositive = compileSelfTraitPositive(
      repositoryRoot,
      compilerJars,
      apiArtifact,
      pluginArtifact,
      selfIndependentIdentity.path,
      selfTraitConsumerSource,
      evidenceDirectory
    )
    val selfTraitRuntime = runMain(
      repositoryRoot,
      compilerJars,
      apiArtifact,
      selfTraitPositiveOutput(evidenceDirectory),
      "contractprobeselfconsumer.IndependentSelfTraitConsumer",
      ExpectedSelfTraitRuntimeOutput,
      new File(evidenceDirectory, "self-trait-positive/runtime.log")
    )
    val selfTraitReject = compileSelfTraitReject(
      repositoryRoot,
      compilerJars,
      apiArtifact,
      pluginArtifact,
      selfIndependentIdentity.path,
      selfTraitRejectSource,
      evidenceDirectory
    )
    val selfTrait = SelfTraitEvidence(
      selfIndependentIdentity,
      selfCompileEvidence,
      selfTraitPositive._1,
      selfTraitPositive._2,
      selfTraitPositive._3,
      selfTraitPositive._4,
      selfTraitRuntime._1,
      selfTraitRuntime._2,
      selfTraitReject
    )
    val negatives = Vector(
      compileMissingHandler(repositoryRoot, compilerJars, apiArtifact, pluginArtifact, independentIdentity.path, consumerSource, evidenceDirectory),
      compileMissingMarker(repositoryRoot, compilerJars, apiArtifact, pluginArtifact, independentIdentity.path, consumerSource, evidenceDirectory),
      compileUnsupportedBodyView(
        repositoryRoot,
        compilerJars,
        apiArtifact,
        pluginArtifact,
        bodyIndependentIdentity.path,
        bodyViewNegativeSource,
        evidenceDirectory
      ),
      typePlacementReject,
      modulePlacementReject,
      selfTraitReject
    )
    val classloader = verifyClassloaderMismatch(apiArtifact, independentIdentity.path, compilerJars)

    val result = VerificationResult(
      apiIdentity,
      pluginIdentity,
      independentIdentity,
      compileEvidence,
      metadata,
      positive,
      bodyView,
      typePlacement,
      modulePlacement,
      selfTrait,
      runtime._1,
      runtime._2,
      runtimeUsesIndependentArtifact = false,
      negatives,
      classloader,
      modelCases,
      evidenceDirectory
    )
    write(new File(evidenceDirectory, "summary.txt").toPath, result.render + "\n")
    result
  }

  private def compilePlain(
      repositoryRoot: File,
      compilerJars: Vector[File],
      apiArtifact: File,
      source: File,
      output: File,
      log: File
  ): Int = {
    val command = Vector(
      javaTool("java"), "-cp", classpath(compilerJars), "dotty.tools.dotc.Main",
      "-classpath", classpath(apiArtifact +: compilerJars), "-d", output.getAbsolutePath,
      source.getAbsolutePath
    )
    runProcess(command, repositoryRoot, log)._1
  }

  private def compilePositive(
      repositoryRoot: File,
      compilerJars: Vector[File],
      apiArtifact: File,
      pluginArtifact: File,
      independentArtifact: File,
      source: File,
      evidenceDirectory: File
  ): PositiveEvidence = {
    val output = positiveOutput(evidenceDirectory)
    recreateDirectory(output.toPath)
    val metadataTrace = new File(evidenceDirectory, "positive/metadata.trace")
    val invocationTrace = new File(evidenceDirectory, "positive/invocation.trace")
    val command = pluginCompileCommand(
      compilerJars, apiArtifact, pluginArtifact, Some(independentArtifact), Some(independentArtifact),
      source, output,
      Vector(
        s"-P:macroparadise:metadataReaderTrace=${metadataTrace.getAbsolutePath}",
        s"-P:macroparadise:externalHandlerInvocationTrace=${invocationTrace.getAbsolutePath}"
      )
    )
    validatePluginCommand(command, apiArtifact, pluginArtifact, independentArtifact, requireHandler = true)
    val (exit, _) = runProcess(command, repositoryRoot, new File(evidenceDirectory, "positive/compile.log"))
    require(exit == 0, s"independent consumer compile failed with exit $exit")
    val outputs = regularRelativeFiles(output)
    val required = Set(
      "contractprobeconsumer/IndependentConsumerUser.class",
      "contractprobeconsumer/IndependentConsumerUser.tasty",
      "contractprobeconsumer/IndependentPackagedConsumer.class",
      "contractprobeconsumer/IndependentPackagedConsumer$.class",
      "contractprobeconsumer/IndependentPackagedConsumer.tasty"
    )
    require(required.subsetOf(outputs.toSet), s"consumer output is missing: ${(required -- outputs.toSet).mkString(", ")}")
    require(outputs.forall(_.startsWith("contractprobeconsumer/")), s"consumer output leaked fixtures: ${outputs.mkString(", ")}")
    val metadataLines = readLines(metadataTrace)
    val selectionCount = metadataLines.count(line => line.contains("contractprobe.IndependentMarker") && line.contains("Found(contractprobe.IndependentHandler)"))
    require(selectionCount == 1, s"expected one independent metadata selection, found $selectionCount: ${metadataLines.mkString(" | ")}")
    val invocationLines = readLines(invocationTrace).filter(_.contains("handler=contractprobe.IndependentHandler"))
    require(invocationLines.size == 1, s"expected one independent handler invocation, found ${invocationLines.size}: ${invocationLines.mkString(" | ")}")
    val javap = runProcess(
      Vector(javaTool("javap"), "-classpath", output.getAbsolutePath, "contractprobeconsumer.IndependentConsumerUser"),
      repositoryRoot,
      new File(evidenceDirectory, "positive/javap.log")
    )
    require(javap._1 == 0 && javap._2.contains("java.lang.String independentHandlerName()"), s"generated method missing after typer: ${javap._2}")
    PositiveEvidence(exit, outputs, selectionCount, invocationLines.size, generatedMethodPresent = true)
  }

  private def compileBodyViewPositive(
      repositoryRoot: File,
      compilerJars: Vector[File],
      apiArtifact: File,
      pluginArtifact: File,
      independentArtifact: ArtifactIdentity,
      handlerCompile: CompileEvidence,
      source: File,
      evidenceDirectory: File
  ): BodyViewEvidence = {
    val output = bodyViewPositiveOutput(evidenceDirectory)
    recreateDirectory(output.toPath)
    val metadataTrace = new File(evidenceDirectory, "body-view-positive/metadata.trace")
    val invocationTrace = new File(evidenceDirectory, "body-view-positive/invocation.trace")
    val command = pluginCompileCommand(
      compilerJars,
      apiArtifact,
      pluginArtifact,
      Some(independentArtifact.path),
      Some(independentArtifact.path),
      source,
      output,
      Vector(
        s"-P:macroparadise:metadataReaderTrace=${metadataTrace.getAbsolutePath}",
        s"-P:macroparadise:externalHandlerInvocationTrace=${invocationTrace.getAbsolutePath}"
      )
    )
    validatePluginCommand(command, apiArtifact, pluginArtifact, independentArtifact.path, requireHandler = true)
    val (exit, _) = runProcess(command, repositoryRoot, new File(evidenceDirectory, "body-view-positive/compile.log"))
    require(exit == 0, s"independent body-view consumer compile failed with exit $exit")
    val outputs = regularRelativeFiles(output)
    val required = Set(
      "contractprobeconsumer/IndependentShow.class",
      "contractprobeconsumer/IndependentShow.tasty",
      "contractprobeconsumer/IndependentShow$.class",
      "contractprobeconsumer/IndependentBodyViewConsumer.class",
      "contractprobeconsumer/IndependentBodyViewConsumer$.class",
      "contractprobeconsumer/IndependentBodyViewConsumer.tasty"
    )
    require(required.subsetOf(outputs.toSet), s"body-view consumer output is missing: ${(required -- outputs.toSet).mkString(", ")}")
    require(outputs.forall(_.startsWith("contractprobeconsumer/")), s"body-view output leaked fixtures: ${outputs.mkString(", ")}")
    val metadataLines = readLines(metadataTrace)
    val selectionCount = metadataLines.count(line => line.contains("contractprobebody.IndependentBodyViewMarker") && line.contains("Found(contractprobebody.IndependentBodyViewHandler)"))
    require(selectionCount == 1, s"expected one body-view metadata selection, found $selectionCount: ${metadataLines.mkString(" | ")}")
    val invocationLines = readLines(invocationTrace).filter(_.contains("handler=contractprobebody.IndependentBodyViewHandler"))
    require(invocationLines.size == 1, s"expected one body-view handler invocation, found ${invocationLines.size}: ${invocationLines.mkString(" | ")}")
    val javap = runProcess(
      Vector(javaTool("javap"), "-classpath", output.getAbsolutePath, "contractprobeconsumer.IndependentShow$"),
      repositoryRoot,
      new File(evidenceDirectory, "body-view-positive/javap.log")
    )
    require(javap._1 == 0 && javap._2.contains("java.lang.String independentBodyView()"), s"generated body-view companion method missing after typer: ${javap._2}")
    val runtime = runMain(
      repositoryRoot,
      compilerJars,
      apiArtifact,
      output,
      "contractprobeconsumer.IndependentBodyViewConsumer",
      ExpectedBodyViewRuntimeOutput,
      new File(evidenceDirectory, "body-view-positive/runtime.log")
    )
    BodyViewEvidence(
      independentArtifact,
      handlerCompile,
      exit,
      outputs,
      selectionCount,
      invocationLines.size,
      runtime._1,
      runtime._2,
      generatedCompanionMethodPresent = true
    )
  }

  private def compileTypePlacementPositive(
      repositoryRoot: File,
      compilerJars: Vector[File],
      apiArtifact: File,
      pluginArtifact: File,
      independentArtifact: File,
      source: File,
      evidenceDirectory: File
  ): TypePlacementPositiveEvidence = {
    val output = typePlacementPositiveOutput(evidenceDirectory)
    recreateDirectory(output.toPath)
    val metadataTrace = new File(evidenceDirectory, "type-placement-positive/metadata.trace")
    val invocationTrace = new File(evidenceDirectory, "type-placement-positive/invocation.trace")
    val command = pluginCompileCommand(
      compilerJars,
      apiArtifact,
      pluginArtifact,
      Some(independentArtifact),
      Some(independentArtifact),
      source,
      output,
      Vector(
        s"-P:macroparadise:metadataReaderTrace=${metadataTrace.getAbsolutePath}",
        s"-P:macroparadise:externalHandlerInvocationTrace=${invocationTrace.getAbsolutePath}"
      )
    )
    validatePluginCommand(command, apiArtifact, pluginArtifact, independentArtifact, requireHandler = true)
    val (exit, log) = runProcess(command, repositoryRoot, new File(evidenceDirectory, "type-placement-positive/compile.log"))
    require(exit == 0, s"independent type-placement consumer compile failed with exit $exit: $log")
    val outputs = regularRelativeFiles(output)
    val required = Set(
      "contractprobetypeconsumer/MissingCompanionAdd.class",
      "contractprobetypeconsumer/MissingCompanionAdd$.class",
      "contractprobetypeconsumer/MissingCompanionAdd.tasty",
      "contractprobetypeconsumer/ExistingCompanionAdd.class",
      "contractprobetypeconsumer/ExistingCompanionAdd$.class",
      "contractprobetypeconsumer/ExistingCompanionAdd.tasty",
      "contractprobetypeconsumer/PreserveConflictAdd.class",
      "contractprobetypeconsumer/PreserveConflictAdd$.class",
      "contractprobetypeconsumer/PreserveConflictAdd.tasty",
      "contractprobetypeconsumer/IndependentTypePlacementConsumer.class",
      "contractprobetypeconsumer/IndependentTypePlacementConsumer$.class",
      "contractprobetypeconsumer/IndependentTypePlacementConsumer.tasty"
    )
    require(required.subsetOf(outputs.toSet), s"type-placement consumer output is missing: ${(required -- outputs.toSet).mkString(", ")}")
    require(outputs.forall(_.startsWith("contractprobetypeconsumer/")), s"type-placement output leaked fixtures: ${outputs.mkString(", ")}")
    val metadataLines = readLines(metadataTrace)
    val selectionCount = metadataLines.count(line =>
      line.contains("contractprobetype.IndependentTypePlacementMarker") &&
        line.contains("Found(contractprobetype.IndependentTypePlacementHandler)")
    )
    require(selectionCount == 1, s"expected one cached type-placement metadata selection, found $selectionCount: ${metadataLines.mkString(" | ")}")
    val invocationLines = readLines(invocationTrace).filter(_.contains("handler=contractprobetype.IndependentTypePlacementHandler"))
    require(invocationLines.size == 3, s"expected three type-placement handler invocations, found ${invocationLines.size}: ${invocationLines.mkString(" | ")}")
    val javap = runProcess(
      Vector(javaTool("javap"), "-classpath", output.getAbsolutePath, "contractprobetypeconsumer.ExistingCompanionAdd$"),
      repositoryRoot,
      new File(evidenceDirectory, "type-placement-positive/javap.log")
    )
    require(javap._1 == 0 && javap._2.contains("int existingValue()"), s"existing companion member missing after type placement: ${javap._2}")
    TypePlacementPositiveEvidence(
      exit,
      outputs,
      selectionCount,
      invocationLines.size,
      existingCompanionMemberPresent = true,
      generatedAliasesTypechecked = true
    )
  }

  private def compileTypePlacementReject(
      repositoryRoot: File,
      compilerJars: Vector[File],
      apiArtifact: File,
      pluginArtifact: File,
      independentArtifact: File,
      source: File,
      evidenceDirectory: File
  ): NegativeEvidence = {
    val output = new File(evidenceDirectory, "type-placement-reject/classes")
    recreateDirectory(output.toPath)
    val invocationTrace = new File(evidenceDirectory, "type-placement-reject/invocation.trace")
    val command = pluginCompileCommand(
      compilerJars,
      apiArtifact,
      pluginArtifact,
      Some(independentArtifact),
      Some(independentArtifact),
      source,
      output,
      Vector(s"-P:macroparadise:externalHandlerInvocationTrace=${invocationTrace.getAbsolutePath}")
    )
    validatePluginCommand(command, apiArtifact, pluginArtifact, independentArtifact, requireHandler = true)
    val (exit, log) = runProcess(command, repositoryRoot, new File(evidenceDirectory, "type-placement-reject/compile.log"))
    val diagnostic = "generated companion type `Aux` conflicts with existing direct companion type member `Aux` for `RejectConflictAdd`"
    require(exit != 0, "type-placement reject lane unexpectedly compiled")
    require(log.contains(diagnostic), s"type-placement reject lane lacked controlled diagnostic: $log")
    require(!log.contains("internal compiler error") && !log.contains("ClassCastException") && !log.contains("Exception in thread"), s"type-placement reject lane exposed an uncontrolled failure: $log")
    val invocationLines = readLines(invocationTrace).filter(_.contains("handler=contractprobetype.IndependentTypePlacementRejectHandler"))
    require(invocationLines.size == 1, s"expected one rejecting type-placement invocation, found ${invocationLines.size}: ${invocationLines.mkString(" | ")}")
    val outputs = regularRelativeFiles(output)
    require(outputs.isEmpty, s"type-placement reject lane emitted partial output: ${outputs.mkString(", ")}")
    NegativeEvidence("direct-type-conflict-reject", exit, diagnostic, outputs.size)
  }

  private def compileModulePlacementPositive(
      repositoryRoot: File,
      compilerJars: Vector[File],
      apiArtifact: File,
      pluginArtifact: File,
      independentArtifact: File,
      source: File,
      evidenceDirectory: File
  ): ModulePlacementPositiveEvidence = {
    val output = modulePlacementPositiveOutput(evidenceDirectory)
    recreateDirectory(output.toPath)
    val metadataTrace = new File(evidenceDirectory, "module-placement-positive/metadata.trace")
    val invocationTrace = new File(evidenceDirectory, "module-placement-positive/invocation.trace")
    val command = pluginCompileCommand(
      compilerJars,
      apiArtifact,
      pluginArtifact,
      Some(independentArtifact),
      Some(independentArtifact),
      source,
      output,
      Vector(
        s"-P:macroparadise:metadataReaderTrace=${metadataTrace.getAbsolutePath}",
        s"-P:macroparadise:externalHandlerInvocationTrace=${invocationTrace.getAbsolutePath}"
      )
    )
    validatePluginCommand(command, apiArtifact, pluginArtifact, independentArtifact, requireHandler = true)
    val (exit, log) = runProcess(command, repositoryRoot, new File(evidenceDirectory, "module-placement-positive/compile.log"))
    require(exit == 0, s"independent module-placement consumer compile failed with exit $exit: $log")
    val outputs = regularRelativeFiles(output)
    val required = Set(
      "contractprobemoduleconsumer/MissingModuleAdd.class",
      "contractprobemoduleconsumer/MissingModuleAdd$.class",
      "contractprobemoduleconsumer/MissingModuleAdd.tasty",
      "contractprobemoduleconsumer/ExistingModuleAdd.class",
      "contractprobemoduleconsumer/ExistingModuleAdd$.class",
      "contractprobemoduleconsumer/ExistingModuleAdd.tasty",
      "contractprobemoduleconsumer/PreserveModuleConflict.class",
      "contractprobemoduleconsumer/PreserveModuleConflict$.class",
      "contractprobemoduleconsumer/PreserveModuleConflict.tasty",
      "contractprobemoduleconsumer/IndependentModulePlacementConsumer.class",
      "contractprobemoduleconsumer/IndependentModulePlacementConsumer$.class",
      "contractprobemoduleconsumer/IndependentModulePlacementConsumer.tasty"
    )
    require(required.subsetOf(outputs.toSet), s"module-placement consumer output is missing: ${(required -- outputs.toSet).mkString(", ")}")
    require(outputs.forall(_.startsWith("contractprobemoduleconsumer/")), s"module-placement output leaked fixtures: ${outputs.mkString(", ")}")
    val metadataLines = readLines(metadataTrace)
    val selectionCount = metadataLines.count(line =>
      line.contains("contractprobemodule.IndependentModulePlacementMarker") &&
        line.contains("Found(contractprobemodule.IndependentModulePlacementHandler)")
    )
    require(selectionCount == 1, s"expected one cached module-placement metadata selection, found $selectionCount: ${metadataLines.mkString(" | ")}")
    val invocationLines = readLines(invocationTrace).filter(_.contains("handler=contractprobemodule.IndependentModulePlacementHandler"))
    require(invocationLines.size == 3, s"expected three module-placement handler invocations, found ${invocationLines.size}: ${invocationLines.mkString(" | ")}")
    val javap = runProcess(
      Vector(javaTool("javap"), "-classpath", output.getAbsolutePath, "contractprobemoduleconsumer.ExistingModuleAdd$"),
      repositoryRoot,
      new File(evidenceDirectory, "module-placement-positive/javap.log")
    )
    require(javap._1 == 0 && javap._2.contains("int existingValue()"), s"existing companion member missing after module placement: ${javap._2}")
    ModulePlacementPositiveEvidence(
      exit,
      outputs,
      selectionCount,
      invocationLines.size,
      existingCompanionMemberPresent = true,
      generatedModulesTypechecked = true
    )
  }

  private def compileModulePlacementReject(
      repositoryRoot: File,
      compilerJars: Vector[File],
      apiArtifact: File,
      pluginArtifact: File,
      independentArtifact: File,
      source: File,
      evidenceDirectory: File
  ): NegativeEvidence = {
    val output = new File(evidenceDirectory, "module-placement-reject/classes")
    recreateDirectory(output.toPath)
    val invocationTrace = new File(evidenceDirectory, "module-placement-reject/invocation.trace")
    val command = pluginCompileCommand(
      compilerJars,
      apiArtifact,
      pluginArtifact,
      Some(independentArtifact),
      Some(independentArtifact),
      source,
      output,
      Vector(s"-P:macroparadise:externalHandlerInvocationTrace=${invocationTrace.getAbsolutePath}")
    )
    validatePluginCommand(command, apiArtifact, pluginArtifact, independentArtifact, requireHandler = true)
    val (exit, log) = runProcess(command, repositoryRoot, new File(evidenceDirectory, "module-placement-reject/compile.log"))
    val diagnostic = "generated companion module `syntax` conflicts with existing direct companion term member `syntax` for `RejectModuleConflict`"
    require(exit != 0, "module-placement reject lane unexpectedly compiled")
    require(log.contains(diagnostic), s"module-placement reject lane lacked controlled diagnostic: $log")
    require(!log.contains("internal compiler error") && !log.contains("ClassCastException") && !log.contains("Exception in thread"), s"module-placement reject lane exposed an uncontrolled failure: $log")
    val invocationLines = readLines(invocationTrace).filter(_.contains("handler=contractprobemodule.IndependentModulePlacementRejectHandler"))
    require(invocationLines.size == 1, s"expected one rejecting module-placement invocation, found ${invocationLines.size}: ${invocationLines.mkString(" | ")}")
    val outputs = regularRelativeFiles(output)
    require(outputs.isEmpty, s"module-placement reject lane emitted partial output: ${outputs.mkString(", ")}")
    NegativeEvidence("direct-module-term-conflict-reject", exit, diagnostic, outputs.size)
  }

  private def compileSelfTraitPositive(
      repositoryRoot: File,
      compilerJars: Vector[File],
      apiArtifact: File,
      pluginArtifact: File,
      independentArtifact: File,
      source: File,
      evidenceDirectory: File
  ): (Int, Vector[String], Int, Int) = {
    val output = selfTraitPositiveOutput(evidenceDirectory)
    recreateDirectory(output.toPath)
    val metadataTrace = new File(evidenceDirectory, "self-trait-positive/metadata.trace")
    val invocationTrace = new File(evidenceDirectory, "self-trait-positive/invocation.trace")
    val command = pluginCompileCommand(
      compilerJars,
      apiArtifact,
      pluginArtifact,
      Some(independentArtifact),
      Some(independentArtifact),
      source,
      output,
      Vector(
        s"-P:macroparadise:metadataReaderTrace=${metadataTrace.getAbsolutePath}",
        s"-P:macroparadise:externalHandlerInvocationTrace=${invocationTrace.getAbsolutePath}"
      )
    )
    validatePluginCommand(command, apiArtifact, pluginArtifact, independentArtifact, requireHandler = true)
    val (exit, log) = runProcess(command, repositoryRoot, new File(evidenceDirectory, "self-trait-positive/compile.log"))
    require(exit == 0, s"independent self-trait consumer compile failed with exit $exit: $log")
    val outputs = regularRelativeFiles(output)
    val required = Set(
      "contractprobeselfconsumer/AnonymousNat.class",
      "contractprobeselfconsumer/AnonymousNat.tasty",
      "contractprobeselfconsumer/ExistingNamedNat.class",
      "contractprobeselfconsumer/ExistingNamedNat.tasty",
      "contractprobeselfconsumer/CollisionNat.class",
      "contractprobeselfconsumer/CollisionNat.tasty",
      "contractprobeselfconsumer/IndependentSelfTraitConsumer.class",
      "contractprobeselfconsumer/IndependentSelfTraitConsumer$.class",
      "contractprobeselfconsumer/IndependentSelfTraitConsumer.tasty"
    )
    require(required.subsetOf(outputs.toSet), s"self-trait consumer output is missing: ${(required -- outputs.toSet).mkString(", ")}")
    require(outputs.forall(_.startsWith("contractprobeselfconsumer/")), s"self-trait output leaked fixtures: ${outputs.mkString(", ")}")
    val metadataLines = readLines(metadataTrace)
    val selectionCount = metadataLines.count(line =>
      line.contains("contractprobeself.IndependentSelfTraitMarker") &&
        line.contains("Found(contractprobeself.IndependentSelfTraitHandler)")
    )
    require(selectionCount == 1, s"expected one cached self-trait metadata selection, found $selectionCount: ${metadataLines.mkString(" | ")}")
    val invocationLines = readLines(invocationTrace).filter(_.contains("handler=contractprobeself.IndependentSelfTraitHandler"))
    require(invocationLines.size == 3, s"expected three self-trait handler invocations, found ${invocationLines.size}: ${invocationLines.mkString(" | ")}")
    (exit, outputs, selectionCount, invocationLines.size)
  }

  private def compileSelfTraitReject(
      repositoryRoot: File,
      compilerJars: Vector[File],
      apiArtifact: File,
      pluginArtifact: File,
      independentArtifact: File,
      source: File,
      evidenceDirectory: File
  ): NegativeEvidence = {
    val output = new File(evidenceDirectory, "self-trait-reject/classes")
    recreateDirectory(output.toPath)
    val invocationTrace = new File(evidenceDirectory, "self-trait-reject/invocation.trace")
    val command = pluginCompileCommand(
      compilerJars,
      apiArtifact,
      pluginArtifact,
      Some(independentArtifact),
      Some(independentArtifact),
      source,
      output,
      Vector(s"-P:macroparadise:externalHandlerInvocationTrace=${invocationTrace.getAbsolutePath}")
    )
    validatePluginCommand(command, apiArtifact, pluginArtifact, independentArtifact, requireHandler = true)
    val (exit, log) = runProcess(command, repositoryRoot, new File(evidenceDirectory, "self-trait-reject/compile.log"))
    val diagnostic =
      "trait `RejectSelfNat` already contains direct type member `Self`; bounded self preparation requires deterministic rejection"
    val classDiagnostic =
      "@IndependentSelfTraitMarker requires one top-level non-sealed ordinary trait with zero type parameters and no constructor/value parameters; found class `RejectSelfClass`"
    val objectDiagnostic =
      "unsupported target `object RejectSelfObject`"
    val enumDiagnostic =
      "unsupported target `enum RejectSelfEnum`"
    require(exit != 0, "self-trait direct-Self reject lane unexpectedly compiled")
    require(log.contains(diagnostic), s"self-trait reject lane lacked controlled diagnostic: $log")
    require(log.contains(classDiagnostic), s"self-trait reject lane lacked class structural diagnostic: $log")
    require(log.contains(objectDiagnostic), s"self-trait reject lane lacked object structural diagnostic: $log")
    require(log.contains(enumDiagnostic), s"self-trait reject lane lacked enum structural diagnostic: $log")
    require(!log.contains("direct Self preflight invoked lowering callback"), s"self-trait reject lane invoked lowering before direct-Self preflight: $log")
    require(!log.contains("internal compiler error") && !log.contains("ClassCastException") && !log.contains("Exception in thread"), s"self-trait reject lane exposed an uncontrolled failure: $log")
    val invocationLines = readLines(invocationTrace).filter(_.contains("handler=contractprobeself.IndependentSelfTraitHandler"))
    require(invocationLines.size == 1, s"expected one rejecting self-trait invocation, found ${invocationLines.size}: ${invocationLines.mkString(" | ")}")
    val outputs = regularRelativeFiles(output)
    require(outputs.isEmpty, s"self-trait reject lane emitted partial output: ${outputs.mkString(", ")}")
    NegativeEvidence("direct-Self-type-conflict-reject", exit, diagnostic, outputs.size)
  }

  private def compileUnsupportedBodyView(
      repositoryRoot: File,
      compilerJars: Vector[File],
      apiArtifact: File,
      pluginArtifact: File,
      independentArtifact: File,
      source: File,
      evidenceDirectory: File
  ): NegativeEvidence = {
    val output = new File(evidenceDirectory, "body-view-negative/classes")
    recreateDirectory(output.toPath)
    val command = pluginCompileCommand(
      compilerJars,
      apiArtifact,
      pluginArtifact,
      Some(independentArtifact),
      Some(independentArtifact),
      source,
      output,
      Vector.empty
    )
    validatePluginCommand(command, apiArtifact, pluginArtifact, independentArtifact, requireHandler = true)
    val (exit, log) = runProcess(command, repositoryRoot, new File(evidenceDirectory, "body-view-negative/compile.log"))
    val diagnostic = "unsupported direct body shape for IndependentBodyViewMarker"
    require(exit != 0, "unsupported body-view lane unexpectedly compiled")
    require(log.contains(diagnostic), s"unsupported body-view lane lacked controlled diagnostic: $log")
    require(!log.contains("internal compiler error") && !log.contains("ClassCastException") && !log.contains("Exception in thread"), s"unsupported body-view lane exposed an uncontrolled failure: $log")
    val outputs = regularRelativeFiles(output)
    require(outputs.isEmpty, s"unsupported body-view lane emitted partial output: ${outputs.mkString(", ")}")
    NegativeEvidence("unsupported-direct-body-type", exit, diagnostic, outputs.size)
  }

  private def compileMissingHandler(
      repositoryRoot: File,
      compilerJars: Vector[File],
      apiArtifact: File,
      pluginArtifact: File,
      independentArtifact: File,
      source: File,
      evidenceDirectory: File
  ): NegativeEvidence = {
    val output = new File(evidenceDirectory, "negative-missing-handler/classes")
    recreateDirectory(output.toPath)
    val command = pluginCompileCommand(compilerJars, apiArtifact, pluginArtifact, Some(independentArtifact), None, source, output, Vector.empty)
    validatePluginCommand(command, apiArtifact, pluginArtifact, independentArtifact, requireHandler = false)
    val (exit, log) = runProcess(command, repositoryRoot, new File(evidenceDirectory, "negative-missing-handler/compile.log"))
    val diagnostic = "handlerClasspathConfigured=false handlerClasspathEntries=0"
    require(exit != 0, "missing-handler lane unexpectedly compiled")
    require(log.contains(diagnostic), s"missing-handler lane lacked controlled diagnostic: $log")
    require(!log.contains("internal compiler error") && !log.contains("ClassCastException") && !log.contains("Exception in thread"), s"missing-handler lane exposed an uncontrolled failure: $log")
    val outputs = regularRelativeFiles(output)
    require(outputs.isEmpty, s"missing-handler lane emitted partial output: ${outputs.mkString(", ")}")
    NegativeEvidence("missing-handler-classpath", exit, diagnostic, outputs.size)
  }

  private def compileMissingMarker(
      repositoryRoot: File,
      compilerJars: Vector[File],
      apiArtifact: File,
      pluginArtifact: File,
      independentArtifact: File,
      source: File,
      evidenceDirectory: File
  ): NegativeEvidence = {
    val output = new File(evidenceDirectory, "negative-missing-marker/classes")
    recreateDirectory(output.toPath)
    val command = pluginCompileCommand(compilerJars, apiArtifact, pluginArtifact, None, Some(independentArtifact), source, output, Vector.empty)
    val (exit, log) = runProcess(command, repositoryRoot, new File(evidenceDirectory, "negative-missing-marker/compile.log"))
    require(exit != 0, "missing-marker lane unexpectedly compiled")
    val accepted = Vector("Not found: contractprobe", "value contractprobe", "Not found: type IndependentMarker", "Not found: IndependentMarker")
    val diagnostic = accepted.find(log.contains).getOrElse(throw new IllegalStateException(s"missing-marker lane lacked ordinary missing-type diagnostic: $log"))
    require(!log.contains("internal compiler error") && !log.contains("ClassCastException") && !log.contains("Exception in thread"), s"missing-marker lane exposed an uncontrolled failure: $log")
    val outputs = regularRelativeFiles(output)
    require(outputs.isEmpty, s"missing-marker lane emitted partial output: ${outputs.mkString(", ")}")
    NegativeEvidence("missing-marker-artifact", exit, diagnostic, outputs.size)
  }

  private def pluginCompileCommand(
      compilerJars: Vector[File],
      apiArtifact: File,
      pluginArtifact: File,
      markerOnCompileClasspath: Option[File],
      handlerClasspath: Option[File],
      source: File,
      output: File,
      extraPluginOptions: Vector[String]
  ): Vector[String] = {
    val sourceClasspath = compilerJars ++ Vector(apiArtifact) ++ markerOnCompileClasspath.toVector
    Vector(javaTool("java")) ++ Vector(
      "-cp", classpath(compilerJars), "dotty.tools.dotc.Main",
      "-classpath", classpath(sourceClasspath), "-d", output.getAbsolutePath,
      s"-Xplugin:${pluginArtifact.getAbsolutePath}",
      "-Xplugin-require:macroparadise"
    ) ++ handlerClasspath.toVector.map(file => s"-P:macroparadise:handlerClasspath=${file.getAbsolutePath}") ++ extraPluginOptions ++ Vector(source.getAbsolutePath)
  }

  private def validatePluginCommand(
      command: Vector[String],
      apiArtifact: File,
      pluginArtifact: File,
      independentArtifact: File,
      requireHandler: Boolean
  ): Unit = {
    val rendered = command.mkString("\n")
    forbiddenClasspathFragments.foreach(fragment => require(!rendered.contains(fragment), s"plugin command leaked forbidden `$fragment`"))
    val pluginOption = command.find(_.startsWith("-Xplugin:")).getOrElse(throw new IllegalStateException("plugin command lacks -Xplugin"))
    require(pluginOption == s"-Xplugin:${pluginArtifact.getAbsolutePath}", s"plugin path changed: $pluginOption")
    require(command.contains("-Xplugin-require:macroparadise"), "plugin command lacks -Xplugin-require")
    val handlerOptions = command.filter(_.startsWith("-P:macroparadise:handlerClasspath="))
    if (requireHandler) require(handlerOptions == Vector(s"-P:macroparadise:handlerClasspath=${independentArtifact.getAbsolutePath}"), s"handler classpath changed: ${handlerOptions.mkString(",")}")
    else require(handlerOptions.isEmpty, s"missing-handler lane retained handler classpath: ${handlerOptions.mkString(",")}")
  }

  private def runRuntime(
      repositoryRoot: File,
      compilerJars: Vector[File],
      apiArtifact: File,
      consumerOutput: File,
      evidenceDirectory: File
  ): (Int, String) = {
    runMain(
      repositoryRoot,
      compilerJars,
      apiArtifact,
      consumerOutput,
      "contractprobeconsumer.IndependentPackagedConsumer",
      ExpectedRuntimeOutput,
      new File(evidenceDirectory, "runtime/run.log")
    )
  }

  private def runMain(
      repositoryRoot: File,
      compilerJars: Vector[File],
      apiArtifact: File,
      consumerOutput: File,
      mainClass: String,
      expectedOutput: String,
      logFile: File
  ): (Int, String) = {
    val runtimeJars = compilerJars.filter(file =>
      file.getName.startsWith("scala3-library_3-") || file.getName.startsWith("scala-library-")
    )
    require(runtimeJars.exists(_.getName.startsWith("scala3-library_3-")), "runtime classpath lacks scala3-library")
    require(runtimeJars.exists(_.getName.startsWith("scala-library-")), "runtime classpath lacks scala-library")
    val command = Vector(
      javaTool("java"), "-cp", classpath(Vector(consumerOutput, apiArtifact) ++ runtimeJars),
      mainClass
    )
    val (exit, output) = runProcess(command, repositoryRoot, logFile)
    require(exit == 0, s"runtime exited $exit: $output")
    require(output == expectedOutput, s"runtime output was `${output.replace("\n", "\\n")}`")
    (exit, output)
  }

  private def verifyMetadataAndIdentity(
      apiArtifact: File,
      independentArtifact: File,
      compilerJars: Vector[File]
  ): MetadataEvidence = {
    val parent = new URLClassLoader((apiArtifact +: compilerJars).map(_.toURI.toURL).toArray, null)
    val child = new TrackingUrlClassLoader(Array(independentArtifact.toURI.toURL), parent)
    try {
      val api = Class.forName("paradise3.api.ParadiseAnnotationExpander", false, parent)
      val carrier = Class.forName("paradise3.api.expander", false, parent)
      val marker = Class.forName("contractprobe.IndependentMarker", false, child)
      val metadata = marker.getDeclaredAnnotations.toVector.find(_.annotationType() == carrier).getOrElse {
        throw new IllegalStateException("independent marker lacks runtime-visible parent expander metadata")
      }
      val value = carrier.getMethod("value").invoke(metadata).toString
      require(value == MetadataValue, s"independent metadata value was `$value`")
      require(!child.requested.contains("contractprobe.IndependentHandler"), "metadata lookup loaded the handler class")
      val handler = Class.forName("contractprobe.IndependentHandler", false, child)
      require(api.isAssignableFrom(handler), "independent handler does not implement the parent API identity")
      require(handler.getInterfaces.toVector.exists(_.getName == "paradise3.api.ParadiseAnnotationExpander"), s"independent handler parent changed: ${handler.getInterfaces.toVector.map(_.getName).mkString(",")}")
      val instance = handler.getDeclaredConstructor().newInstance()
      require(handler.getMethod("annotationName").invoke(instance) == HandlerAnnotationName, "independent handler annotationName does not match production marker semantics")
      MetadataEvidence(
        marker.getName,
        value,
        "paradise3.api.ParadiseAnnotationExpander",
        markerLoadedWithoutInitialization = true,
        handlerNotLoadedDuringLookup = true,
        apiIdentityShared = true
      )
    } finally {
      child.close()
      parent.close()
    }
  }

  private def verifyClassloaderMismatch(
      apiArtifact: File,
      independentArtifact: File,
      compilerJars: Vector[File]
  ): ClassloaderEvidence = {
    val parent = new URLClassLoader((apiArtifact +: compilerJars).map(_.toURI.toURL).toArray, null)
    val duplicate = new ChildFirstApiLoader((Vector(independentArtifact, apiArtifact) ++ compilerJars).map(_.toURI.toURL).toArray, null)
    try {
      val parentApi = Class.forName("paradise3.api.ParadiseAnnotationExpander", false, parent)
      val duplicateApi = Class.forName("paradise3.api.ParadiseAnnotationExpander", false, duplicate)
      val duplicateHandler = Class.forName("contractprobe.IndependentHandler", false, duplicate)
      val rejected = !parentApi.isAssignableFrom(duplicateHandler)
      val implementsDuplicate = duplicateApi.isAssignableFrom(duplicateHandler)
      require(parentApi ne duplicateApi, "duplicate API simulation reused parent identity")
      require(rejected && implementsDuplicate, "duplicate API identity simulation did not expose the cast boundary")
      ClassloaderEvidence(positiveParentFirst = true, duplicateApiRejectedByIdentity = rejected, duplicateHandlerImplementsDuplicateApi = implementsDuplicate)
    } finally {
      duplicate.close()
      parent.close()
    }
  }

  private final class TrackingUrlClassLoader(urls: Array[URL], parent: ClassLoader) extends URLClassLoader(urls, parent) {
    val requested = mutable.ArrayBuffer.empty[String]
    override def loadClass(name: String, resolve: Boolean): Class[_] = synchronized {
      requested += name
      super.loadClass(name, resolve)
    }
  }

  private final class ChildFirstApiLoader(urls: Array[URL], parent: ClassLoader) extends URLClassLoader(urls, parent) {
    override def loadClass(name: String, resolve: Boolean): Class[_] = synchronized {
      if (name.startsWith("paradise3.api.") || name.startsWith("contractprobe.")) {
        val loaded = findLoadedClass(name)
        val value = if (loaded != null) loaded else try findClass(name) catch { case _: ClassNotFoundException => super.loadClass(name, false) }
        if (resolve) resolveClass(value)
        value
      } else super.loadClass(name, resolve)
    }
  }

  private def renderThinArtifact(
      classes: File,
      destination: File,
      expectedCompiled: Set[String],
      packageDirectory: String
  ): Unit = {
    val files = regularRelativeFiles(classes)
    require(files.toSet == expectedCompiled, s"cannot package unexpected independent output: ${files.mkString(", ")}")
    val entries = Map(
      "META-INF/" -> Array.emptyByteArray,
      "META-INF/MANIFEST.MF" -> deterministicManifest.getBytes(StandardCharsets.UTF_8),
      packageDirectory -> Array.emptyByteArray
    ) ++ files.map(name => name -> Files.readAllBytes(new File(classes, name).toPath)).toMap
    Files.createDirectories(destination.toPath.getParent)
    val output = new ZipOutputStream(Files.newOutputStream(destination.toPath))
    try entries.toVector.sortBy(_._1).foreach { case (name, bytes) =>
      val crc = new CRC32()
      crc.update(bytes)
      val entry = new ZipEntry(name)
      entry.setMethod(ZipEntry.STORED)
      entry.setSize(bytes.length.toLong)
      entry.setCompressedSize(bytes.length.toLong)
      entry.setCrc(crc.getValue)
      entry.setTimeLocal(deterministicTimestamp)
      output.putNextEntry(entry)
      output.write(bytes)
      output.closeEntry()
    } finally output.close()
  }

  private def thinArtifactIdentity(
      file: File,
      expectedCompiled: Set[String],
      packageDirectory: String
  ): ArtifactIdentity = {
    val entries = jarEntries(file)
    val expected = (expectedCompiled ++ Set("META-INF/", "META-INF/MANIFEST.MF", packageDirectory)).toVector.sorted
    require(entries == expected, s"thin artifact inventory/order changed: ${entries.mkString(", ")}")
    require(entries.forall(entry => !entry.startsWith("paradise3/") && !entry.startsWith("dotty/") && !entry.startsWith("scala/") && !entry.startsWith("macroparadise/")), "thin artifact packages a forbidden universe")
    ArtifactIdentity(file, file.length(), entries.size, entries.count(_.endsWith(".class")), entries.count(_.endsWith(".tasty")), sha256(file))
  }

  private def artifactIdentity(file: File): ArtifactIdentity = {
    val entries = jarEntries(file)
    ArtifactIdentity(file, file.length(), entries.size, entries.count(_.endsWith(".class")), entries.count(_.endsWith(".tasty")), sha256(file))
  }

  private def compilerClasspath(repositoryRoot: File, dependencies: Seq[File], config: Config): Vector[File] = {
    val jars = dependencies.filter(file => file.isFile && file.getName.endsWith(".jar")).map(_.getAbsoluteFile).distinct.sortBy(_.getAbsolutePath).toVector
    require(jars.exists(_.getName == s"scala3-compiler_3-${config.scalaVersion}.jar"), s"compiler universe lacks exact Scala ${config.scalaVersion}")
    require(jars.forall(file => !isWithin(repositoryRoot, file)), s"compiler universe leaked repository output: ${jars.filter(isWithin(repositoryRoot, _)).mkString(",")}")
    val compilerVersions = jars.filter(_.getName.startsWith("scala3-compiler_3-")).map(_.getName)
    require(compilerVersions.size == 1, s"compiler universe is not singular: ${compilerVersions.mkString(",")}")
    jars
  }

  private def validateClasspath(label: String, files: Seq[File], allowApi: Boolean): Unit = {
    val rendered = files.map(_.getAbsolutePath).mkString("\n")
    forbiddenClasspathFragments.foreach(fragment => require(!rendered.contains(fragment), s"$label leaked forbidden `$fragment`"))
    require(!rendered.contains("macroparadise-scala3-plugin_3"), s"$label leaked plugin implementation")
    if (!allowApi) require(!rendered.contains("macroparadise-scala3-plugin-api_3"), s"$label unexpectedly contains pluginApi")
  }

  private def validateConfig(config: Config): Unit = {
    require(
      config.scalaVersion == ExpectedScalaVersion,
      s"requires selected exact Scala $ExpectedScalaVersion"
    )
    require(config.sbtVersion == ExpectedSbtVersion, s"requires sbt $ExpectedSbtVersion")
    require(config.projectVersion == ExpectedProjectVersion, s"requires project $ExpectedProjectVersion")
    require(Runtime.version().feature() == 25, "requires JDK 25")
  }

  private def runProcess(command: Seq[String], workingDirectory: File, logFile: File): (Int, String) = {
    Files.createDirectories(logFile.toPath.getParent)
    val output = new StringBuilder
    val exit = Process(command, workingDirectory).!(ProcessLogger(line => output.append(line).append('\n'), line => output.append(line).append('\n')))
    val rendered = output.result()
    write(logFile.toPath, "COMMAND\n" + command.mkString("\n") + "\nOUTPUT\n" + rendered)
    (exit, rendered)
  }

  private def positiveOutput(evidenceDirectory: File): File = new File(evidenceDirectory, "positive/classes")
  private def bodyViewPositiveOutput(evidenceDirectory: File): File = new File(evidenceDirectory, "body-view-positive/classes")
  private def typePlacementPositiveOutput(evidenceDirectory: File): File = new File(evidenceDirectory, "type-placement-positive/classes")
  private def modulePlacementPositiveOutput(evidenceDirectory: File): File = new File(evidenceDirectory, "module-placement-positive/classes")
  private def selfTraitPositiveOutput(evidenceDirectory: File): File = new File(evidenceDirectory, "self-trait-positive/classes")
  private def classpath(files: Seq[File]): String = files.map(_.getAbsolutePath).distinct.mkString(File.pathSeparator)
  private def javaTool(name: String): String = new File(new File(System.getProperty("java.home"), "bin"), name).getAbsolutePath
  private def isWithin(root: File, file: File): Boolean = file.toPath.toAbsolutePath.normalize.startsWith(root.toPath.toAbsolutePath.normalize)

  private def regularRelativeFiles(root: File): Vector[String] = {
    if (!root.exists()) Vector.empty
    else {
      val stream = Files.walk(root.toPath)
      try stream.iterator().asScala.filter(Files.isRegularFile(_)).map(path => root.toPath.relativize(path).toString.replace(File.separatorChar, '/')).toVector.sorted
      finally stream.close()
    }
  }

  private def jarEntries(file: File): Vector[String] = {
    val jar = new JarFile(file)
    try jar.entries().asScala.map(_.getName).toVector
    finally jar.close()
  }

  private def readLines(file: File): Vector[String] = if (file.isFile) Files.readAllLines(file.toPath, StandardCharsets.UTF_8).asScala.toVector else Vector.empty

  private def recreateDirectory(path: Path): Unit = {
    if (Files.exists(path)) {
      val stream = Files.walk(path)
      try stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.delete)
      finally stream.close()
    }
    Files.createDirectories(path)
  }

  private def write(path: Path, value: String): Unit = {
    Files.createDirectories(path.getParent)
    Files.write(path, value.getBytes(StandardCharsets.UTF_8))
  }

  private def sha256(file: File): String =
    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath)).map(byte => f"${byte & 0xff}%02x").mkString
}
