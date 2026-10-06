import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import scala.sys.process.{Process, ProcessLogger}

object PublicEmbeddedProducerStarter {
  final case class Finding(code: String, path: String, detail: String) {
    override def toString: String = s"$code:$path:$detail"
  }

  final case class Result(
      scalaVersion: String,
      sourceBuiltInputs: Boolean,
      producerGeneration: Boolean,
      derivedRolePackaging: Boolean,
      consumerCompile: Boolean,
      runtimeOutput: Boolean,
      runtimeIsolation: Boolean,
      noProducerPublishLocal: Boolean,
      evidenceDirectory: File
  ) {
    def render: String =
      s"scala=$scalaVersion sourceBuiltInputs=$sourceBuiltInputs " +
        s"producerGeneration=$producerGeneration derivedRolePackaging=$derivedRolePackaging " +
        s"consumerCompile=$consumerCompile runtimeOutput=$runtimeOutput " +
        s"runtimeIsolation=$runtimeIsolation noProducerPublishLocal=$noProducerPublishLocal " +
        s"evidence=$evidenceDirectory"
  }

  private val RequiredFiles = Vector(
    "README.md",
    "build.sbt",
    "project/build.properties",
    "project/plugins.sbt",
    "embedded-producer/src/main/scala/starter/embedded/EmbeddedAnnotations.scala",
    "core/src/main/scala/starter/core/Main.scala"
  )

  def validateLayout(root: File): Vector[Finding] = {
    val errors = Vector.newBuilder[Finding]

    RequiredFiles.foreach { relative =>
      if (!new File(root, relative).isFile)
        errors += Finding("MISSING_STARTER_FILE", relative, "required public starter file is absent")
    }

    checkTokens(
      root,
      "project/plugins.sbt",
      "STARTER_PLUGIN_VERSION",
      Vector(
        "addSbtPlugin",
        "\"sbt-macroparadise\"",
        "\"0.2.0-SNAPSHOT\""
      ),
      errors
    )
    checkTokens(
      root,
      "build.sbt",
      "STARTER_STATIC_MARKER_EDGE",
      Vector(
        "MacroParadiseEmbeddedProducerPlugin",
        "MacroParadisePrecompiledPlugin",
        "precompiledEmbeddedProject(embeddedProducer)",
        "\"provided->macroParadiseEmbeddedMarker\""
      ),
      errors
    )
    checkTokens(
      root,
      "build.sbt",
      "STARTER_RUNTIME_ISOLATION",
      Vector(
        "Runtime / fullClasspath",
        "derived handler is not first in its closure",
        "handler implementation leaked onto runtime",
        "Scala compiler leaked onto runtime",
        "producer compiler plugin leaked onto runtime"
      ),
      errors
    )
    checkTokens(
      root,
      "embedded-producer/src/main/scala/starter/embedded/EmbeddedAnnotations.scala",
      "STARTER_ARGUMENT_DECODING",
      Vector(
        "@embeddedExpander",
        "ExpansionEdit.finish(ExpansionEdit.start(input))",
        "AnnotationApplication.fromInput(input)",
        "requireSingleStringLiteralArgument(\"prefix\")",
        "ExpansionTransforms.placeMemberInPrimary"
      ),
      errors
    )
    checkTokens(
      root,
      "core/src/main/scala/starter/core/Main.scala",
      "STARTER_NAMED_ARGUMENT",
      Vector(
        "@identityEmbedded",
        "@addGreeting(\"Hello\")",
        "@addGreeting(prefix = \"Welcome\")",
        "PUBLIC_EMBEDDED_STARTER_PASS"
      ),
      errors
    )
    checkTokens(
      root,
      "README.md",
      "STARTER_NO_PUBLISHLOCAL",
      Vector(
        "source-built `0.2.0-SNAPSHOT`",
        "precompiled producer",
        "marker and handler roles",
        "no producer `publishLocal`",
        "generated adapter name is not a public contract",
        "same-module use is unsupported",
        "External handler authoring remains supported"
      ),
      errors
    )

    errors.result()
  }

  def verify(
      repositoryRoot: File,
      taskRoot: File,
      scalaVersion: String,
      sbtVersion: String
  ): Result = {
    require(Set("3.3.8", "3.8.4", "3.9.0")(scalaVersion), "unsupported exact Scala line")
    require(sbtVersion == "1.12.15", "unsupported sbt version")

    val source = new File(repositoryRoot, "examples/embedded-producer-starter")
    val layoutErrors = validateLayout(source)
    require(layoutErrors.isEmpty, layoutErrors.mkString("; "))

    sbt.IO.delete(taskRoot)
    val build = new File(taskRoot, "build")
    val evidence = new File(taskRoot, "evidence")
    sbt.IO.createDirectory(evidence)
    sbt.IO.copyDirectory(source, build)

    val sourceInstallLog = new File(evidence, "00-sbt-plugin-source-install.log")
    val sourceInstallExit = run(
      new File(repositoryRoot, "sbt-integration"),
      Vector("sbt", "-batch", "verifyIntegrationPolicy", "publishLocal"),
      sourceInstallLog
    )
    require(sourceInstallExit == 0, s"source-built sbt plugin installation failed; see $sourceInstallLog")

    val childLog = new File(evidence, "10-public-starter.log")
    val command = Vector(
      "sbt",
      "-batch",
      "-Dmacroparadise.example.scalaVersion=" + scalaVersion,
      "clean",
      "core/verifyEmbeddedStarter"
    )
    require(
      !command.exists(value => value == "publishLocal" || value == "publishM2"),
      "public starter child command must not publish the producer"
    )
    val exit = run(build, command, childLog)
    require(exit == 0, s"public embedded producer starter failed; see $childLog")
    val log = read(childLog)
    require(log.contains("PUBLIC_EMBEDDED_STARTER_PASS"), "public starter runtime witness is absent")

    Result(
      scalaVersion,
      sourceBuiltInputs = true,
      producerGeneration = true,
      derivedRolePackaging = true,
      consumerCompile = true,
      runtimeOutput = true,
      runtimeIsolation = true,
      noProducerPublishLocal = true,
      evidence
    )
  }

  private def checkTokens(
      root: File,
      relative: String,
      code: String,
      tokens: Vector[String],
      errors: scala.collection.mutable.Builder[Finding, Vector[Finding]]
  ): Unit = {
    val file = new File(root, relative)
    if (file.isFile) {
      val body = read(file)
      val missing = tokens.filterNot(body.contains)
      if (missing.nonEmpty)
        errors += Finding(code, relative, "missing: " + missing.mkString(", "))
    }
  }

  private def run(directory: File, command: Vector[String], log: File): Int = {
    val output = new StringBuilder
    val exit = Process(command, directory).!(
      ProcessLogger(
        line => output.append(line).append('\n'),
        line => output.append(line).append('\n')
      )
    )
    Option(log.getParentFile).foreach(sbt.IO.createDirectory)
    Files.write(log.toPath, output.result().getBytes(StandardCharsets.UTF_8))
    exit
  }

  private def read(file: File): String =
    new String(Files.readAllBytes(file.toPath), StandardCharsets.UTF_8)
}
