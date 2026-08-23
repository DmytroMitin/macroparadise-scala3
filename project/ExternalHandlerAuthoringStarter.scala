import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.util.jar.JarFile

import scala.collection.JavaConverters._
import scala.sys.process.{Process, ProcessLogger}

object ExternalHandlerAuthoringStarter {
  val ReadyClassification = "EXTERNAL_HANDLER_AUTHORING_STARTER_READY"
  val SplitClassification = "FIXTURE_INDEPENDENT_MARKER_HANDLER_CONSUMER_SPLIT_PROVED"
  val PrecheckClassification = "PRECONSUMER_HANDLER_DECLARATION_AND_BINDING_PRECHECK_READY"
  val TypedClassification = "ORDINARY_TYPED_CONSUMER_VISIBILITY_PROVED"
  val PublishingClassification =
    "REMOTE_PUBLISHING_REMAINS_DISABLED_LOCAL_SELECTED_ARTIFACTS_ONLY"

  val ExpectedScalaVersion = ExactBuildIdentity.SelectedScalaVersion
  val ExpectedSbtVersion = "1.12.15"

  final case class Config(scalaVersion: String, sbtVersion: String, jdkMajor: Int)

  final case class ArtifactIdentity(
      path: File,
      bytes: Long,
      entries: Int,
      classes: Int,
      tasty: Int,
      sha256: String
  ) {
    def render: String =
      s"path=${path.getAbsolutePath} bytes=$bytes entries=$entries classes=$classes tasty=$tasty sha256=$sha256"
  }

  final case class NegativeEvidence(id: String, category: String, flow: Vector[String]) {
    def render: String = s"$id(category=$category flow=${flow.mkString(",")})"
  }

  final case class MetadataNegativeEvidence(
      id: String,
      category: String,
      explicitFlow: Vector[String],
      compactFlow: Vector[String]
  ) {
    def render: String =
      s"$id(category=$category explicitFlow=${explicitFlow.mkString(",")} compactFlow=${compactFlow.mkString(",")})"
  }

  final case class VerificationResult(
      plugin: ArtifactIdentity,
      pluginApi: ArtifactIdentity,
      marker: ArtifactIdentity,
      handler: ArtifactIdentity,
      positiveFlow: Vector[String],
      handlerClasspath: Vector[String],
      consumerClasspath: Vector[String],
      pluginOptions: Vector[String],
      runtimeOutput: String,
      generatedMethodPresent: Boolean,
      expansionCount: Int,
      negatives: Vector[NegativeEvidence],
      compactNegatives: Vector[NegativeEvidence],
      metadataNegatives: Vector[MetadataNegativeEvidence],
      explicitPrecheckArguments: Int,
      compactPrecheckArguments: Int,
      childStateDeleted: Boolean,
      evidenceDirectory: File
  ) {
    def render: String =
      s"classification=$ReadyClassification splitClassification=$SplitClassification " +
        s"precheckClassification=$PrecheckClassification typedClassification=$TypedClassification " +
        s"publishingClassification=$PublishingClassification plugin={${plugin.render}} " +
        s"pluginApi={${pluginApi.render}} marker={${marker.render}} handler={${handler.render}} " +
        s"positiveFlow=${positiveFlow.mkString(",")} handlerClasspathEntries=${handlerClasspath.size} " +
        s"consumerClasspathEntries=${consumerClasspath.size} pluginOptions=${pluginOptions.mkString("|")} " +
        s"runtimeOutput=${runtimeOutput.trim} generatedMethodPresent=$generatedMethodPresent " +
        s"expansionCount=$expansionCount negatives=${negatives.map(_.render).mkString(";")} " +
        s"compactNegatives=${compactNegatives.map(_.render).mkString(";")} " +
        s"metadataNegatives=${metadataNegatives.map(_.render).mkString(";")} " +
        s"explicitPrecheckArguments=$explicitPrecheckArguments compactPrecheckArguments=$compactPrecheckArguments " +
        s"childStateDeleted=$childStateDeleted"
  }

  def validatePositiveFlow(flow: Vector[String]): Vector[String] = {
    val expected = Vector(
      "precheck-start",
      "precheck-success",
      "consumer-compile-start",
      "consumer-compile-success",
      "runtime-success"
    )
    if (flow == expected) Vector.empty
    else Vector(s"positive flow mismatch: expected ${expected.mkString(",")}, found ${flow.mkString(",")}")
  }

