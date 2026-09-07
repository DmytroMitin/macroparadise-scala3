import java.io.{ByteArrayOutputStream, File}
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import scala.sys.process.{Process, ProcessLogger}

object IndependentRoleAwareHandlerPackagedConsumer {
  final case class Config(scalaVersion: String, projectVersion: String)

  final case class Result(
      handlerCompileExit: Int,
      markerCompileExit: Int,
      consumerCompileExit: Int,
      runtimeExit: Int,
      invocationCount: Int,
      legacyBinaryCompileExit: Int,
      legacyBinaryRuntimeExit: Int,
      legacyObjectInvocationCount: Int,
      output: String,
      evidenceDirectory: File
  ) {
    def render: String =
      s"handlerCompile=$handlerCompileExit markerCompile=$markerCompileExit " +
        s"consumerCompile=$consumerCompileExit runtime=$runtimeExit " +
        s"invocations=$invocationCount legacyBinaryCompile=$legacyBinaryCompileExit " +
        s"legacyBinaryRuntime=$legacyBinaryRuntimeExit legacyObjectInvocations=$legacyObjectInvocationCount " +
        s"output=${output.trim.replace("\n", "|")}"
  }

  def verify(
      repositoryRoot: File,
      apiArtifact: File,
      pluginArtifact: File,
      releasedApiArtifact: File,
      apiDependencies: Seq[File],
      handlerSource: File,
      markerSource: File,
      consumerSource: File,
      legacyHandlerSource: File,
      legacyMarkerSource: File,
      legacyConsumerSource: File,
      legacyObjectNegativeSource: File,
      evidenceDirectory: File,
      config: Config
  ): Result = {
    require(config.scalaVersion == ExactBuildIdentity.SelectedScalaVersion)
    require(config.projectVersion == ExactBuildIdentity.DevelopmentVersion)
    Vector(
      apiArtifact,
      pluginArtifact,
      releasedApiArtifact,
      handlerSource,
      markerSource,
      consumerSource,
      legacyHandlerSource,
      legacyMarkerSource,
      legacyConsumerSource,
      legacyObjectNegativeSource
    )
      .foreach(file => require(file.isFile, s"missing role-aware proof input ${file.getAbsolutePath}"))

    deleteRecursively(evidenceDirectory)
    val handlerOutput = new File(evidenceDirectory, "handler-classes")
    val markerOutput = new File(evidenceDirectory, "marker-classes")
    val consumerOutput = new File(evidenceDirectory, "consumer-classes")
    Vector(handlerOutput, markerOutput, consumerOutput).foreach(file => require(file.mkdirs()))

    val compilerJars = apiDependencies.toVector.distinct
    require(compilerJars.exists(_.getName.startsWith("scala3-compiler_3-")))
    require(compilerJars.exists(_.getName.startsWith("scala3-library_3-")))
    require(compilerJars.exists(_.getName.startsWith("scala-library-")))

    val handlerCompile = compile(
      repositoryRoot,
      compilerJars,
      Vector(apiArtifact) ++ compilerJars,
      handlerOutput,
      handlerSource,
      Vector.empty,
      new File(evidenceDirectory, "handler-compile.log")
    )
    require(handlerCompile._1 == 0, s"independent role-aware handler compile failed: ${handlerCompile._2}")
    val handlerJar = new File(evidenceDirectory, "role-aware-handler.jar")
    packageJar(repositoryRoot, handlerOutput, handlerJar, new File(evidenceDirectory, "handler-package.log"))

    val markerCompile = compile(
      repositoryRoot,
      compilerJars,
      Vector(apiArtifact) ++ compilerJars,
      markerOutput,
      markerSource,
      Vector.empty,
      new File(evidenceDirectory, "marker-compile.log")
    )
    require(markerCompile._1 == 0, s"independent role-aware marker compile failed: ${markerCompile._2}")
    val markerJar = new File(evidenceDirectory, "role-aware-marker.jar")
    packageJar(repositoryRoot, markerOutput, markerJar, new File(evidenceDirectory, "marker-package.log"))

    val trace = new File(evidenceDirectory, "invocations.trace")
    val consumerCompile = compile(
      repositoryRoot,
      compilerJars,
      Vector(apiArtifact, markerJar) ++ compilerJars,
      consumerOutput,
      consumerSource,
      Vector(
        s"-Xplugin:${pluginArtifact.getAbsolutePath}",
        "-Xplugin-require:macroparadise",
        s"-P:macroparadise:handlerClasspath=${handlerJar.getAbsolutePath}",
        s"-P:macroparadise:externalHandlerInvocationTrace=${trace.getAbsolutePath}"
      ),
      new File(evidenceDirectory, "consumer-compile.log")
    )
    require(consumerCompile._1 == 0, s"independent role-aware consumer compile failed: ${consumerCompile._2}")
    val outputFiles = regularFiles(consumerOutput)
    require(outputFiles.exists(_.endsWith("ObjectEdit$.class")), s"missing object output: ${outputFiles.mkString(",")}")
    require(outputFiles.exists(_.endsWith("CreateClass.class")), s"missing created class output: ${outputFiles.mkString(",")}")
    require(outputFiles.exists(_.endsWith("CreateTrait.class")), s"missing created trait output: ${outputFiles.mkString(",")}")

    val invocationLines =
      if (trace.isFile) Files.readAllLines(trace.toPath, StandardCharsets.UTF_8).toArray.toVector.map(_.toString)
      else Vector.empty
    require(invocationLines.size == 5, s"expected five role-aware invocations, found ${invocationLines.size}")
    require(invocationLines.forall(_.contains("handler=roleawareprobe.IndependentRoleAwareHandler")))

    val runtimeJars = compilerJars.filter(file =>
      file.getName.startsWith("scala3-library_3-") || file.getName.startsWith("scala-library-")
    )
    val runtime = run(
      Vector(
        javaTool("java"),
        "-cp",
        classpath(Vector(consumerOutput) ++ runtimeJars),
        "roleawareconsumer.IndependentRoleAwareConsumer"
      ),
      repositoryRoot,
      new File(evidenceDirectory, "runtime.log")
    )
    val expected = "ok\nclass-replaced\ntrait-replaced\nclass-created\ntrait-created\n"
    require(runtime._1 == 0, s"role-aware runtime failed: ${runtime._2}")
    require(runtime._2 == expected, s"role-aware runtime output was `${runtime._2.replace("\n", "\\n")}`")

    val legacyHandlerOutput = new File(evidenceDirectory, "legacy-0.1.1-handler-classes")
    val legacyMarkerOutput = new File(evidenceDirectory, "legacy-0.1.1-marker-classes")
    val legacyConsumerOutput = new File(evidenceDirectory, "legacy-0.1.1-consumer-classes")
    val legacyObjectOutput = new File(evidenceDirectory, "legacy-0.1.1-object-negative-classes")
    Vector(legacyHandlerOutput, legacyMarkerOutput, legacyConsumerOutput, legacyObjectOutput)
      .foreach(file => require(file.mkdirs()))

    val legacyHandlerCompile = compile(
      repositoryRoot,
      compilerJars,
      Vector(releasedApiArtifact) ++ compilerJars,
      legacyHandlerOutput,
      legacyHandlerSource,
      Vector.empty,
      new File(evidenceDirectory, "legacy-0.1.1-handler-compile.log")
    )
    require(legacyHandlerCompile._1 == 0, s"0.1.1 legacy handler compile failed: ${legacyHandlerCompile._2}")
    val legacyHandlerJar = new File(evidenceDirectory, "legacy-0.1.1-handlers.jar")
    packageJar(repositoryRoot, legacyHandlerOutput, legacyHandlerJar, new File(evidenceDirectory, "legacy-0.1.1-handler-package.log"))

    val legacyMarkerCompile = compile(
      repositoryRoot,
      compilerJars,
      Vector(releasedApiArtifact) ++ compilerJars,
      legacyMarkerOutput,
      legacyMarkerSource,
      Vector.empty,
      new File(evidenceDirectory, "legacy-0.1.1-marker-compile.log")
    )
    require(legacyMarkerCompile._1 == 0, s"0.1.1 legacy marker compile failed: ${legacyMarkerCompile._2}")
    val legacyMarkerJar = new File(evidenceDirectory, "legacy-0.1.1-markers.jar")
    packageJar(repositoryRoot, legacyMarkerOutput, legacyMarkerJar, new File(evidenceDirectory, "legacy-0.1.1-marker-package.log"))

    val legacyTrace = new File(evidenceDirectory, "legacy-0.1.1-positive.trace")
    val legacyConsumerCompile = compile(
      repositoryRoot,
      compilerJars,
      Vector(apiArtifact, legacyMarkerJar) ++ compilerJars,
      legacyConsumerOutput,
      legacyConsumerSource,
      Vector(
        s"-Xplugin:${pluginArtifact.getAbsolutePath}",
        "-Xplugin-require:macroparadise",
        s"-P:macroparadise:handlerClasspath=${legacyHandlerJar.getAbsolutePath}",
        s"-P:macroparadise:externalHandlerInvocationTrace=${legacyTrace.getAbsolutePath}"
      ),
      new File(evidenceDirectory, "legacy-0.1.1-consumer-compile.log")
    )
    require(legacyConsumerCompile._1 == 0, s"0.1.1 legacy binary consumer failed: ${legacyConsumerCompile._2}")
    val legacyTraceLines = readLines(legacyTrace)
    require(legacyTraceLines.size == 2, s"expected two 0.1.1 handler invocations, found ${legacyTraceLines.size}")
    val legacyRuntime = run(
      Vector(
        javaTool("java"),
        "-cp",
        classpath(Vector(legacyConsumerOutput) ++ runtimeJars),
        "legacybinaryconsumer.LegacyBinaryConsumer"
      ),
      repositoryRoot,
      new File(evidenceDirectory, "legacy-0.1.1-runtime.log")
    )
    require(legacyRuntime._1 == 0, s"0.1.1 legacy runtime failed: ${legacyRuntime._2}")
    require(legacyRuntime._2 == "class-0.1.1\ntrait-0.1.1\n")

    val legacyObjectTrace = new File(evidenceDirectory, "legacy-0.1.1-object-negative.trace")
    val legacyObjectCompile = compile(
      repositoryRoot,
      compilerJars,
      Vector(apiArtifact, legacyMarkerJar) ++ compilerJars,
      legacyObjectOutput,
      legacyObjectNegativeSource,
      Vector(
        s"-Xplugin:${pluginArtifact.getAbsolutePath}",
        "-Xplugin-require:macroparadise",
        s"-P:macroparadise:handlerClasspath=${legacyHandlerJar.getAbsolutePath}",
        s"-P:macroparadise:externalHandlerInvocationTrace=${legacyObjectTrace.getAbsolutePath}"
      ),
      new File(evidenceDirectory, "legacy-0.1.1-object-negative-compile.log")
    )
    require(legacyObjectCompile._1 != 0, "legacy 0.1.1 object negative unexpectedly compiled")
    require(legacyObjectCompile._2.contains("currently supports only top-level classes"))
    val legacyObjectTraceLines = readLines(legacyObjectTrace)
    require(legacyObjectTraceLines.isEmpty, s"legacy object invoked old binary handler: ${legacyObjectTraceLines.mkString("|")}")
    require(regularFiles(legacyObjectOutput).isEmpty, "legacy object failure emitted partial class/TASTy output")

    Result(
      handlerCompile._1,
      markerCompile._1,
      consumerCompile._1,
      runtime._1,
      invocationLines.size,
      legacyConsumerCompile._1,
      legacyRuntime._1,
      legacyObjectTraceLines.size,
      runtime._2,
      evidenceDirectory
    )
  }

