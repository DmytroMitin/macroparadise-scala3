import dotty.tools.dotc.Main
import dotty.tools.dotc.interfaces.{Diagnostic, SimpleReporter}

import java.io.File
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

class StructuredExpansionOutcomeIntegrationSpec extends munit.FunSuite:
  private val scalaVersion =
    sys.props.getOrElse(
      "macroparadise.testScalaVersion",
      "3.8.5-RC1-bin-20260405-9478256-NIGHTLY"
    )
  private val pluginJar =
    new File(
      s"plugin/target/scala-$scalaVersion/macroparadise-scala3-plugin_$scalaVersion-0.1.0-SNAPSHOT.jar"
    ).getAbsolutePath
  private val pluginApiJar =
    new File(
      s"plugin-api/target/scala-$scalaVersion/macroparadise-scala3-plugin-api_$scalaVersion-0.1.0-SNAPSHOT.jar"
    ).getAbsolutePath
  private val markerJar =
    new File(
      s"plugin-test-markers/target/scala-$scalaVersion/macroparadise-scala3-plugin-test-markers_3-0.1.0-SNAPSHOT.jar"
    ).getAbsolutePath
  private val handlerJar =
    new File(
      s"plugin-test-handlers/target/scala-$scalaVersion/macroparadise-scala3-plugin-test-handlers_3-0.1.0-SNAPSHOT.jar"
    ).getAbsolutePath
  private val pluginPath =
    Seq(pluginJar, pluginApiJar, markerJar).mkString(File.pathSeparator)

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

  private final class CollectingReporter extends SimpleReporter:
    val messages = scala.collection.mutable.ListBuffer.empty[String]

    override def report(diagnostic: Diagnostic): Unit =
      messages += diagnostic.message()

  private enum CompileOutcome:
    case ReportedErrors(messages: List[String], outputFiles: List[String])
    case Threw(throwable: Throwable, outputFiles: List[String])
    case Succeeded(outputFiles: List[String])

  private def compileSnippet(
      source: String,
      handlerClassName: Option[String]
  ): CompileOutcome =
    val tempDir = Files.createTempDirectory("macroparadise-structured-outcome")
    val sourceFile = tempDir.resolve("Snippet.scala")
    val outDir = tempDir.resolve("out")
    Files.createDirectories(outDir)
    Files.writeString(sourceFile, source)

    val pluginOptions =
      Seq(s"-P:helloWorld:handlerClasspath=$handlerJar") ++
        handlerClassName.toList.map(name => s"-P:helloWorld:handler=$name")
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
            "-Xplugin-require:helloWorld"
          ) ++ pluginOptions.toArray ++ Array(sourceFile.toString),
          reporter,
          null
        )
      val outputs = outputFiles(outDir)
      if result.hasErrors() then
        CompileOutcome.ReportedErrors(reporter.messages.toList, outputs)
      else CompileOutcome.Succeeded(outputs)
    catch
      case throwable: Throwable =>
        CompileOutcome.Threw(throwable, outputFiles(outDir))

  private def outputFiles(outDir: Path): List[String] =
    val stream = Files.walk(outDir)
    try
      stream.iterator().asScala
        .filter(Files.isRegularFile(_))
        .map(outDir.relativize(_).toString)
        .toList
        .sorted
    finally stream.close()

  private def assertStructuredDiagnostic(
      outcome: CompileOutcome,
      expectedFragments: String*
  ): Unit =
    outcome match
      case CompileOutcome.ReportedErrors(messages, outputs) =>
        val diagnostic = messages.mkString("\n")
        expectedFragments.foreach: fragment =>
          assert(diagnostic.contains(fragment), diagnostic)
        assert(diagnostic.contains("stage=output-validation"), diagnostic)
        assertEquals(
          messages.count(_.contains("invalid structured expansion output")),
          1,
          diagnostic
        )
        List(
          "MatchError",
          "NullPointerException",
          "ClassCastException",
          "LinkageError",
          "AssertionError",
          "internal compiler error",
          "already defined"
        ).foreach: forbidden =>
          assert(!diagnostic.contains(forbidden), diagnostic)
        assertEquals(
          outputs,
          Nil,
          s"unexpected partial class/Tasty output: ${outputs.mkString(", ")}"
        )
      case CompileOutcome.Threw(throwable, outputs) =>
        fail(
          s"expected a controlled structured diagnostic, got ${throwable.getClass.getName}: " +
            s"${throwable.getMessage}; outputs=${outputs.mkString(",")}"
        )
      case CompileOutcome.Succeeded(outputs) =>
        fail(s"expected structured rejection, compilation emitted ${outputs.mkString(",")}")

  private def standaloneSource(annotationName: String, className: String): String =
    s"""package structuredoutcome
       |
       |import paradise3.$annotationName
       |
       |@$annotationName
       |class $className
       |""".stripMargin

  private def directCase(
      annotationName: String,
      className: String,
      handlerClassName: String,
      category: String
  ): Unit =
    assertStructuredDiagnostic(
      compileSnippet(
        standaloneSource(annotationName, className),
        Some(handlerClassName)
      ),
      s"external handler `$handlerClassName`",
      s"annotation=@$annotationName",
      s"class=$className",
      s"category=$category"
    )

  test("null structured output is rejected at the external boundary") {
    directCase(
      "structuredNullOutput",
      "StructuredNullOutputUser",
      "demo.StructuredNullOutputExpander",
      "NULL_OUTPUT"
    )
  }

  test("null structured primary is rejected at the external boundary") {
    directCase(
      "structuredNullPrimary",
      "StructuredNullPrimaryUser",
      "demo.StructuredNullPrimaryExpander",
      "NULL_PRIMARY"
    )
  }

  test("null companion Option is rejected at the external boundary") {
    directCase(
      "structuredNullCompanionOption",
      "StructuredNullCompanionOptionUser",
      "demo.StructuredNullCompanionOptionExpander",
      "NULL_COMPANION_OPTION"
    )
  }

  test("Some(null) companion is rejected at the external boundary") {
    directCase(
      "structuredNullCompanion",
      "StructuredNullCompanionUser",
      "demo.StructuredNullCompanionExpander",
      "NULL_COMPANION"
    )
  }

  test("null additional list restores a leased companion exactly once") {
    val className = "StructuredNullAdditionalListUser"
    val outcome =
      compileSnippet(
        s"""package structuredoutcome
           |
           |import paradise3.structuredNullAdditionalList
           |
           |@structuredNullAdditionalList
           |class $className
           |
           |object $className:
           |  val preserved: Int = 42
           |
           |object StructuredNullAdditionalListWitness:
           |  val preserved: Int = $className.preserved
           |""".stripMargin,
        Some("demo.StructuredNullAdditionalListExpander")
      )
    assertStructuredDiagnostic(
      outcome,
      "external handler `demo.StructuredNullAdditionalListExpander`",
      "annotation=@structuredNullAdditionalList",
      s"class=$className",
      "category=NULL_ADDITIONAL_LIST"
    )
  }

  test("null additional element is rejected at the external boundary") {
    directCase(
      "structuredNullAdditionalElement",
      "StructuredNullAdditionalElementUser",
      "demo.StructuredNullAdditionalElementExpander",
      "NULL_ADDITIONAL_ELEMENT"
    )
  }

  test("unknown additional raw kind is rejected only by the structured path") {
    directCase(
      "structuredUnknownAdditional",
      "StructuredUnknownAdditionalUser",
      "demo.StructuredUnknownAdditionalExpander",
      "UNSUPPORTED_ADDITIONAL_TREE_KIND"
    )
  }

  test("structured additional top-level conflict is rejected before splicing") {
    val outcome =
      compileSnippet(
        """package structuredoutcome
          |
          |import paradise3.structuredTopLevelConflict
          |
          |class KnownConflict
          |
          |@structuredTopLevelConflict
          |class StructuredTopLevelConflictUser
          |""".stripMargin,
        Some("demo.StructuredTopLevelConflictExpander")
      )
    assertStructuredDiagnostic(
      outcome,
      "external handler `demo.StructuredTopLevelConflictExpander`",
      "annotation=@structuredTopLevelConflict",
      "class=StructuredTopLevelConflictUser",
      "category=TOP_LEVEL_NAME_CONFLICT",
      "actual=name=KnownConflict"
    )
  }

  test("later malformed structured composition rolls back every earlier output lane") {
    val outcome =
      compileSnippet(
        """package structuredoutcome
          |
          |import paradise3.{externalCompanionDebug, gen}
          |
          |@gen
          |@externalCompanionDebug
          |class StructuredCompositionFailureUser(name: String):
          |  def generatedHello: String = "original"
          |
          |object StructuredCompositionFailureUser:
          |  val preserved: Int = 42
          |  def generatedFactory(name: String): StructuredCompositionFailureUser =
          |    new StructuredCompositionFailureUser(name)
          |
          |object StructuredCompositionFailureWitness:
          |  val preserved: Int = StructuredCompositionFailureUser.preserved
          |""".stripMargin,
        None
      )
    assertStructuredDiagnostic(
      outcome,
      "external handler `demo.ExternalCompanionDebugExpander`",
      "annotation=@externalCompanionDebug",
      "class=StructuredCompositionFailureUser",
      "category=NULL_ADDITIONAL_LIST"
    )
  }
