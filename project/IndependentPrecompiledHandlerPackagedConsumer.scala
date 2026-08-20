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
  val ExpectedScalaVersion = "3.8.5-RC1-bin-20260405-9478256-NIGHTLY"
  val ExpectedSbtVersion = "1.12.15"
  val ExpectedProjectVersion = "0.1.0"
  val IndependentArtifactBasename = "independent-marker-handler_3-0.1.0.jar"
  val MetadataValue = "contractprobe.IndependentHandler"
  val HandlerAnnotationName = "IndependentMarker"
  val ExpectedRuntimeOutput = "IndependentConsumerUser\n"
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
        s"compile={${compile.render}} metadata={${metadata.render}} positive={${positive.render}} " +
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
    renderThinArtifact(firstOutput, firstJar)
    renderThinArtifact(firstOutput, secondJar)
    require(java.util.Arrays.equals(Files.readAllBytes(firstJar.toPath), Files.readAllBytes(secondJar.toPath)), "thin artifact renders are not byte-identical")
    val independentIdentity = thinArtifactIdentity(firstJar)

    val apiIdentity = artifactIdentity(apiArtifact)
    val pluginIdentity = artifactIdentity(pluginArtifact)
    val metadata = verifyMetadataAndIdentity(apiArtifact, independentIdentity.path, compilerJars)

    val positive = compilePositive(repositoryRoot, compilerJars, apiArtifact, pluginArtifact, independentIdentity.path, consumerSource, evidenceDirectory)
    val runtime = runRuntime(repositoryRoot, compilerJars, apiArtifact, positiveOutput(evidenceDirectory), evidenceDirectory)
    val negatives = Vector(
      compileMissingHandler(repositoryRoot, compilerJars, apiArtifact, pluginArtifact, independentIdentity.path, consumerSource, evidenceDirectory),
      compileMissingMarker(repositoryRoot, compilerJars, apiArtifact, pluginArtifact, independentIdentity.path, consumerSource, evidenceDirectory)
    )
    val classloader = verifyClassloaderMismatch(apiArtifact, independentIdentity.path, compilerJars)

    val result = VerificationResult(
      apiIdentity,
      pluginIdentity,
      independentIdentity,
      compileEvidence,
      metadata,
      positive,
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
    val runtimeJars = compilerJars.filter(file =>
      file.getName.startsWith("scala3-library_3-") || file.getName.startsWith("scala-library-")
    )
    require(runtimeJars.exists(_.getName.startsWith("scala3-library_3-")), "runtime classpath lacks scala3-library")
    require(runtimeJars.exists(_.getName.startsWith("scala-library-")), "runtime classpath lacks scala-library")
    val command = Vector(
      javaTool("java"), "-cp", classpath(Vector(consumerOutput, apiArtifact) ++ runtimeJars),
      "contractprobeconsumer.IndependentPackagedConsumer"
    )
    val (exit, output) = runProcess(command, repositoryRoot, new File(evidenceDirectory, "runtime/run.log"))
    require(exit == 0, s"runtime exited $exit: $output")
    require(output == ExpectedRuntimeOutput, s"runtime output was `${output.replace("\n", "\\n")}`")
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

  private def renderThinArtifact(classes: File, destination: File): Unit = {
    val files = regularRelativeFiles(classes)
    require(files.toSet == expectedCompiledEntries, s"cannot package unexpected independent output: ${files.mkString(", ")}")
    val entries = Map(
      "META-INF/" -> Array.emptyByteArray,
      "META-INF/MANIFEST.MF" -> deterministicManifest.getBytes(StandardCharsets.UTF_8),
      "contractprobe/" -> Array.emptyByteArray
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

  private def thinArtifactIdentity(file: File): ArtifactIdentity = {
    val entries = jarEntries(file)
    val expected = (expectedCompiledEntries ++ Set("META-INF/", "META-INF/MANIFEST.MF", "contractprobe/")).toVector.sorted
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
    val selectedNewerExactNightly =
      config.scalaVersion.matches(
        "^3\\.10\\.0-RC1-bin-[0-9]{8}-[0-9a-f]{7,40}-NIGHTLY$"
      )
    require(
      config.scalaVersion == ExpectedScalaVersion || selectedNewerExactNightly,
      s"requires pinned Scala $ExpectedScalaVersion or the configured exact 3.10 compatibility nightly"
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
