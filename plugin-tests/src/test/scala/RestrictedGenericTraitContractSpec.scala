import dotty.tools.dotc.Main
import dotty.tools.dotc.interfaces.{Diagnostic, SimpleReporter}

import java.io.File
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

class RestrictedGenericTraitContractSpec extends munit.FunSuite:
  private val scalaVersion =
    sys.props.getOrElse(
      "macroparadise.testScalaVersion",
      "3.8.5-RC1-bin-20260405-9478256-NIGHTLY"
    )
  private val pluginJar =
    new File(s"plugin/target/scala-$scalaVersion/macroparadise-scala3-plugin_$scalaVersion-0.1.0.jar").getAbsolutePath
  private val pluginApiJar =
    new File(s"plugin-api/target/scala-$scalaVersion/macroparadise-scala3-plugin-api_$scalaVersion-0.1.0.jar").getAbsolutePath
  private val markerJar =
    new File(s"plugin-test-markers/target/scala-$scalaVersion/macroparadise-scala3-plugin-test-markers_3-0.1.0.jar").getAbsolutePath
  private val handlerJar =
    new File(s"plugin-test-handlers/target/scala-$scalaVersion/macroparadise-scala3-plugin-test-handlers_3-0.1.0.jar").getAbsolutePath
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

  private final class CollectingReporter extends SimpleReporter:
    val messages = scala.collection.mutable.ListBuffer.empty[String]
    override def report(diagnostic: Diagnostic): Unit = messages += diagnostic.message()

  private final case class CompileResult(
      hasErrors: Boolean,
      messages: List[String],
      outputFiles: List[String],
      invocationTrace: List[String]
  )

  private def compileSnippet(
      source: String,
      explicitHandler: Boolean,
      traceInvocations: Boolean = false
  ): CompileResult =
    val tempDir = Files.createTempDirectory("macroparadise-restricted-trait")
    val sourceFile = tempDir.resolve("Snippet.scala")
    val outDir = tempDir.resolve("out")
    val traceFile = tempDir.resolve("invocations.txt")
    Files.createDirectories(outDir)
    Files.writeString(sourceFile, source)

    val options =
      Seq(s"-P:macroparadise:handlerClasspath=$handlerJar") ++
        Option.when(explicitHandler)(
          "-P:macroparadise:handler=demo.ExternalRestrictedTraitApplyExpander"
        ) ++
        Option.when(traceInvocations)(
          s"-P:macroparadise:externalHandlerInvocationTrace=$traceFile"
        )
    val reporter = new CollectingReporter
    val result =
      Main.process(
        Array(
          "-classpath",
          compileClasspath,
          "-d",
          outDir.toString,
          s"-Xplugin:$pluginPath",
          "-Xplugin-require:macroparadise"
        ) ++ options.toArray ++ Array(sourceFile.toString),
        reporter,
        null
      )
    CompileResult(
      result.hasErrors(),
      reporter.messages.toList,
      outputFiles(outDir),
      if Files.exists(traceFile) then Files.readAllLines(traceFile).asScala.toList else Nil
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

  private def topLevelTrait(name: String, declaration: String = "[A]"): String =
    s"""package restrictedprobe
       |import paradise3.externalRestrictedTraitApply
       |@externalRestrictedTraitApply
       |trait $name$declaration
       |""".stripMargin

  private def assertRejected(
      source: String,
      expectedFragments: String*
  ): Unit =
    val result = compileSnippet(source, explicitHandler = true)
    val diagnostic = result.messages.mkString("\n")
    assert(result.hasErrors, s"expected rejection; outputs=${result.outputFiles.mkString(",")}")
    expectedFragments.foreach(fragment => assert(diagnostic.contains(fragment), diagnostic))
    List(
      "internal compiler error",
      "ClassCastException",
      "MatchError",
      "already defined",
      "exception occurred while typechecking"
    ).foreach(forbidden => assert(!diagnostic.contains(forbidden), diagnostic))
    assertEquals(result.outputFiles, Nil, s"unexpected partial class/Tasty output: ${result.outputFiles}")

  test("metadata discovery invokes the restricted precompiled handler exactly once and types generated apply") {
    val result = compileSnippet(
      """package restrictedprobe
        |import paradise3.externalRestrictedTraitApply
        |@externalRestrictedTraitApply
        |trait Show[A]
        |object Witness:
        |  val supplied: Show[String] = new Show[String] {}
        |  val returned: Show[String] = Show.apply[String](using supplied)
        |""".stripMargin,
      explicitHandler = false,
      traceInvocations = true
    )
    assert(!result.hasErrors, result.messages.mkString("\n"))
    assert(result.outputFiles.exists(_.endsWith("Show.class")), result.outputFiles.mkString(","))
    assert(result.outputFiles.exists(_.endsWith("Show.tasty")), result.outputFiles.mkString(","))
    val restrictedInvocations =
      result.invocationTrace.filter(
        _ == "handler=demo.ExternalRestrictedTraitApplyExpander annotation=externalRestrictedTraitApply class=Show"
      )
    assertEquals(
      restrictedInvocations,
      List(
        "handler=demo.ExternalRestrictedTraitApplyExpander annotation=externalRestrictedTraitApply class=Show"
      )
    )
  }

  test("restricted admission rejects every non-envelope target without partial output") {
    val cases = List(
      """package restrictedprobe
        |import paradise3.externalRestrictedTraitApply
        |@externalRestrictedTraitApply
        |class NotATrait[A]
        |""".stripMargin -> "found class `NotATrait`",
      topLevelTrait("SealedShow", "[A]").replace("trait SealedShow", "sealed trait SealedShow") -> "sealed trait `SealedShow`",
      topLevelTrait("NoTypeParameter", "") -> "found 0 type parameters",
      topLevelTrait("TwoTypeParameters", "[A, B]") -> "found 2 type parameters",
      topLevelTrait("CovariantShow", "[+A]") -> "is covariant",
      topLevelTrait("ContravariantShow", "[-A]") -> "is contravariant",
      topLevelTrait("BoundedShow", "[A <: Product]") -> "explicit or contextual bound",
      topLevelTrait("ContextualShow", "[A: Ordering]") -> "explicit or contextual bound",
      topLevelTrait("ConstructorShow", "[A](val value: A)") -> "constructor/value parameters are unsupported"
    )
    cases.foreach: (source, fragment) =>
      assertRejected(source, "@externalRestrictedTraitApply requires", fragment)
  }

  test("object enum nested and local trait targets are controlled rejections") {
    assertRejected(
      """package restrictedprobe
        |import paradise3.externalRestrictedTraitApply
        |@externalRestrictedTraitApply object RestrictedObject
        |""".stripMargin,
      "restricted top-level generic trait envelope",
      "object RestrictedObject"
    )
    assertRejected(
      """package restrictedprobe
        |import paradise3.externalRestrictedTraitApply
        |@externalRestrictedTraitApply enum RestrictedEnum:
        |  case One
        |""".stripMargin,
      "restricted top-level generic trait envelope",
      "enum RestrictedEnum"
    )
    assertRejected(
      """package restrictedprobe
        |import paradise3.externalRestrictedTraitApply
        |object Outer:
        |  @externalRestrictedTraitApply trait Nested[A]
        |""".stripMargin,
      "restricted top-level generic trait envelope",
      "nested trait Nested"
    )
    assertRejected(
      """package restrictedprobe
        |import paradise3.externalRestrictedTraitApply
        |def make =
        |  @externalRestrictedTraitApply trait Local[A]
        |  new Local[String] {}
        |""".stripMargin,
      "restricted top-level generic trait envelope",
      "local trait Local"
    )
  }

  test("leased companions roll back on handler rejection and invocation failures") {
    val cases = List(
      "RestrictedTraitHandlerRejected" -> "restricted trait handler rejection fixture",
      "RestrictedTraitNonFatal" -> "category=NONFATAL_EXCEPTION",
      "RestrictedTraitLinkage" -> "category=LINKAGE_ERROR"
    )
    cases.foreach: (name, fragment) =>
      assertRejected(withExistingCompanion(name), fragment)
  }

  test("leased companions roll back on every exercised structured validation failure") {
    val cases = List(
      "RestrictedTraitNullStructured" -> "category=NULL_OUTPUT",
      "RestrictedTraitWrongPrimaryName" -> "category=PRIMARY_NAME_MISMATCH",
      "RestrictedTraitWrongPrimaryKind" -> "category=PRIMARY_KIND_MISMATCH",
      "RestrictedTraitWrongCompanion" -> "category=COMPANION_NAME_MISMATCH",
      "RestrictedTraitDuplicateAdditional" -> "category=DUPLICATE_ADDITIONAL_NAME"
    )
    cases.foreach: (name, fragment) =>
      assertRejected(withExistingCompanion(name), "stage=output-validation", fragment)
  }

  private def withExistingCompanion(name: String): String =
    s"""package restrictedprobe
       |import paradise3.externalRestrictedTraitApply
       |@externalRestrictedTraitApply trait $name[A]
       |object $name:
       |  val preservedBefore: Int = 1
       |  object Nested:
       |    def apply(value: Int): Int = value
       |  def applyLike(value: Int): Int = value
       |  val preservedAfter: Int = 2
       |""".stripMargin