  def validateNegativeFlow(flow: Vector[String]): Vector[String] = {
    val errors = Vector.newBuilder[String]
    if (flow != Vector("precheck-start", "precheck-failed"))
      errors += s"negative flow mismatch: ${flow.mkString(",")}"
    if (flow.contains("consumer-compile-start"))
      errors += "negative flow reached consumer compilation"
    errors.result()
  }

  def validateChildCommand(command: Vector[String]): Vector[String] = {
    val errors = Vector.newBuilder[String]
    val rendered = command.mkString("\n")
    if (!rendered.contains("macroparadise.starter.plugin=")) errors += "plugin path property missing"
    if (!rendered.contains("macroparadise.starter.pluginApi=")) errors += "pluginApi path property missing"
    if (!rendered.contains("macroparadise.starter.scalaVersion=")) errors += "exact Scala property missing"
    if (rendered.contains("macroparadise.starter.expandTrace="))
      errors += "starter command exposes an internal expansion-trace property"
    Vector("clean", "verifyStarter", "verifyNegativeMatrix").foreach { task =>
      if (!command.contains(task)) errors += s"child task $task missing"
    }
    Vector(
      "publish" + "Local",
      "publish" + "M2",
      "publish",
      "deploy"
    ).foreach { forbidden =>
      if (command.exists(_ == forbidden)) errors += s"forbidden child command $forbidden"
    }
    errors.result()
  }

  def validateHandlerClasspath(classpath: Vector[String]): Vector[String] = {
    val errors = Vector.newBuilder[String]
    val normalized = classpath.map(_.replace('\\', '/').toLowerCase)
    if (!normalized.exists(_.contains("plugin-api"))) errors += "pluginApi contract artifact missing"
    if (!normalized.exists(_.contains("scala3-compiler"))) errors += "exact compiler artifact missing"
    Vector(
      "plugin-test-handlers",
      "plugin-test-markers",
      "plugin-tests",
      "plugin-api-handler-contract-probe",
      "composition-contract",
      "same-module",
      "quasiquotes",
      "auxify"
    ).foreach { forbidden =>
      if (normalized.exists(_.contains(forbidden))) errors += s"forbidden handler classpath fragment $forbidden"
    }
    errors.result()
  }

  def validatePrecheckCommandShapes(
      explicit: Vector[String],
      compact: Vector[String]
  ): Vector[String] = {
    val errors = Vector.newBuilder[String]
    val explicitKeys = explicit.filter(_.startsWith("--")).map(_.takeWhile(_ != '='))
    val compactKeys = compact.filter(_.startsWith("--")).map(_.takeWhile(_ != '='))
    val explicitRequired = Vector(
      "--plugin",
      "--plugin-api",
      "--marker",
      "--handler",
      "--handler-compile-classpath",
      "--marker-class",
      "--expected-handler-class",
      "--expected-annotation",
      "--expected-scala-version",
      "--expected-jdk-major"
    )
    val compactRequired = Vector(
      "--compact",
      "--marker",
      "--handler",
      "--handler-compile-classpath",
      "--expected-handler-class",
      "--expected-annotation",
      "--expected-scala-version",
      "--expected-jdk-major"
    )

    if (explicitKeys != explicitRequired)
      errors += s"explicit precheck argument shape changed: ${explicitKeys.mkString(",")}"
    if (compactKeys != compactRequired)
      errors += s"compact precheck argument shape changed: ${compactKeys.mkString(",")}"
    Vector("--plugin", "--plugin-api", "--marker-class").foreach { forbidden =>
      if (compactKeys.contains(forbidden)) errors += s"compact precheck repeats derived $forbidden"
    }
    errors.result()
  }

