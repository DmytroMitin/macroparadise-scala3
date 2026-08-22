import dotty.tools.dotc.Main
import dotty.tools.dotc.interfaces.{Diagnostic, SimpleReporter}

import java.io.File
import java.nio.file.Files

class ExternalHelperDiagnosticProvenanceSpec extends munit.FunSuite:
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
  private val pluginPath = Seq(pluginJar, markerJar).mkString(File.pathSeparator)

  private def codeSourcePath(clazz: Class[?]): String =
    new File(clazz.getProtectionDomain.getCodeSource.getLocation.toURI).getAbsolutePath

  private val compileClasspath =
    Seq(
      codeSourcePath(classOf[scala.Option[?]]),
      codeSourcePath(classOf[scala.deriving.Mirror]),
      pluginJar,
      pluginApiJar,
      markerJar
    ).distinct.mkString(File.pathSeparator)

  test("external sibling conflict diagnostic anchors the current annotation") {
    val source =
      """package provenance
        |
        |import paradise3.externalSiblingDebug
        |
        |@externalSiblingDebug
        |class SiblingConflict
        |
        |class SiblingConflictExternalMeta
        |""".stripMargin
    val result = compileSnippet(source)
    val diagnostic = result.errorContaining("generated sibling `SiblingConflictExternalMeta` already exists")

    assertAnchor(diagnostic, source, "@externalSiblingDebug", "current-annotation")
  }

  test("malformed raw output diagnostic anchors the current annotation") {
    val source =
      """package provenance
        |
        |import paradise3.malformedEmptyOutput
        |
        |@malformedEmptyOutput
        |class MalformedRaw
        |""".stripMargin
    val result = compileSnippet(
      source,
      Seq("-P:macroparadise:handler=demo.MalformedEmptyOutputExpander")
    )
    val diagnostic = result.errorContaining("invalid raw expansion output")

    assertAnchor(diagnostic, source, "@malformedEmptyOutput", "current-annotation")
  }

  test("malformed structured output diagnostic anchors the current annotation") {
    val source =
      """package provenance
        |
        |import paradise3.structuredNullOutput
        |
        |@structuredNullOutput
        |class MalformedStructured
        |""".stripMargin
    val result = compileSnippet(
      source,
      Seq("-P:macroparadise:handler=demo.StructuredNullOutputExpander")
    )
    val diagnostic = result.errorContaining("invalid structured expansion output")

    assertAnchor(diagnostic, source, "@structuredNullOutput", "current-annotation")
  }

  test("composition-closure diagnostic retains the exact current annotation") {
    val source =
      """package provenance
        |
        |import paradise3.externalDebug
        |import scala.annotation.StaticAnnotation
        |
        |final class compositionRetainsCurrent extends StaticAnnotation
        |
        |@compositionRetainsCurrent
        |@externalDebug
        |class ClosureViolation
        |""".stripMargin
    val result = compileSnippet(
      source,
      Seq("-P:macroparadise:handler=demo.CompositionRetainsCurrentExpander")
    )
    val diagnostic = result.errorContaining("category=COMPOSITION_ANNOTATION_PRESERVATION")

    assertAnchor(diagnostic, source, "@compositionRetainsCurrent", "current-annotation")
  }

  test("ordinary typer diagnostic for a generated sibling reference remains on user source") {
    val source =
      """package provenance
        |
        |import paradise3.externalSiblingDebug
        |
        |@externalSiblingDebug
        |class GeneratedReference
        |
        |object GeneratedReferenceWitness:
        |  val broken = new GeneratedReferenceExternalMeta().missingMember
        |""".stripMargin
    val result = compileSnippet(source)
    val diagnostic = result.errorContaining("missingMember")

    val reference = "new GeneratedReferenceExternalMeta().missingMember"
    assertAnchor(diagnostic, source, reference, "ordinary-user-reference")
    assertEquals(
      diagnostic.position.map(_.point),
      Some(source.indexOf("missingMember"))
    )
    assert(!diagnostic.message.contains("stage="), diagnostic.message)
  }

  private final case class PositionSnapshot(
      sourcePath: String,
      lineContent: String,
      start: Int,
      end: Int,
      point: Int
  )

  private final case class DiagnosticSnapshot(
      message: String,
      level: Int,
      position: Option[PositionSnapshot]
  )

  private final case class CompileResult(
      hasErrors: Boolean,
      diagnostics: List[DiagnosticSnapshot],
      outputFiles: List[String]
  ):
    def errorContaining(fragment: String): DiagnosticSnapshot =
      assert(hasErrors, s"expected compilation errors; diagnostics=$diagnostics outputs=$outputFiles")
      diagnostics.find(value => value.level == Diagnostic.ERROR && value.message.contains(fragment))
        .getOrElse(fail(s"missing diagnostic containing `$fragment` in $diagnostics"))

  private final class CollectingReporter extends SimpleReporter:
    val diagnostics = scala.collection.mutable.ListBuffer.empty[DiagnosticSnapshot]

    override def report(diagnostic: Diagnostic): Unit =
      val position = diagnostic.position()
      val snapshot =
        if position.isPresent then
          val value = position.get()
          Some(
            PositionSnapshot(
              value.source().path(),
              value.lineContent(),
              value.start(),
              value.end(),
              value.point()
            )
          )
        else None
      diagnostics += DiagnosticSnapshot(diagnostic.message(), diagnostic.level(), snapshot)

  private def compileSnippet(source: String, extraPluginOptions: Seq[String] = Nil): CompileResult =
    val tempDir = Files.createTempDirectory("macroparadise-external-helper-diagnostics")
    val sourceFile = tempDir.resolve("Snippet.scala")
    val outDir = tempDir.resolve("out")
    Files.createDirectories(outDir)
    Files.writeString(sourceFile, source)
    val reporter = new CollectingReporter
    val result =
      Main.process(
        Array(
          "-classpath",
          compileClasspath,
          "-d",
          outDir.toString,
          s"-Xplugin:$pluginPath",
          "-Xplugin-require:macroparadise",
          s"-P:macroparadise:handlerClasspath=$handlerJar"
        ) ++ extraPluginOptions.toArray ++ Array(sourceFile.toString),
        reporter,
        null
      )
    CompileResult(result.hasErrors(), reporter.diagnostics.toList, regularFiles(outDir))

  private def regularFiles(directory: java.nio.file.Path): List[String] =
    val paths = Files.walk(directory)
    try
      paths
        .filter(path => Files.isRegularFile(path))
        .map(path => directory.relativize(path).toString)
        .sorted()
        .toArray
        .toList
        .map(_.toString)
    finally paths.close()

  private def assertAnchor(
      diagnostic: DiagnosticSnapshot,
      source: String,
      expectedText: String,
      anchorKind: String
  ): Unit =
    val position = diagnostic.position.getOrElse(fail(s"missing diagnostic position: ${diagnostic.message}"))
    val expectedStart = source.indexOf(expectedText)
    assert(expectedStart >= 0, s"fixture missing `$expectedText`")
    val expectedEnd = expectedStart + expectedText.length
    println(
      s"EXTERNAL_HELPER_DIAGNOSTIC_PROVENANCE category=${diagnostic.message.takeWhile(_ != ' ')} " +
        s"source=${position.sourcePath} start=${position.start} end=${position.end} point=${position.point} " +
        s"anchorKind=$anchorKind text=${source.slice(position.start, position.end)}"
    )
    assert(position.sourcePath.endsWith("Snippet.scala"), position.sourcePath)
    assertEquals(position.start, expectedStart)
    assertEquals(position.end, expectedEnd)
    assert(position.point >= position.start && position.point <= position.end, clue(position))