  private def compile(
      cwd: File,
      compilerJars: Vector[File],
      sourceClasspath: Vector[File],
      output: File,
      source: File,
      options: Vector[String],
      log: File
  ): (Int, String) =
    run(
      Vector(javaTool("java"), "-cp", classpath(compilerJars), "dotty.tools.dotc.Main") ++
        Vector("-classpath", classpath(sourceClasspath), "-d", output.getAbsolutePath) ++
        options ++ Vector(source.getAbsolutePath),
      cwd,
      log
    )

  private def packageJar(cwd: File, classes: File, jar: File, log: File): Unit = {
    val result = run(
      Vector(javaTool("jar"), "--create", "--file", jar.getAbsolutePath, "-C", classes.getAbsolutePath, "."),
      cwd,
      log
    )
    require(result._1 == 0 && jar.isFile, s"role-aware artifact packaging failed: ${result._2}")
  }

  private def run(command: Vector[String], cwd: File, log: File): (Int, String) = {
    val bytes = new ByteArrayOutputStream()
    val logger = ProcessLogger(
      line => { bytes.write(line.getBytes(StandardCharsets.UTF_8)); bytes.write('\n') },
      line => { bytes.write(line.getBytes(StandardCharsets.UTF_8)); bytes.write('\n') }
    )
    val exit = Process(command, cwd).!(logger)
    val output = new String(bytes.toByteArray, StandardCharsets.UTF_8)
    Files.write(log.toPath, output.getBytes(StandardCharsets.UTF_8))
    exit -> output
  }

  private def javaTool(name: String): String =
    new File(new File(System.getProperty("java.home"), "bin"), name).getAbsolutePath

  private def classpath(files: Seq[File]): String =
    files.map(_.getCanonicalPath).distinct.mkString(File.pathSeparator)

  private def regularFiles(root: File): Vector[String] = {
    import scala.collection.JavaConverters._
    val stream = Files.walk(root.toPath)
    try stream.iterator.asScala.filter(Files.isRegularFile(_)).map(root.toPath.relativize(_).toString).toVector.sorted
    finally stream.close()
  }

  private def readLines(file: File): Vector[String] =
    if (file.isFile)
      Files.readAllLines(file.toPath, StandardCharsets.UTF_8).toArray.toVector.map(_.toString)
    else Vector.empty

  private def deleteRecursively(file: File): Unit =
    if (file.exists()) {
      if (file.isDirectory) Option(file.listFiles()).toVector.flatten.foreach(deleteRecursively)
      require(file.delete(), s"could not delete ${file.getAbsolutePath}")
    }
}