  def validateMetadataDiagnosticParity(
      explicit: String,
      compact: String,
      expectedCategory: String
  ): Vector[String] = {
    val errors = Vector.newBuilder[String]
    val categoryToken = s"category=$expectedCategory"
    if (!explicit.contains(categoryToken)) errors += s"explicit diagnostic lacks $categoryToken"
    if (!compact.contains(categoryToken)) errors += s"compact diagnostic lacks $categoryToken"

    val coreFields = Vector(
      "failureStage",
      "markerIdentity",
      "expectedAnnotation",
      "metadataHandler",
      "expectedHandler",
      "markerArtifact",
      "handlerArtifact"
    )

    def field(log: String, name: String): Option[String] =
      log.split("\\s+").find(_.startsWith(name + "=")).map(_.substring(name.length + 1))

    coreFields.foreach { name =>
      val explicitValue = field(explicit, name)
      val compactValue = field(compact, name)
      if (explicitValue.isEmpty) errors += s"explicit diagnostic lacks $name"
      if (compactValue.isEmpty) errors += s"compact diagnostic lacks $name"
      if (explicitValue.nonEmpty && compactValue.nonEmpty && explicitValue != compactValue)
        errors += s"diagnostic field $name differs: explicit=${explicitValue.get} compact=${compactValue.get}"
    }
    errors.result()
  }

