import paradise3.api.{ExpansionTargetProfile, ParadiseAnnotationExpander}

import java.io.File
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.jar.{JarEntry, JarOutputStream}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.sys.process.*

class IndependentRestrictedTraitHandlerConsumerSpec extends munit.FunSuite:
  override val munitTimeout: Duration = 180.seconds

  private val scalaVersion =
    sys.props.getOrElse(
      "macroparadise.testScalaVersion",
      "3.8.5-RC1-bin-20260405-9478256-NIGHTLY"
    )
  private val pluginJar =
    new File(s"plugin/target/scala-$scalaVersion/macroparadise-scala3-plugin_$scalaVersion-0.1.0-SNAPSHOT.jar").getAbsoluteFile
  private val pluginApiJar =
    new File(s"plugin-api/target/scala-$scalaVersion/macroparadise-scala3-plugin-api_$scalaVersion-0.1.0-SNAPSHOT.jar").getAbsoluteFile
  private val producerSource =
    Path.of("plugin-api-handler-contract-probe/restricted-trait/IndependentRestrictedTraitMarkerAndHandler.scala").toAbsolutePath
  private val consumerSource =
    Path.of("plugin-api-handler-contract-probe/restricted-trait/IndependentRestrictedTraitConsumer.scala").toAbsolutePath
  private val compilerUniverse =
    List(
      codeSourcePath(dotty.tools.dotc.Main.getClass),
      codeSourcePath(classOf[dotty.tools.dotc.core.Contexts.Context]),
      codeSourcePath(classOf[dotty.tools.dotc.interfaces.ReporterResult]),
      codeSourcePath(classOf[dotty.tools.tasty.TastyReader]),
      codeSourcePath(classOf[scala.tools.asm.Type]),
      codeSourcePath(classOf[xsbti.UseScope]),
      codeSourcePath(classOf[scala.Option[?]]),
      codeSourcePath(classOf[scala.deriving.Mirror])
    ).distinct

  private final case class CompiledProbe(root: Path, artifact: File)

  private final case class CompileResult(
      hasErrors: Boolean,
      messages: List[String],
      outputFiles: List[String],
      metadataTrace: List[String],
      invocationTrace: List[String],
      output: Path
  )

  test("arbitrary-name marker and handlers compile only against packaged pluginApi and share parent-first API identity") {
    withCompiledProbe: probe =>
      val entries = jarEntries(probe.artifact)
      assert(entries.contains("external/traitprobe/RestrictedApply.class"))
      assert(entries.contains("external/traitprobe/RestrictedApplyHandler.class"))
      assert(entries.contains("external/traitprobe/DefaultTraitAttempt.class"))
      assert(entries.contains("external/traitprobe/DefaultClassOnlyHandler.class"))
      assert(entries.forall(name =>
        !name.startsWith("paradise3/") &&
          !name.startsWith("macroparadise/") &&
          !name.startsWith("demo/")
      ))

      val parent = new URLClassLoader(
        (pluginApiJar :: compilerUniverse).map(_.toURI.toURL).toArray,
        null
      )
      val child = new URLClassLoader(Array(probe.artifact.toURI.toURL), parent)
      try
        val api = Class.forName(classOf[ParadiseAnnotationExpander].getName, false, parent)
        val childApi = Class.forName(classOf[ParadiseAnnotationExpander].getName, false, child)
        val restricted = Class.forName("external.traitprobe.RestrictedApplyHandler", true, child)
        val defaulted = Class.forName("external.traitprobe.DefaultClassOnlyHandler", true, child)
        assert(api eq childApi)
        assert(api.isAssignableFrom(restricted))
        assert(api.isAssignableFrom(defaulted))
        assertEquals(
          restricted.getMethod("targetProfile").invoke(restricted.getConstructor().newInstance()).toString,
          "RestrictedGenericTraitApply"
        )
        assertEquals(
          defaulted.getMethod("targetProfile").invoke(defaulted.getConstructor().newInstance()).toString,
          "CommonClassOnly"
        )
      finally
        child.close()
        parent.close()
  }

  test("independent handler creates and merges companions while direct apply wins at runtime") {
    withCompiledProbe: probe =>
      val result = compileConsumer(Files.readString(consumerSource), probe.artifact, includeHandlerPath = true)
      assert(!result.hasErrors, result.messages.mkString("\n"))
      assert(result.outputFiles.exists(_.endsWith("IndependentShow.class")))
      assert(result.outputFiles.exists(_.endsWith("IndependentExistingShow.class")))
      assert(result.outputFiles.exists(_.endsWith("IndependentDirectShow.class")))
      assertEquals(
        result.invocationTrace.count(_.contains("handler=external.traitprobe.RestrictedApplyHandler")),
        3
      )
      assert(
        result.metadataTrace.exists(_.contains("external.traitprobe.RestrictedApplyHandler")),
        result.metadataTrace.mkString("\n")
      )

      val runtimeClasspath =
        (result.output.toFile :: compilerUniverse.filter(file =>
          file.getName.startsWith("scala-library") || file.getName.startsWith("scala3-library")
        )).map(_.getAbsolutePath).mkString(File.pathSeparator)
      val output =
        Process(
          Seq(
            javaTool,
            "-cp",
            runtimeClasspath,
            "external.traitconsumer.IndependentRestrictedTraitConsumer"
          ),
          new File(".")
        ).!!
      assertEquals(output, "IndependentRestrictedTraitConsumer\n")
  }

  test("default-profile independent handler rejects a trait before invocation with zero output") {
    withCompiledProbe: probe =>
      val result = compileConsumer(
        """package external.defaultconsumer
          |import external.traitprobe.DefaultTraitAttempt
          |@DefaultTraitAttempt trait DefaultAttempt[A]
          |""".stripMargin,
        probe.artifact,
        includeHandlerPath = true
      )
      assertRejected(result, "currently supports only top-level classes", "trait DefaultAttempt")
      assertEquals(result.invocationTrace, Nil)
  }

  test("independent restricted profile rejects representative out-of-envelope targets atomically") {
    withCompiledProbe: probe =>
      val declarations = List(
        "trait NoParameter" -> "found 0 type parameters",
        "trait TwoParameters[A, B]" -> "found 2 type parameters",
        "trait Covariant[+A]" -> "is covariant",
        "trait Bounded[A <: Product]" -> "explicit or contextual bound",
        "sealed trait Sealed[A]" -> "sealed trait `Sealed`",
        "class ClassTarget[A]" -> "found class `ClassTarget`"
      )
      declarations.zipWithIndex.foreach:
        case ((declaration, fragment), index) =>
          val result = compileConsumer(
            s"""package external.invalidconsumer$index
               |import external.traitprobe.RestrictedApply
               |@RestrictedApply $declaration
               |""".stripMargin,
            probe.artifact,
            includeHandlerPath = true
          )
          assertRejected(result, "@RestrictedApply requires", fragment)
          assertEquals(result.invocationTrace, Nil)
  }

  test("missing explicit handler path remains a controlled loading failure with zero output") {
    withCompiledProbe: probe =>
      val result = compileConsumer(
        """package external.missingconsumer
          |import external.traitprobe.RestrictedApply
          |@RestrictedApply trait MissingHandler[A]
          |""".stripMargin,
        probe.artifact,
        includeHandlerPath = false
      )
      assertRejected(
        result,
        "stage=loading",
        "category=HANDLER_LOAD_FAILURE",
        "external.traitprobe.RestrictedApplyHandler"
      )
      assertEquals(result.invocationTrace, Nil)
  }

  private def withCompiledProbe(body: CompiledProbe => Unit): Unit =
    val root = Files.createTempDirectory("independent-restricted-trait-probe-")
    try
      val classes = root.resolve("classes")
      Files.createDirectories(classes)
      val (exitCode, messages) = runCompiler(
        List(
          "-classpath",
          (pluginApiJar :: compilerUniverse).map(_.getAbsolutePath).mkString(File.pathSeparator),
          "-d",
          classes.toString,
          producerSource.toString
        )
      )
      assertEquals(exitCode, 0, messages.mkString("\n"))
      val artifact = root.resolve("independent-restricted-trait-handler.jar").toFile
      writeJar(classes, artifact)
      body(CompiledProbe(root, artifact))
    finally deleteRecursively(root)

  private def compileConsumer(
      source: String,
      artifact: File,
      includeHandlerPath: Boolean
  ): CompileResult =
    val root = artifact.toPath.getParent.resolve(s"consumer-${java.util.UUID.randomUUID()}")
    val sourceFile = root.resolve("Consumer.scala")
    val output = root.resolve("classes")
    val metadataTrace = root.resolve("metadata.trace")
    val invocationTrace = root.resolve("invocation.trace")
    Files.createDirectories(output)
    Files.writeString(sourceFile, source)
    val handlerOption =
      Option.when(includeHandlerPath)(
        s"-P:helloWorld:handlerClasspath=${artifact.getAbsolutePath}"
      ).toList
    val (exitCode, messages) = runCompiler(
      List(
        "-classpath",
        (artifact :: pluginApiJar :: compilerUniverse).map(_.getAbsolutePath).distinct.mkString(File.pathSeparator),
        "-d",
        output.toString,
        s"-Xplugin:${Seq(pluginJar, pluginApiJar).map(_.getAbsolutePath).mkString(File.pathSeparator)}",
        "-Xplugin-require:helloWorld"
      ) ++ handlerOption ++ List(sourceFile.toString),
      jvmProperties = List(
        "macroparadise.metadataReaderTrace" -> metadataTrace.toString,
        "macroparadise.externalHandlerInvocationTrace" -> invocationTrace.toString
      )
    )
    CompileResult(
      exitCode != 0,
      messages,
      outputFiles(output),
      readLines(metadataTrace),
      readLines(invocationTrace),
      output
    )

  private def runCompiler(
      arguments: List[String],
      jvmProperties: List[(String, String)] = Nil
  ): (Int, List[String]) =
    val messages = scala.collection.mutable.ListBuffer.empty[String]
    val command =
      javaTool ::
        jvmProperties.map((name, value) => s"-D$name=$value") :::
        List(
          "-cp",
          compilerUniverse.map(_.getAbsolutePath).mkString(File.pathSeparator),
          "dotty.tools.dotc.Main"
        ) ::: arguments
    val exitCode =
      Process(command, new File(".")).!(ProcessLogger(
        line => messages += line,
        line => messages += line
      ))
    exitCode -> messages.toList

  private def assertRejected(result: CompileResult, fragments: String*): Unit =
    val messages = result.messages.mkString("\n")
    assert(result.hasErrors, s"expected controlled rejection; output=${result.outputFiles.mkString(",")}")
    fragments.foreach(fragment => assert(messages.contains(fragment), messages))
    List("internal compiler error", "ClassCastException", "MatchError", "exception occurred while typechecking")
      .foreach(fragment => assert(!messages.contains(fragment), messages))
    assertEquals(result.outputFiles, Nil)

  private def outputFiles(root: Path): List[String] =
    val stream = Files.walk(root)
    try
      stream.iterator().asScala
        .filter(Files.isRegularFile(_))
        .map(root.relativize(_).toString.replace(File.separatorChar, '/'))
        .toList
        .sorted
    finally stream.close()

  private def readLines(path: Path): List[String] =
    if Files.isRegularFile(path) then Files.readAllLines(path).asScala.toList else Nil

  private def writeJar(classes: Path, artifact: File): Unit =
    val stream = Files.walk(classes)
    val files =
      try stream.iterator().asScala.filter(Files.isRegularFile(_)).toVector.sortBy(_.toString)
      finally stream.close()
    val output = new JarOutputStream(Files.newOutputStream(artifact.toPath))
    try
      files.foreach: file =>
        val name = classes.relativize(file).toString.replace(File.separatorChar, '/')
        val entry = new JarEntry(name)
        entry.setTime(0L)
        output.putNextEntry(entry)
        output.write(Files.readAllBytes(file))
        output.closeEntry()
    finally output.close()

  private def jarEntries(artifact: File): List[String] =
    val jar = new java.util.jar.JarFile(artifact)
    try jar.entries().asScala.map(_.getName).toList
    finally jar.close()

  private def codeSourcePath(clazz: Class[?]): File =
    new File(clazz.getProtectionDomain.getCodeSource.getLocation.toURI).getAbsoluteFile

  private def javaTool: String =
    new File(new File(System.getProperty("java.home"), "bin"), "java").getAbsolutePath

  private def deleteRecursively(root: Path): Unit =
    if Files.exists(root) then
      val stream = Files.walk(root)
      try stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.delete)
      finally stream.close()
