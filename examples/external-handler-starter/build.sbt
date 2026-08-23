import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer
import scala.sys.process.{Process, ProcessLogger}

val ExactScalaVersion =
  sys.props.getOrElse("macroparadise.starter.scalaVersion", "3.8.4")

ThisBuild / scalaVersion := ExactScalaVersion
ThisBuild / publish / skip := true

Global / onLoad := { state =>
  require(
    Set("3.3.8", "3.8.4").contains(ExactScalaVersion),
    s"unsupported exact Scala line $ExactScalaVersion"
  )
  val actual = java.lang.Runtime.version().feature()
  if (actual != 25) sys.error(s"external-handler starter requires JDK 25, found $actual")
  state
}

lazy val precheckPositive = taskKey[Unit]("Run the packaged declaration and binding precheck")
lazy val verifyNegativeMatrix = taskKey[Unit]("Verify P1-P7 stop before consumer compilation")
lazy val verifyStarter = taskKey[Unit]("Verify precheck-gated ordinary typed starter consumption")
lazy val markConsumerCompileStart = taskKey[Unit]("Record that the real consumer compile began")

val PluginProperty = "macroparadise.starter.plugin"
val PluginApiProperty = "macroparadise.starter.pluginApi"
val EvidenceProperty = "macroparadise.starter.evidenceDir"
val ExpansionTraceProperty = "macroparadise.starter.expandTrace"

def requiredArtifact(property: String): File =
  sys.props.get(property).map(file).filter(_.isFile).getOrElse {
    sys.error(s"missing required packaged artifact property -D$property=/path/to/artifact.jar")
  }.getCanonicalFile

def evidenceDirectory: File =
  sys.props.get(EvidenceProperty).map(file).getOrElse(file("target/external-handler-starter-evidence")).getCanonicalFile

def expansionTrace: File =
  sys.props.get(ExpansionTraceProperty).map(file).getOrElse(evidenceDirectory / "expand.trace").getCanonicalFile

def appendEvent(path: File, event: String): Unit = {
  IO.createDirectory(path.getParentFile)
  IO.append(path, event + "\n", StandardCharsets.UTF_8)
}

def writeLines(path: File, lines: Seq[String]): Unit = {
  IO.createDirectory(path.getParentFile)
  IO.write(path, lines.mkString("", "\n", "\n"), StandardCharsets.UTF_8)
}

def runCommand(command: Seq[String], cwd: File, log: File): Int = {
  val output = ArrayBuffer.empty[String]
  val exit = Process(command, cwd).!(ProcessLogger(
    line => output += line,
    line => output += line
  ))
  writeLines(log, output)
  exit
}

def javaCommand(
    runtimeClasspath: Seq[File],
    plugin: File,
    pluginApi: File,
    marker: File,
    handler: File,
    handlerCompileClasspath: Seq[File],
    markerClass: String,
    expectedHandlerClass: String,
    expectedAnnotation: String,
    expectedScala: String = ExactScalaVersion,
    expectedJdk: Int = 25
): Seq[String] = {
  val java = file(sys.props("java.home")) / "bin" / "java"
  val parentClasspath = (plugin +: runtimeClasspath).map(_.getCanonicalFile).distinct
  Seq(
    java.getAbsolutePath,
    "-cp",
    parentClasspath.map(_.getAbsolutePath).mkString(File.pathSeparator),
    "macroparadise.ExternalHandlerPrecheckMain",
    s"--plugin=${plugin.getAbsolutePath}",
    s"--plugin-api=${pluginApi.getAbsolutePath}",
    s"--marker=${marker.getAbsolutePath}",
    s"--handler=${handler.getAbsolutePath}",
    s"--handler-compile-classpath=${handlerCompileClasspath.map(_.getCanonicalPath).mkString(File.pathSeparator)}",
    s"--marker-class=$markerClass",
    s"--expected-handler-class=$expectedHandlerClass",
    s"--expected-annotation=$expectedAnnotation",
    s"--expected-scala-version=$expectedScala",
    s"--expected-jdk-major=$expectedJdk"
  )
}