  def verify(
      repositoryRoot: File,
      pluginArtifact: File,
      pluginApiArtifact: File,
      taskRoot: File,
      config: Config
  ): VerificationResult = {
    validateConfig(config)
    val canonicalRepository = repositoryRoot.getCanonicalFile
    val canonicalTaskRoot = taskRoot.getCanonicalFile
    val requiredTaskParent = new File(canonicalRepository, "target").getCanonicalFile.toPath
    require(canonicalTaskRoot.toPath.startsWith(requiredTaskParent), s"task root must stay under repository target: $canonicalTaskRoot")

    recreateDirectory(canonicalTaskRoot.toPath)
    val evidence = new File(canonicalTaskRoot, "evidence")
    val childState = new File(canonicalTaskRoot, "child-state")
    val boot = new File(childState, "boot")
    val global = new File(childState, "global")
    val ivy = new File(childState, "ivy")
    val coursier = new File(childState, "coursier")
    val temporary = new File(childState, "tmp")
    Vector(evidence, boot, global, ivy, coursier, temporary).foreach(file => Files.createDirectories(file.toPath))

    val starter = new File(canonicalRepository, "examples/external-handler-starter")
    require(starter.isDirectory, s"starter build is missing: $starter")
    val expandTrace = new File(evidence, "expand.trace")
    val command = Vector(
      "sbt",
      "-batch",
      "-Dsbt.boot.directory=" + boot.getAbsolutePath,
      "-Dsbt.global.base=" + global.getAbsolutePath,
      "-Dsbt.ivy.home=" + ivy.getAbsolutePath,
      "-Djava.io.tmpdir=" + temporary.getAbsolutePath,
      "-Dmacroparadise.starter.plugin=" + pluginArtifact.getCanonicalPath,
      "-Dmacroparadise.starter.pluginApi=" + pluginApiArtifact.getCanonicalPath,
      "-Dmacroparadise.starter.scalaVersion=" + config.scalaVersion,
      "-Dmacroparadise.starter.evidenceDir=" + evidence.getAbsolutePath,
      "clean",
      "verifyStarter",
      "verifyNegativeMatrix"
    )
    require(validateChildCommand(command).isEmpty, validateChildCommand(command).mkString("; "))
    val environment = Map(
      "COURSIER_CACHE" -> coursier.getAbsolutePath,
      "IVY_HOME" -> ivy.getAbsolutePath
    )
    val childLog = new File(evidence, "child-sbt.log")
    val exit = runProcess(command, starter, environment, childLog)
    require(exit == 0, s"starter child build failed with exit $exit; see $childLog")

    val positiveFlow = readLines(new File(evidence, "positive-flow.trace"))
    require(validatePositiveFlow(positiveFlow).isEmpty, validatePositiveFlow(positiveFlow).mkString("; "))
    val handlerClasspath = readLines(new File(evidence, "handler-compile-classpath.txt"))
    require(validateHandlerClasspath(handlerClasspath).isEmpty, validateHandlerClasspath(handlerClasspath).mkString("; "))
    val consumerClasspath = readLines(new File(evidence, "consumer-compile-classpath.txt"))
    val pluginOptions = readLines(new File(evidence, "consumer-scalac-options.txt"))
    require(pluginOptions.exists(_.startsWith("-Xplugin:")), "consumer plugin path option missing")
    require(pluginOptions.exists(_.startsWith("-P:macroparadise:handlerClasspath=")), "consumer handler path option missing")
    val externalArtifactIdentities =
      pluginOptions.filter(_.startsWith("-P:macroparadise:externalArtifactIdentity=sha256:"))
    require(externalArtifactIdentities.size == 1, "consumer external artifact identity option is not singular")
    require(
      externalArtifactIdentities.head.stripPrefix("-P:macroparadise:externalArtifactIdentity=sha256:").matches("[0-9a-f]{64}"),
      "consumer external artifact identity is not a lowercase SHA-256 value"
    )
    require(pluginOptions.forall(value => !value.contains("plugin-test")), "consumer plugin options contain repository fixtures")

    val runtimeOutput = read(new File(evidence, "runtime.log"))
    require(runtimeOutput == "Hello, Greeter!\n", s"unexpected starter runtime output: ${runtimeOutput.replace("\n", "\\n")}")
    val expansions = readLines(expandTrace).count(_ == "expand")
    require(expansions == 1, s"expected one post-precheck expansion, found $expansions")
    val precheckLog = read(new File(evidence, "precheck-positive.log"))
    require(precheckLog.contains("parentFirstContractIdentity=true"), "parent-first contract identity was not proven")
    require(precheckLog.contains("expansionInvoked=false"), "precheck zero-expansion evidence is missing")
    val compactPrecheckLog = read(new File(evidence, "precheck-compact-positive.log"))
    require(compactPrecheckLog.contains("parentFirstContractIdentity=true"), "compact parent-first contract identity was not proven")
    require(compactPrecheckLog.contains("expansionInvoked=false"), "compact precheck zero-expansion evidence is missing")
    val explicitPrecheckArguments = readLines(new File(evidence, "precheck-command.txt")).filter(_.startsWith("--"))
    val compactPrecheckArguments = readLines(new File(evidence, "precheck-compact-command.txt")).filter(_.startsWith("--"))
    require(
      validatePrecheckCommandShapes(explicitPrecheckArguments, compactPrecheckArguments).isEmpty,
      validatePrecheckCommandShapes(explicitPrecheckArguments, compactPrecheckArguments).mkString("; ")
    )

    val expectedCategories = Vector(
      "P1" -> "INVALID_HANDLER_ANNOTATION_NAME",
      "P2" -> "METADATA_HANDLER_CLASS_MISMATCH",
      "P3" -> "METADATA_HANDLER_ANNOTATION_MISMATCH",
      "P4" -> "EXACT_COMPILER_MISMATCH",
      "P5" -> "EXACT_JDK_MISMATCH",
      "P6" -> "FORBIDDEN_HANDLER_DEPENDENCY",
      "P7" -> "MISSING_ARTIFACT"
    )
    val negatives = expectedCategories.map { case (id, category) =>
      val directory = new File(evidence, s"negative/$id")
      val flow = readLines(new File(directory, "flow.trace"))
      require(validateNegativeFlow(flow).isEmpty, s"$id: ${validateNegativeFlow(flow).mkString("; ")}")
      val log = read(new File(directory, "precheck.log"))
      require(log.contains(s"category=$category"), s"$id lacked $category")
      NegativeEvidence(id, category, flow)
    }
    val expectedCompactCategories = Vector(
      "C1" -> "METADATA_HANDLER_CLASS_MISMATCH",
      "C2" -> "WRONG_ARTIFACT_ROLE",
      "C3" -> "EXACT_COMPILER_MISMATCH",
      "C4" -> "EXACT_JDK_MISMATCH",
      "C5" -> "FORBIDDEN_HANDLER_DEPENDENCY",
      "C6" -> "COMPACT_PRECHECK_DERIVATION_FAILURE"
    )
    val compactNegatives = expectedCompactCategories.map { case (id, category) =>
      val directory = new File(evidence, s"negative-compact/$id")
      val flow = readLines(new File(directory, "flow.trace"))
      require(validateNegativeFlow(flow).isEmpty, s"$id: ${validateNegativeFlow(flow).mkString("; ")}")
      val log = read(new File(directory, "precheck.log"))
      require(log.contains(s"category=$category"), s"$id lacked $category")
      require(log.contains("consumerCompilationStarted=false"), s"$id lacked consumer stop evidence")
      require(log.contains("expansionInvoked=false"), s"$id lacked expansion stop evidence")
      NegativeEvidence(id, category, flow)
    }
    val expectedMetadataCategories = Vector(
      "M1" -> "HANDLER_CLASS_LOADING_FAILURE",
      "M2" -> "HANDLER_CONTRACT_IDENTITY_FAILURE",
      "M3" -> "HANDLER_CLASS_LOADING_FAILURE",
      "M4" -> "METADATA_HANDLER_ANNOTATION_MISMATCH",
      "M5" -> "METADATA_HANDLER_ANNOTATION_MISMATCH",
      "M6" -> "METADATA_HANDLER_ANNOTATION_MISMATCH",
      "M7" -> "INVALID_METADATA_HANDLER_CLASS_NAME",
      "M8" -> "INVALID_METADATA_HANDLER_CLASS_NAME",
      "M9" -> "INVALID_METADATA_HANDLER_CLASS_NAME"
    )
    val metadataNegatives = expectedMetadataCategories.map { case (id, category) =>
      val directory = new File(evidence, s"negative-metadata/$id")
      val explicitFlow = readLines(new File(directory, "explicit/flow.trace"))
      val compactFlow = readLines(new File(directory, "compact/flow.trace"))
      require(validateNegativeFlow(explicitFlow).isEmpty, s"$id explicit: ${validateNegativeFlow(explicitFlow).mkString("; ")}")
      require(validateNegativeFlow(compactFlow).isEmpty, s"$id compact: ${validateNegativeFlow(compactFlow).mkString("; ")}")
      val explicitLog = read(new File(directory, "explicit/precheck.log"))
      val compactLog = read(new File(directory, "compact/precheck.log"))
      Vector("consumerCompilationStarted=false", "expansionInvoked=false").foreach { fragment =>
        require(explicitLog.contains(fragment), s"$id explicit lacked $fragment")
        require(compactLog.contains(fragment), s"$id compact lacked $fragment")
      }
      require(
        validateMetadataDiagnosticParity(explicitLog, compactLog, category).isEmpty,
        s"$id: ${validateMetadataDiagnosticParity(explicitLog, compactLog, category).mkString("; ")}"
      )
      MetadataNegativeEvidence(id, category, explicitFlow, compactFlow)
    }

    val markerArtifact = singleJar(new File(starter, "marker/target"), "external-handler-starter-marker_3-")
    val handlerArtifact = singleJar(new File(starter, "handler/target"), "external-handler-starter-handler_3-")
    require(jarEntries(markerArtifact).contains("starter/marker/generateGreeting.class"), "marker artifact role is missing")
    val handlerEntries = jarEntries(handlerArtifact)
    require(handlerEntries.contains("starter/handler/GenerateGreetingHandler.class"), "handler artifact role is missing")
    require(!handlerEntries.exists(_.startsWith("macroparadise/")), "handler artifact contains plugin implementation classes")

    val consumerClasses = singleDirectory(new File(starter, "consumer/target"), "classes")
    val javapLog = new File(evidence, "consumer-javap.log")
    val javapExit = runProcess(
      Vector(
        new File(new File(System.getProperty("java.home"), "bin"), "javap").getAbsolutePath,
        "-classpath",
        consumerClasses.getAbsolutePath,
        "starter.consumer.Greeter"
      ),
      starter,
      Map.empty,
      javapLog
    )
    val generatedMethodPresent = javapExit == 0 && read(javapLog).contains("generatedGreeting")
    require(generatedMethodPresent, "generated greeting method is absent from ordinary consumer bytecode")

    val pluginIdentity = artifactIdentity(pluginArtifact)
    val apiIdentity = artifactIdentity(pluginApiArtifact)
    val markerIdentity = artifactIdentity(markerArtifact)
    val handlerIdentity = artifactIdentity(handlerArtifact)

    deleteRecursively(childState.toPath)
    val childStateDeleted = !childState.exists()
    require(childStateDeleted, "task-owned child sbt state was not deleted")

    val result = VerificationResult(
      pluginIdentity,
      apiIdentity,
      markerIdentity,
      handlerIdentity,
      positiveFlow,
      handlerClasspath,
      consumerClasspath,
      pluginOptions,
      runtimeOutput,
      generatedMethodPresent,
      expansions,
      negatives,
      compactNegatives,
      metadataNegatives,
      explicitPrecheckArguments.size,
      compactPrecheckArguments.count(_ != "--compact"),
      childStateDeleted,
      evidence
    )
    write(new File(evidence, "summary.txt"), result.render + "\n")
    result
  }

