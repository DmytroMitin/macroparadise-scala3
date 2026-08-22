import dotty.tools.dotc.Main
import dotty.tools.dotc.interfaces.{Diagnostic, SimpleReporter}

import java.io.File
import java.nio.file.{Files, Path}
import java.util.concurrent.{Callable, FutureTask, TimeUnit}
import scala.jdk.CollectionConverters.*

class ExternalHandlerTraceIsolationSpec extends munit.FunSuite:
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
    ).distinct.mkString(File.pathSeparator)

  private final case class TraceCase(
      annotation: String,
      handlerClass: String,
      className: String
  )

  private final case class CompileResult(
      hasErrors: Boolean,
      diagnostics: List[String],
      invocationTrace: List[String],
      metadataTrace: List[String]
  )

  private final class CollectingReporter extends SimpleReporter:
    val diagnostics = scala.collection.mutable.ListBuffer.empty[String]

    override def report(diagnostic: Diagnostic): Unit =
      diagnostics += diagnostic.message()

  test("parallel compiler invocations own disjoint handler and metadata traces"):
    val invocationProperty = "macroparadise.externalHandlerInvocationTrace"
    val metadataProperty = "macroparadise.metadataReaderTrace"
    val legacyInvocationTrap = Files.createTempFile("macroparadise-legacy-invocation", ".trace")
    val legacyMetadataTrap = Files.createTempFile("macroparadise-legacy-metadata", ".trace")
    val previousInvocation = Option(System.getProperty(invocationProperty))
    val previousMetadata = Option(System.getProperty(metadataProperty))
    System.setProperty(invocationProperty, legacyInvocationTrap.toString)
    System.setProperty(metadataProperty, legacyMetadataTrap.toString)

    try
      (1 to 4).foreach: round =>
        val left = TraceCase("externalDebug", "demo.ExternalDebugExpander", s"Left$round")
        val right = TraceCase("externalSiblingDebug", "demo.ExternalSiblingDebugExpander", s"Right$round")

        def task(testCase: TraceCase) =
          FutureTask(
            new Callable[CompileResult]:
              override def call(): CompileResult =
                compile(testCase)
          )

        val leftFuture = task(left)
        val rightFuture = task(right)
        val leftThread = Thread(leftFuture, s"trace-isolation-left-$round")
        val rightThread = Thread(rightFuture, s"trace-isolation-right-$round")
        leftThread.start()
        rightThread.start()

        val results =
          List(
            left -> leftFuture.get(90, TimeUnit.SECONDS),
            right -> rightFuture.get(90, TimeUnit.SECONDS)
          )

        results.foreach: (testCase, result) =>
          assert(!result.hasErrors, result.diagnostics.mkString("\n"))
          assertEquals(
            result.invocationTrace,
            List(
              s"handler=${testCase.handlerClass} annotation=${testCase.annotation} class=${testCase.className}"
            )
          )
          assertEquals(
            result.metadataTrace,
            List(
              s"runtime paradise3.${testCase.annotation} Found(${testCase.handlerClass})"
            )
          )

      assertEquals(readLines(legacyInvocationTrap), Nil)
      assertEquals(readLines(legacyMetadataTrap), Nil)
    finally
      restoreProperty(invocationProperty, previousInvocation)
      restoreProperty(metadataProperty, previousMetadata)

  private def compile(testCase: TraceCase): CompileResult =
    val root = Files.createTempDirectory("macroparadise-trace-isolation")
    val sourceFile = root.resolve("Snippet.scala")
    val output = root.resolve("classes")
    val invocationTrace = root.resolve("invocation.trace")
    val metadataTrace = root.resolve("metadata.trace")
    Files.createDirectories(output)
    Files.writeString(
      sourceFile,
      s"""package traceisolation
         |
         |import paradise3.${testCase.annotation}
         |
         |@${testCase.annotation}
         |class ${testCase.className}
         |""".stripMargin
    )

    val reporter = CollectingReporter()
    val result =
      Main.process(
        Array(
          "-classpath",
          compileClasspath,
          "-d",
          output.toString,
          s"-Xplugin:$pluginPath",
          "-Xplugin-require:macroparadise",
          s"-P:macroparadise:handlerClasspath=$handlerJar",
          s"-P:macroparadise:externalHandlerInvocationTrace=$invocationTrace",
          s"-P:macroparadise:metadataReaderTrace=$metadataTrace",
          sourceFile.toString
        ),
        reporter,
        null
      )

    CompileResult(
      result.hasErrors(),
      reporter.diagnostics.toList,
      readLines(invocationTrace),
      readLines(metadataTrace)
    )

  private def readLines(path: Path): List[String] =
    if Files.exists(path) then Files.readAllLines(path).asScala.toList
    else Nil

  private def restoreProperty(name: String, previous: Option[String]): Unit =
    previous match
      case Some(value) => System.setProperty(name, value)
      case None => System.clearProperty(name)