def compactJavaCommand(
    runtimeClasspath: Seq[File],
    plugin: File,
    marker: File,
    handler: File,
    handlerCompileClasspath: Seq[File],
    expectedHandlerClass: String,
    expectedAnnotation: String,
    expectedScala: String = ExactScalaVersion,
    expectedJdk: Int = 25
): Seq[String] = {
  val java = file(sys.props("java.home")) / "bin" / "java"
  val parentClasspath = (plugin +: runtimeClasspath).map(_.getCanonicalFile).distinct
  Seq(
    java.getAbsolutePath,
    "-cp",
    parentClasspath.map(_.getAbsolutePath).mkString(File.pathSeparator),
    "macroparadise.ExternalHandlerPrecheckMain",
    "--compact",
    s"--marker=${marker.getAbsolutePath}",
    s"--handler=${handler.getAbsolutePath}",
    s"--handler-compile-classpath=${handlerCompileClasspath.map(_.getCanonicalPath).mkString(File.pathSeparator)}",
    s"--expected-handler-class=$expectedHandlerClass",
    s"--expected-annotation=$expectedAnnotation",
    s"--expected-scala-version=$expectedScala",
    s"--expected-jdk-major=$expectedJdk"
  )
}

def contractSettings: Seq[Setting[_]] = Seq(
  Compile / unmanagedJars += Attributed.blank(requiredArtifact(PluginApiProperty)),
  libraryDependencies += "org.scala-lang" %% "scala3-compiler" % scalaVersion.value
)

lazy val marker: Project = project.in(file("marker"))
  .settings(contractSettings)
  .settings(name := "external-handler-starter-marker")

lazy val handler: Project = project.in(file("handler"))
  .settings(contractSettings)
  .settings(
    name := "external-handler-starter-handler",
    precheckPositive := {
      val evidence = evidenceDirectory
      val flow = evidence / "positive-flow.trace"
      IO.delete(flow)
      IO.delete(expansionTrace)
      appendEvent(flow, "precheck-start")

      val plugin = requiredArtifact(PluginProperty)
      val pluginApi = requiredArtifact(PluginApiProperty)
      val markerJar = (marker / Compile / packageBin).value
      val handlerJar = (Compile / packageBin).value
      val handlerClasspath = (Compile / dependencyClasspath).value.files.map(_.getCanonicalFile)
      writeLines(evidence / "handler-compile-classpath.txt", handlerClasspath.map(_.getAbsolutePath))

      val command = javaCommand(
        runtimeClasspath = handlerClasspath,
        plugin = plugin,
        pluginApi = pluginApi,
        marker = markerJar,
        handler = handlerJar,
        handlerCompileClasspath = handlerClasspath,
        markerClass = "starter.marker.generateGreeting",
        expectedHandlerClass = "starter.handler.GenerateGreetingHandler",
        expectedAnnotation = "starter.marker.generateGreeting"
      )
      writeLines(evidence / "precheck-command.txt", command)
      val exit = runCommand(command, baseDirectory.value, evidence / "precheck-positive.log")
      require(exit == 0, s"positive precheck failed with exit $exit")
      require(!expansionTrace.isFile, "precheck invoked handler expansion")

      val compactCommand = compactJavaCommand(
        runtimeClasspath = handlerClasspath,
        plugin = plugin,
        marker = markerJar,
        handler = handlerJar,
        handlerCompileClasspath = handlerClasspath,
        expectedHandlerClass = "starter.handler.GenerateGreetingHandler",
        expectedAnnotation = "starter.marker.generateGreeting"
      )
      writeLines(evidence / "precheck-compact-command.txt", compactCommand)
      val compactExit = runCommand(
        compactCommand,
        baseDirectory.value,
        evidence / "precheck-compact-positive.log"
      )
      require(compactExit == 0, s"compact positive precheck failed with exit $compactExit")
      require(!expansionTrace.isFile, "compact precheck invoked handler expansion")
      appendEvent(flow, "precheck-success")
    }
  )