  private def validateConfig(config: Config): Unit = {
    require(config.scalaVersion == ExpectedScalaVersion, s"requires exact Scala $ExpectedScalaVersion")
    require(config.sbtVersion == ExpectedSbtVersion, s"requires sbt $ExpectedSbtVersion")
    require(config.jdkMajor == 25, s"requires JDK 25, found ${config.jdkMajor}")
  }

  private def runProcess(
      command: Seq[String],
      directory: File,
      environment: Map[String, String],
      log: File
  ): Int = {
    Files.createDirectories(log.toPath.getParent)
    val output = new StringBuilder
    val logger = ProcessLogger(
      line => output.append(line).append('\n'),
      line => output.append(line).append('\n')
    )
    val exit = Process(command, directory, environment.toSeq: _*).!(logger)
    val header =
      "DIRECTORY\n" + directory.getAbsolutePath +
        "\nENVIRONMENT\n" + environment.toVector.sortBy(_._1).map { case (key, value) => s"$key=$value" }.mkString("\n") +
        "\nCOMMAND\n" + command.mkString("\n") +
        "\nOUTPUT\n"
    write(log, header + output.result())
    exit
  }

  private def artifactIdentity(path: File): ArtifactIdentity = {
    val canonical = path.getCanonicalFile
    require(canonical.isFile, s"artifact is missing: $canonical")
    val entries = jarEntries(canonical)
    ArtifactIdentity(
      canonical,
      canonical.length(),
      entries.size,
      entries.count(_.endsWith(".class")),
      entries.count(_.endsWith(".tasty")),
      sha256(canonical)
    )
  }

