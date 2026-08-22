import dotty.tools.dotc.Main
import dotty.tools.dotc.interfaces.{Diagnostic, SimpleReporter}

import java.io.File
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

class RejectedTargetRecoverySpec extends munit.FunSuite:
  private val scalaVersion =
    sys.props.getOrElse(
      "macroparadise.testScalaVersion",
      "3.8.4"
    )
  private val pluginJar =
    new File(
      s"plugin/target/scala-$scalaVersion/macroparadise-scala3-plugin_$scalaVersion-0.1.0.jar"
    ).getAbsolutePath
  private val pluginApiJar =
    new File(
      s"plugin-api/target/scala-$scalaVersion/macroparadise-scala3-plugin-api_$scalaVersion-0.1.0.jar"
    ).getAbsolutePath
  private val markerJar =
    new File(
      s"plugin-test-markers/target/scala-$scalaVersion/macroparadise-scala3-plugin-test-markers_3-0.1.0.jar"
    ).getAbsolutePath
  private val handlerJar =
    new File(
      s"plugin-test-handlers/target/scala-$scalaVersion/macroparadise-scala3-plugin-test-handlers_3-0.1.0.jar"
    ).getAbsolutePath
  private val pluginPath =
    Seq(pluginJar, markerJar).mkString(File.pathSeparator)
  private def codeSourcePath(clazz: Class[?]): String =
    new File(clazz.getProtectionDomain.getCodeSource.getLocation.toURI)
      .getAbsolutePath
  private val compileClasspath =
    Seq(
      codeSourcePath(classOf[scala.Option[?]]),
      codeSourcePath(classOf[scala.deriving.Mirror]),
      pluginJar,
      pluginApiJar,
      markerJar
    )
      .distinct
      .mkString(File.pathSeparator)
  private val externalHandlerOptions =
    Seq(s"-P:macroparadise:handlerClasspath=$handlerJar")

  private final class CollectingReporter extends SimpleReporter:
    val messages = scala.collection.mutable.ListBuffer.empty[String]

    override def report(diagnostic: Diagnostic): Unit =
      messages += diagnostic.message()

  private final case class Probe(
      name: String,
      source: String,
      pluginOptions: Seq[String] = Nil
  )

  private enum CompileOutcome:
    case ReportedErrors(messages: List[String], outputFiles: List[String])
    case Threw(
        throwableClass: String,
        message: String,
        frames: List[String],
        diagnostics: List[String],
        outputFiles: List[String]
    )
    case Succeeded(outputFiles: List[String])

  private def compileSnippet(probe: Probe): CompileOutcome =
    val tempDir = Files.createTempDirectory("macroparadise-recovery")
    val sourceFile = tempDir.resolve("Snippet.scala")
    val outDir = tempDir.resolve("out")
    Files.createDirectories(outDir)
    Files.writeString(sourceFile, probe.source)

    val reporter = new CollectingReporter
    try
      val result =
        Main.process(
          Array(
            "-classpath",
            compileClasspath,
            "-d",
            outDir.toString,
            s"-Xplugin:$pluginPath",
            "-Xplugin-require:macroparadise"
          ) ++ probe.pluginOptions.toArray ++ Array(sourceFile.toString),
          reporter,
          null
        )
      val outputs = outputFiles(outDir)
      if result.hasErrors() then
        CompileOutcome.ReportedErrors(reporter.messages.toList, outputs)
      else CompileOutcome.Succeeded(outputs)
    catch
      case throwable: Throwable =>
        CompileOutcome.Threw(
          throwable.getClass.getName,
          Option(throwable.getMessage).getOrElse(""),
          throwable.getStackTrace.take(12).map(_.toString).toList,
          reporter.messages.toList,
          outputFiles(outDir)
        )

  private def outputFiles(outDir: Path): List[String] =
    val stream = Files.walk(outDir)
    try
      stream.iterator().asScala
        .filter(Files.isRegularFile(_))
        .map(outDir.relativize(_).toString)
        .toList
        .sorted
    finally stream.close()

  private val probes =
    List(
      Probe(
        "top-level @gen case class",
        """import paradise3.gen
          |@gen case class CaseGen(name: String)
          |""".stripMargin
      ),
      Probe(
        "top-level external case class",
        """import paradise3.externalDebug
          |@externalDebug case class CaseExternal(name: String)
          |""".stripMargin,
        externalHandlerOptions
      ),
      Probe(
        "handled enum",
        """import paradise3.externalDebug
          |@externalDebug enum Choice:
          |  case One
          |""".stripMargin,
        externalHandlerOptions
      ),
      Probe(
        "nested handled class",
        """import paradise3.externalDebug
          |object Outer:
          |  @externalDebug class Nested
          |""".stripMargin,
        externalHandlerOptions
      ),
      Probe(
        "local built-in handled class",
        """import paradise3.debug
          |def make =
          |  @debug class Local
          |  new Local
          |""".stripMargin
      ),
      Probe(
        "local metadata-discovered annotation is not discovered",
        """import paradise3.externalDebug
          |def make =
          |  @externalDebug class Local
          |  new Local().externalDebugName
          |""".stripMargin,
        externalHandlerOptions
      ),
      Probe(
        "invalid @gen without generated-output reference",
        """import paradise3.gen
          |@gen class MissingName
          |""".stripMargin
      ),
      Probe(
        "invalid @gen with generated-output references",
        """import paradise3.gen
          |@gen class MissingName
          |object UseMissing:
          |  val member = new MissingName().generatedHello
          |  val factory = MissingName.generatedFactory("x")
          |  val sibling: MissingNameMeta = ???
          |""".stripMargin
      ),
      Probe(
        "invalid supported pair without generated-output reference",
        """import paradise3.{externalDebug, gen}
          |@externalDebug
          |@gen
          |class AtomicInvalid
          |""".stripMargin,
        externalHandlerOptions
      ),
      Probe(
        "invalid supported pair with generated-output references",
        """import paradise3.{externalDebug, gen}
          |@externalDebug
          |@gen
          |class AtomicInvalid
          |object UseAtomic:
          |  val external = new AtomicInvalid().externalDebugName
          |  val generated = new AtomicInvalid().generatedHello
          |  val factory = AtomicInvalid.generatedFactory("x")
          |  val sibling: AtomicInvalidMeta = ???
          |""".stripMargin,
        externalHandlerOptions
      )
    )

  private def assertControlledRejection(
      probe: Probe,
      expectedPluginFragments: String*
  ): List[String] =
    compileSnippet(probe) match
      case CompileOutcome.ReportedErrors(messages, outputFiles) =>
        val diagnostic = messages.mkString("\n")
        expectedPluginFragments.foreach: fragment =>
          assert(diagnostic.contains(fragment), s"${probe.name}\n$diagnostic")
        assertEquals(
          messages.count(message =>
            message.contains("unsupported class family") ||
              message.contains("unsupported constructor shape") ||
              message.contains("currently supports only top-level classes")
          ),
          1,
          s"${probe.name}\n$diagnostic"
        )
        assertNoUncontrolledFailure(probe, diagnostic)
        assertEquals(outputFiles, Nil, s"${probe.name}: $outputFiles")
        messages
      case CompileOutcome.Threw(throwableClass, message, frames, diagnostics, outputFiles) =>
        fail(
          s"${probe.name} threw $throwableClass: $message\n" +
            s"diagnostics=${diagnostics.mkString(" | ")}\n" +
            s"outputs=${outputFiles.mkString(",")}\n${frames.mkString("\n")}"
        )
      case CompileOutcome.Succeeded(outputFiles) =>
        fail(s"${probe.name} unexpectedly succeeded with outputs $outputFiles")

  private def assertNoUncontrolledFailure(probe: Probe, diagnostic: String): Unit =
    List(
      "internal compiler error",
      "ClassCastException",
      "NoSymbol",
      "LinkageError",
      "unhandled exception",
      "exception occurred while typechecking",
      "already defined",
      "package scala.compiletime does not have a member",
      "value gen is not a member of paradise3",
      "value debug is not a member of paradise3",
      "value externalDebug is not a member of paradise3"
    ).foreach: forbidden =>
      assert(!diagnostic.contains(forbidden), s"${probe.name}: found `$forbidden`\n$diagnostic")

  test("top-level @gen case class fails once without an uncontrolled compiler throw") {
    assertControlledRejection(
      probes(0),
      "@gen currently supports ordinary non-case classes",
      "unsupported class family `case class CaseGen`"
    )
  }

  test("top-level constructor-independent case class fails once without an uncontrolled compiler throw") {
    assertControlledRejection(
      probes(1),
      "@externalDebug currently supports ordinary non-case classes",
      "unsupported class family `case class CaseExternal`"
    )
  }

  test("handled enum fails once without an uncontrolled compiler throw") {
    assertControlledRejection(
      probes(2),
      "@externalDebug currently supports only top-level classes",
      "unsupported target `enum Choice`"
    )
  }

  test("nested handled class fails once without an uncontrolled compiler throw") {
    assertControlledRejection(
      probes(3),
      "@externalDebug currently supports only top-level classes",
      "unsupported target `nested class Nested`"
    )
  }

  test("local built-in handled class fails once without an uncontrolled compiler throw") {
    assertControlledRejection(
      probes(4),
      "@debug currently supports only top-level classes",
      "unsupported target `local class Local`"
    )
  }

  test("local metadata-discovered annotation remains outside current traversal") {
    compileSnippet(probes(5)) match
      case CompileOutcome.ReportedErrors(messages, outputFiles) =>
        val diagnostic = messages.mkString("\n")
        assert(diagnostic.contains("value externalDebugName is not a member of Local"), diagnostic)
        assert(!diagnostic.contains("unsupported target"), diagnostic)
        assert(!diagnostic.contains("@externalDebug currently"), diagnostic)
        assertNoUncontrolledFailure(probes(5), diagnostic)
        assertEquals(outputFiles, Nil)
      case CompileOutcome.Threw(throwableClass, message, frames, diagnostics, outputFiles) =>
        fail(
          s"${probes(5).name} threw $throwableClass: $message\n" +
            s"diagnostics=${diagnostics.mkString(" | ")}\n" +
            s"outputs=${outputFiles.mkString(",")}\n${frames.mkString("\n")}"
        )
      case CompileOutcome.Succeeded(outputFiles) =>
        fail(s"${probes(5).name} unexpectedly generated output: $outputFiles")
  }

  test("invalid @gen without a downstream reference is a controlled rejection") {
    assertControlledRejection(
      probes(6),
      "unsupported constructor shape for @gen on `MissingName`",
      "found 0 term parameter clause(s)"
    )
  }

  test("invalid @gen with downstream references has only ordinary missing-output errors") {
    val messages =
      assertControlledRejection(
        probes(7),
        "unsupported constructor shape for @gen on `MissingName`",
        "found 0 term parameter clause(s)"
      ).mkString("\n")
    assert(messages.contains("value generatedHello is not a member of MissingName"), messages)
    assert(messages.contains("value generatedFactory is not a member of object MissingName"), messages)
    assert(messages.contains("Not found: type MissingNameMeta"), messages)
  }

  test("invalid supported pair without a downstream reference rejects before the fold") {
    assertControlledRejection(
      probes(8),
      "unsupported constructor shape for @gen on `AtomicInvalid`",
      "found 0 term parameter clause(s)"
    )
  }

  test("invalid supported pair with downstream references emits no partial output") {
    val messages =
      assertControlledRejection(
        probes(9),
        "unsupported constructor shape for @gen on `AtomicInvalid`",
        "found 0 term parameter clause(s)"
      ).mkString("\n")
    assert(messages.contains("value externalDebugName is not a member of AtomicInvalid"), messages)
    assert(messages.contains("value generatedHello is not a member of AtomicInvalid"), messages)
    assert(messages.contains("value generatedFactory is not a member of object AtomicInvalid"), messages)
    assert(messages.contains("Not found: type AtomicInvalidMeta"), messages)
  }