lazy val negativeMarker: Project = project.in(file("precheck-fixtures/marker"))
  .settings(contractSettings)
  .settings(name := "external-handler-starter-negative-marker")

lazy val negativeHandler: Project = project.in(file("precheck-fixtures/handler"))
  .settings(contractSettings)
  .settings(name := "external-handler-starter-negative-handler")

lazy val metadataHandlerB: Project = project.in(file("metadata-fixtures/handler-b"))
  .settings(contractSettings)
  .settings(name := "external-handler-starter-metadata-handler-b")

lazy val consumer: Project = project.in(file("consumer"))
  .settings(
    name := "external-handler-starter-consumer",
    Compile / unmanagedJars ++= Seq(
      Attributed.blank(requiredArtifact(PluginApiProperty)),
      Attributed.blank((marker / Compile / packageBin).value)
    ),
    Compile / scalacOptions ++= {
      val plugin = requiredArtifact(PluginProperty)
      val markerJar = (marker / Compile / packageBin).value
      val handlerJar = (handler / Compile / packageBin).value
      Seq(
        s"-Xplugin:${plugin.getAbsolutePath}",
        "-Xplugin-require:macroparadise",
        s"-P:macroparadise:handlerClasspath=${handlerJar.getAbsolutePath}"
      )
    },
    markConsumerCompileStart := {
      (handler / precheckPositive).value
      val evidence = evidenceDirectory
      System.setProperty(ExpansionTraceProperty, expansionTrace.getAbsolutePath)
      appendEvent(evidence / "positive-flow.trace", "consumer-compile-start")
      writeLines(
        evidence / "consumer-compile-classpath.txt",
        (Compile / dependencyClasspath).value.files.map(_.getCanonicalPath)
      )
      writeLines(
        evidence / "consumer-scalac-options.txt",
        (Compile / scalacOptions).value
      )
    },
    Compile / compile := (Compile / compile).dependsOn(markConsumerCompileStart).value
  )