  private def jarEntries(path: File): Vector[String] = {
    val jar = new JarFile(path)
    try jar.entries().asScala.map(_.getName).toVector.sorted
    finally jar.close()
  }

  private def singleJar(root: File, prefix: String): File = {
    val matches = regularFiles(root.toPath).filter { path =>
      val name = path.getFileName.toString
      name.startsWith(prefix) && name.endsWith(".jar") && !name.contains("sources") && !name.contains("javadoc")
    }
    require(matches.size == 1, s"expected one $prefix JAR under $root, found ${matches.mkString(", ")}")
    matches.head.toFile
  }

  private def singleDirectory(root: File, name: String): File = {
    val stream = Files.walk(root.toPath)
    try {
      val matches = stream.iterator().asScala.filter(path => Files.isDirectory(path) && path.getFileName.toString == name).toVector
      require(matches.size == 1, s"expected one $name directory under $root, found ${matches.mkString(", ")}")
      matches.head.toFile
    } finally stream.close()
  }

  private def regularFiles(root: Path): Vector[Path] = {
    val stream = Files.walk(root)
    try stream.iterator().asScala.filter(Files.isRegularFile(_)).toVector
    finally stream.close()
  }

  private def sha256(path: File): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    val input = Files.newInputStream(path.toPath)
    try {
      val buffer = new Array[Byte](8192)
      var read = input.read(buffer)
      while (read >= 0) {
        if (read > 0) digest.update(buffer, 0, read)
        read = input.read(buffer)
      }
    } finally input.close()
    digest.digest().map(value => f"${value & 0xff}%02x").mkString
  }

  private def read(path: File): String =
    new String(Files.readAllBytes(path.toPath), StandardCharsets.UTF_8)

  private def readLines(path: File): Vector[String] =
    Files.readAllLines(path.toPath, StandardCharsets.UTF_8).asScala.toVector

  private def write(path: File, value: String): Unit = {
    Files.createDirectories(path.toPath.getParent)
    Files.write(path.toPath, value.getBytes(StandardCharsets.UTF_8))
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
}