lazy val root: Project = project.in(file("."))
  .aggregate(marker, handler, negativeMarker, negativeHandler, metadataHandlerB, consumer)
  .settings(
    name := "external-handler-starter",
    verifyNegativeMatrix := {
      val evidence = evidenceDirectory / "negative"
      IO.delete(evidence)
      IO.createDirectory(evidence)

      val plugin = requiredArtifact(PluginProperty)
      val pluginApi = requiredArtifact(PluginApiProperty)
      val markerJar = (marker / Compile / packageBin).value
      val handlerJar = (handler / Compile / packageBin).value
      val negativeMarkerJar = (negativeMarker / Compile / packageBin).value
      val negativeHandlerJar = (negativeHandler / Compile / packageBin).value
      val metadataHandlerBJar = (metadataHandlerB / Compile / packageBin).value
      val handlerClasspath = (handler / Compile / dependencyClasspath).value.files.map(_.getCanonicalFile)
      val negativeHandlerClasspath = (negativeHandler / Compile / dependencyClasspath).value.files.map(_.getCanonicalFile)
      val metadataHandlerBClasspath = (metadataHandlerB / Compile / dependencyClasspath).value.files.map(_.getCanonicalFile)

      final case class Lane(
          id: String,
          category: String,
          marker: File = markerJar,
          handler: File = handlerJar,
          compileClasspath: Seq[File] = handlerClasspath,
          markerClass: String = "starter.marker.generateGreeting",
          expectedHandler: String = "starter.handler.GenerateGreetingHandler",
          expectedAnnotation: String = "starter.marker.generateGreeting",
          expectedScala: String = ExactScalaVersion,
          expectedJdk: Int = 25
      )

      val lanes = Vector(
        Lane("P1", "INVALID_HANDLER_ANNOTATION_NAME", expectedAnnotation = "starter.marker..generateGreeting"),
        Lane("P2", "METADATA_HANDLER_CLASS_MISMATCH", expectedHandler = "starter.handler.OtherHandler"),
        Lane(
          "P3",
          "METADATA_HANDLER_ANNOTATION_MISMATCH",
          marker = negativeMarkerJar,
          handler = negativeHandlerJar,
          compileClasspath = negativeHandlerClasspath,
          markerClass = "starter.negative.bindingMismatch",
          expectedHandler = "starter.negative.BindingMismatchHandler",
          expectedAnnotation = "starter.negative.bindingMismatch"
        ),
        Lane(
          "P4",
          "EXACT_COMPILER_MISMATCH",
          expectedScala = "3.8.5-RC1-bin-20260405-9478256-NIGHTLY"
        ),
        Lane("P5", "EXACT_JDK_MISMATCH", expectedJdk = 26),
        Lane("P6", "FORBIDDEN_HANDLER_DEPENDENCY", compileClasspath = handlerClasspath :+ plugin),
        Lane("P7", "MISSING_ARTIFACT", marker = evidence / "missing-marker.jar")
      )

      lanes.foreach { lane =>
        val laneDirectory = evidence / lane.id
        val sentinel = laneDirectory / "flow.trace"
        appendEvent(sentinel, "precheck-start")
        val command = javaCommand(
          runtimeClasspath = lane.compileClasspath,
          plugin = plugin,
          pluginApi = pluginApi,
          marker = lane.marker,
          handler = lane.handler,
          handlerCompileClasspath = lane.compileClasspath,
          markerClass = lane.markerClass,
          expectedHandlerClass = lane.expectedHandler,
          expectedAnnotation = lane.expectedAnnotation,
          expectedScala = lane.expectedScala,
          expectedJdk = lane.expectedJdk
        )
        writeLines(laneDirectory / "command.txt", command)
        val log = laneDirectory / "precheck.log"
        val exit = runCommand(command, baseDirectory.value, log)
        if (exit == 0) appendEvent(sentinel, "consumer-compile-start")
        require(exit != 0, s"${lane.id} unexpectedly passed precheck")
        val diagnostic = IO.read(log, StandardCharsets.UTF_8)
        require(diagnostic.contains(s"category=${lane.category}"), s"${lane.id} lacked ${lane.category}: $diagnostic")
        require(diagnostic.contains("consumerCompilationStarted=false"), s"${lane.id} lacked consumer stop evidence")
        require(diagnostic.contains("expansionInvoked=false"), s"${lane.id} lacked expansion stop evidence")
        require(!IO.readLines(sentinel).contains("consumer-compile-start"), s"${lane.id} reached consumer compile")
        appendEvent(sentinel, "precheck-failed")
      }

      val duplicatePluginApi = evidence / "duplicate-plugin-api.jar"
      IO.copyFile(pluginApi, duplicatePluginApi)

      final case class CompactLane(
          id: String,
          category: String,
          marker: File = markerJar,
          handler: File = handlerJar,
          runtimeClasspath: Seq[File] = handlerClasspath,
          compileClasspath: Seq[File] = handlerClasspath,
          expectedHandler: String = "starter.handler.GenerateGreetingHandler",
          expectedAnnotation: String = "starter.marker.generateGreeting",
          expectedScala: String = ExactScalaVersion,
          expectedJdk: Int = 25
      )

      val compactLanes = Vector(
        CompactLane("C1", "METADATA_HANDLER_CLASS_MISMATCH", expectedHandler = "starter.handler.OtherHandler"),
        CompactLane("C2", "WRONG_ARTIFACT_ROLE", expectedAnnotation = "starter.marker.otherGreeting"),
        CompactLane(
          "C3",
          "EXACT_COMPILER_MISMATCH",
          expectedScala = "3.8.5-RC1-bin-20260405-9478256-NIGHTLY"
        ),
        CompactLane("C4", "EXACT_JDK_MISMATCH", expectedJdk = 26),
        CompactLane("C5", "FORBIDDEN_HANDLER_DEPENDENCY", compileClasspath = handlerClasspath :+ plugin),
        CompactLane(
          "C6",
          "COMPACT_PRECHECK_DERIVATION_FAILURE",
          compileClasspath = duplicatePluginApi +: handlerClasspath
        )
      )

      compactLanes.foreach { lane =>
        val laneDirectory = evidenceDirectory / "negative-compact" / lane.id
        val sentinel = laneDirectory / "flow.trace"
        appendEvent(sentinel, "precheck-start")
        val command = compactJavaCommand(
          runtimeClasspath = lane.runtimeClasspath,
          plugin = plugin,
          marker = lane.marker,
          handler = lane.handler,
          handlerCompileClasspath = lane.compileClasspath,
          expectedHandlerClass = lane.expectedHandler,
          expectedAnnotation = lane.expectedAnnotation,
          expectedScala = lane.expectedScala,
          expectedJdk = lane.expectedJdk
        )
        writeLines(laneDirectory / "command.txt", command)
        val log = laneDirectory / "precheck.log"
        val exit = runCommand(command, baseDirectory.value, log)
        if (exit == 0) appendEvent(sentinel, "consumer-compile-start")
        require(exit != 0, s"${lane.id} unexpectedly passed compact precheck")
        val diagnostic = IO.read(log, StandardCharsets.UTF_8)
        require(diagnostic.contains(s"category=${lane.category}"), s"${lane.id} lacked ${lane.category}: $diagnostic")
        require(diagnostic.contains("consumerCompilationStarted=false"), s"${lane.id} lacked consumer stop evidence")
        require(diagnostic.contains("expansionInvoked=false"), s"${lane.id} lacked expansion stop evidence")
        require(!IO.readLines(sentinel).contains("consumer-compile-start"), s"${lane.id} reached consumer compile")
        appendEvent(sentinel, "precheck-failed")
      }

      final case class MetadataLane(
          id: String,
          category: String,
          markerClass: String,
          expectedHandler: String,
          expectedAnnotation: String,
          handler: File = negativeHandlerJar,
          compileClasspath: Seq[File] = negativeHandlerClasspath
      )

      val metadataLanes = Vector(
        MetadataLane(
          "M1",
          "HANDLER_CLASS_LOADING_FAILURE",
          "starter.metadata.missingHandler",
          "starter.metadata.DoesNotExist",
          "starter.metadata.missingHandler"
        ),
        MetadataLane(
          "M2",
          "HANDLER_CONTRACT_IDENTITY_FAILURE",
          "starter.metadata.invalidContract",
          "starter.metadata.NotAHandler",
          "starter.metadata.invalidContract"
        ),
        MetadataLane(
          "M3",
          "HANDLER_CLASS_LOADING_FAILURE",
          "starter.metadata.handlerA",
          "starter.metadata.HandlerA",
          "starter.metadata.handlerA",
          handler = metadataHandlerBJar,
          compileClasspath = metadataHandlerBClasspath
        ),
        MetadataLane(
          "M4",
          "METADATA_HANDLER_ANNOTATION_MISMATCH",
          "starter.metadata.descriptorMismatch",
          "starter.metadata.DescriptorMismatchHandler",
          "starter.metadata.descriptorMismatch"
        ),
        MetadataLane(
          "M5",
          "METADATA_HANDLER_ANNOTATION_MISMATCH",
          "starter.metadata.stale",
          "starter.metadata.StableHandler",
          "starter.metadata.stale"
        ),
        MetadataLane(
          "M6",
          "METADATA_HANDLER_ANNOTATION_MISMATCH",
          "starter.alpha.audit",
          "starter.metadata.QualifiedMismatchHandler",
          "starter.alpha.audit"
        ),
        MetadataLane(
          "M7",
          "INVALID_METADATA_HANDLER_CLASS_NAME",
          "starter.metadata.emptyMetadata",
          "starter.metadata.EmptyHandler",
          "starter.metadata.emptyMetadata"
        ),
        MetadataLane(
          "M8",
          "INVALID_METADATA_HANDLER_CLASS_NAME",
          "starter.metadata.whitespaceMetadata",
          "   ",
          "starter.metadata.whitespaceMetadata"
        ),
        MetadataLane(
          "M9",
          "INVALID_METADATA_HANDLER_CLASS_NAME",
          "starter.metadata.malformedMetadata",
          "starter.metadata..Broken",
          "starter.metadata.malformedMetadata"
        )
      )

      metadataLanes.foreach { lane =>
        Vector("explicit", "compact").foreach { mode =>
          val laneDirectory = evidenceDirectory / "negative-metadata" / lane.id / mode
          val sentinel = laneDirectory / "flow.trace"
          appendEvent(sentinel, "precheck-start")
          val command =
            if (mode == "explicit")
              javaCommand(
                runtimeClasspath = lane.compileClasspath,
                plugin = plugin,
                pluginApi = pluginApi,
                marker = negativeMarkerJar,
                handler = lane.handler,
                handlerCompileClasspath = lane.compileClasspath,
                markerClass = lane.markerClass,
                expectedHandlerClass = lane.expectedHandler,
                expectedAnnotation = lane.expectedAnnotation
              )
            else
              compactJavaCommand(
                runtimeClasspath = lane.compileClasspath,
                plugin = plugin,
                marker = negativeMarkerJar,
                handler = lane.handler,
                handlerCompileClasspath = lane.compileClasspath,
                expectedHandlerClass = lane.expectedHandler,
                expectedAnnotation = lane.expectedAnnotation
              )
          writeLines(laneDirectory / "command.txt", command)
          val log = laneDirectory / "precheck.log"
          val exit = runCommand(command, baseDirectory.value, log)
          if (exit == 0) appendEvent(sentinel, "consumer-compile-start")
          require(exit != 0, s"${lane.id} $mode unexpectedly passed metadata precheck")
          val diagnostic = IO.read(log, StandardCharsets.UTF_8)
          require(diagnostic.contains(s"category=${lane.category}"), s"${lane.id} $mode lacked ${lane.category}: $diagnostic")
          require(diagnostic.contains("consumerCompilationStarted=false"), s"${lane.id} $mode lacked consumer stop evidence")
          require(diagnostic.contains("expansionInvoked=false"), s"${lane.id} $mode lacked expansion stop evidence")
          require(!IO.readLines(sentinel).contains("consumer-compile-start"), s"${lane.id} $mode reached consumer compile")
          appendEvent(sentinel, "precheck-failed")
        }
      }
    },
    verifyStarter := {
      val _ = (consumer / Compile / compile).value
      val evidence = evidenceDirectory
      appendEvent(evidence / "positive-flow.trace", "consumer-compile-success")
      val runtimeClasspath = (consumer / Runtime / fullClasspath).value.files.map(_.getCanonicalFile)
      val java = file(sys.props("java.home")) / "bin" / "java"
      val command = Seq(
        java.getAbsolutePath,
        "-cp",
        runtimeClasspath.map(_.getAbsolutePath).mkString(File.pathSeparator),
        "starter.consumer.StarterConsumer"
      )
      writeLines(evidence / "runtime-command.txt", command)
      val exit = runCommand(command, baseDirectory.value, evidence / "runtime.log")
      require(exit == 0, s"starter runtime failed with exit $exit")
      require(IO.read(evidence / "runtime.log", StandardCharsets.UTF_8).contains("Hello, Greeter!"), "starter runtime value was not observed")
      val expansions = if (expansionTrace.isFile) IO.readLines(expansionTrace).filter(_ == "expand") else Nil
      require(expansions.size == 1, s"expected one real consumer expansion after precheck, found ${expansions.size}")
      appendEvent(evidence / "positive-flow.trace", "runtime-success")
      val expectedFlow = List(
        "precheck-start",
        "precheck-success",
        "consumer-compile-start",
        "consumer-compile-success",
        "runtime-success"
      )
      require(IO.readLines(evidence / "positive-flow.trace") == expectedFlow, "positive flow order drifted")
    }
  )
