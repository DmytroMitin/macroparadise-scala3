import dotty.tools.dotc.Main
import dotty.tools.dotc.interfaces.{Diagnostic, SimpleReporter}

import java.io.File
import java.nio.file.Files

class StructuredFurtherExpansionSpec extends munit.FunSuite:
  private val scalaVersion =
    sys.props.getOrElse("macroparadise.testScalaVersion", "3.8.4")
  private val projectVersion =
    sys.props.getOrElse("macroparadise.testProjectVersion", "0.1.1-SNAPSHOT")
  private val pluginJar =
    new File(
      s"plugin/target/scala-$scalaVersion/macroparadise-scala3-plugin_$scalaVersion-$projectVersion.jar"
    ).getAbsolutePath
  private val pluginApiJar =
    new File(
      s"plugin-api/target/scala-$scalaVersion/macroparadise-scala3-plugin-api_$scalaVersion-$projectVersion.jar"
    ).getAbsolutePath
  private val markerJar =
    new File(
      s"plugin-test-markers/target/scala-$scalaVersion/macroparadise-scala3-plugin-test-markers_3-$projectVersion.jar"
    ).getAbsolutePath
  private val handlerJar =
    new File(
      s"plugin-test-handlers/target/scala-$scalaVersion/macroparadise-scala3-plugin-test-handlers_3-$projectVersion.jar"
    ).getAbsolutePath
  private val pluginPath = Seq(pluginJar, markerJar).mkString(File.pathSeparator)

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

  private val handlerOptions =
    Seq(s"-P:macroparadise:handlerClasspath=$handlerJar") ++
      List(
        "demo.InternalR2AExpander",
        "demo.InternalR2BExpander",
        "demo.InternalR2CExpander",
        "demo.InternalR2SelfExpander",
        "demo.InternalR2MutualSeedExpander",
        "demo.InternalR2MutualAExpander",
        "demo.InternalR2MutualBExpander",
        "demo.InternalR2ChangingSeedExpander",
        "demo.InternalR2ChangingExpander",
        "demo.InternalR2BudgetSeedExpander",
        "demo.InternalR2BudgetExpander",
        "demo.InternalR2LateFailureExpander",
        "demo.InternalR2MalformedExpander",
        "demo.InternalR2RestrictedExpander",
        "demo.InternalR2StandaloneExpander",
        "demo.InternalR2StandaloneGeneratorExpander",
        "demo.InternalR2FreshHandledExpander"
      ).map(name => s"-P:macroparadise:handler=$name")

  private final class CollectingReporter extends SimpleReporter:
    val messages = scala.collection.mutable.ListBuffer.empty[String]

    override def report(diagnostic: Diagnostic): Unit =
      messages += diagnostic.message()

  private sealed trait CompileOutcome
  private object CompileOutcome:
    final case class ReportedErrors(
        messages: List[String],
        outputFiles: List[String]
    ) extends CompileOutcome
    final case class Threw(
        throwable: Throwable,
        outputFiles: List[String]
    ) extends CompileOutcome
    final case class Succeeded(outputFiles: List[String]) extends CompileOutcome

  private def compileSnippet(source: String): CompileOutcome =
    compileSnippetWithTrace(source, withTrace = false)._1

  private def compileSnippetWithTrace(
      source: String,
      withTrace: Boolean
  ): (CompileOutcome, List[String]) =
    val tempDir = Files.createTempDirectory("macroparadise-r2")
    val sourceFile = tempDir.resolve("Snippet.scala")
    val outDir = tempDir.resolve("out")
    val traceFile = tempDir.resolve("invocations.trace")
    Files.createDirectories(outDir)
    Files.writeString(sourceFile, source)

    val reporter = new CollectingReporter
    val traceOptions =
      if withTrace then
        Seq(s"-P:macroparadise:externalHandlerInvocationTrace=$traceFile")
      else Nil
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
          ) ++ (handlerOptions ++ traceOptions).toArray ++ Array(sourceFile.toString),
          reporter,
          null
        )
      val outputs =
        val paths = Files.walk(outDir)
        try
          paths
            .filter(path => Files.isRegularFile(path))
            .map(path => outDir.relativize(path).toString)
            .sorted()
            .toArray
            .toList
            .map(_.toString)
        finally paths.close()
      val outcome =
        if result.hasErrors() then
          CompileOutcome.ReportedErrors(reporter.messages.toList, outputs)
        else CompileOutcome.Succeeded(outputs)
      (outcome, traceLines(traceFile))
    catch
      case throwable: Throwable =>
        (CompileOutcome.Threw(throwable, Nil), traceLines(traceFile))

  private def traceLines(path: java.nio.file.Path): List[String] =
    if !Files.isRegularFile(path) || Files.size(path) == 0 then Nil
    else Files.readAllLines(path).toArray.toList.map(_.toString)

  private def assertSucceeded(outcome: CompileOutcome): Unit =
    outcome match
      case CompileOutcome.Succeeded(outputs) =>
        assert(outputs.nonEmpty, "successful compilation emitted no output")
      case CompileOutcome.ReportedErrors(messages, outputs) =>
        fail(
          s"expected success, got diagnostics=${messages.mkString(" | ")} outputs=${outputs.mkString(",")}"
        )
      case CompileOutcome.Threw(throwable, outputs) =>
        fail(
          s"expected success, got ${throwable.getClass.getName}: ${throwable.getMessage}; outputs=${outputs.mkString(",")}"
        )

  private def assertRejected(
      outcome: CompileOutcome,
      expectedFragments: String*
  ): Unit =
    outcome match
      case CompileOutcome.ReportedErrors(messages, outputs) =>
        val diagnostic = messages.mkString("\n")
        expectedFragments.foreach(fragment => assert(diagnostic.contains(fragment), diagnostic))
        assertEquals(outputs, Nil, s"partial class/Tasty output: ${outputs.mkString(",")}")
        assert(!diagnostic.contains("internal compiler error"), diagnostic)
      case CompileOutcome.Threw(throwable, outputs) =>
        fail(
          s"expected controlled rejection, got ${throwable.getClass.getName}: ${throwable.getMessage}; outputs=${outputs.mkString(",")}"
        )
      case other => fail(s"expected controlled rejection, got $other")

  private def source(body: String): String =
    s"""package internalr2
       |
       |import scala.annotation.StaticAnnotation
       |
       |final class r2A extends StaticAnnotation
       |final class r2B extends StaticAnnotation
       |final class r2C extends StaticAnnotation
       |final class r2Self extends StaticAnnotation
       |final class r2MutualSeed extends StaticAnnotation
       |final class r2ChangingSeed extends StaticAnnotation
       |final class r2BudgetSeed extends StaticAnnotation
       |final class r2StandaloneGenerator extends StaticAnnotation
       |
       |$body
       |""".stripMargin

  private def assertR2ValidationRejectedBeforeR1(
      className: String,
      expectedFragments: String*
  ): Unit =
    val (outcome, trace) =
      compileSnippetWithTrace(
        source(s"@r2A\nclass $className\n"),
        withTrace = true
      )
    assertRejected(outcome, expectedFragments*)
    assertEquals(
      trace.map(_.split(" ").head.stripPrefix("handler=")),
      List("demo.InternalR2AExpander")
    )

  test("R2 lowers an explicit A to B directive into the existing R1 scheduler") {
    val outcome =
      compileSnippet(
        source(
          """@r2A
          |class R2FiniteAB
          |
          |object R2FiniteABWitness:
          |  val a: String = new R2FiniteAB().r2AValue
          |  val b: String = new R2FiniteAB().r2BValue
          |""".stripMargin
        )
      )
    assertSucceeded(outcome)
  }

  test("R2 rejects an empty logical annotation identity before R1") {
    assertR2ValidationRejectedBeforeR1(
      "R2EmptyName",
      "stage=structured-r2-validation",
      "category=STRUCTURED_R2_DIRECTIVE_REJECTED",
      "directive annotation name is empty"
    )
  }

  test("R2 rejects a raw application whose terminal name mismatches the directive") {
    assertR2ValidationRejectedBeforeR1(
      "R2NameMismatch",
      "stage=structured-r2-validation",
      "raw application names @r2C",
      "directive names @r2B"
    )
  }

  test("R2 rejects a non-constructor raw application before R1") {
    assertR2ValidationRejectedBeforeR1(
      "R2MalformedRaw",
      "stage=structured-r2-validation",
      "expected a constructor application"
    )
  }

  test("R2 rejects a null raw application before R1") {
    assertR2ValidationRejectedBeforeR1(
      "R2NullRaw",
      "stage=structured-r2-validation",
      "directive raw application is null"
    )
  }

  test("R2 rejects provenance that is not explicitly generated or delegated") {
    assertR2ValidationRejectedBeforeR1(
      "R2UnsupportedProvenance",
      "stage=structured-r2-validation",
      "directive provenance is not generated/delegated"
    )
  }

  test("R2 rejects reuse of an original source annotation object") {
    assertR2ValidationRejectedBeforeR1(
      "R2SourceObjectReuse",
      "stage=structured-r2-validation",
      "original source annotation object"
    )
  }

  test("R2 rejects mixed direct R1 then structured R2 authoring") {
    val (outcome, trace) =
      compileSnippetWithTrace(
        source("""@r2A
                 |class R2MixedR1ThenR2
                 |""".stripMargin),
        withTrace = true
      )
    assertRejected(
      outcome,
      "stage=structured-r2-validation",
      "category=MIXED_INTERNAL_R1_R2_AUTHORING"
    )
    assert(!trace.exists(_.contains("InternalR2BExpander")), trace.mkString("\n"))
    assert(!trace.exists(_.contains("InternalR2CExpander")), trace.mkString("\n"))
  }

  test("R2 rejects mixed structured R2 then direct R1 authoring") {
    val (outcome, trace) =
      compileSnippetWithTrace(
        source("""@r2A
                 |class R2MixedR2ThenR1
                 |""".stripMargin),
        withTrace = true
      )
    assertRejected(
      outcome,
      "stage=structured-r2-validation",
      "category=MIXED_INTERNAL_R1_R2_AUTHORING"
    )
    assert(!trace.exists(_.contains("InternalR2BExpander")), trace.mkString("\n"))
    assert(!trace.exists(_.contains("InternalR2CExpander")), trace.mkString("\n"))
  }

  test("R2 executes a finite A to B to C directive chain") {
    assertSucceeded(
      compileSnippet(
        source(
          """@r2A
            |class R2FiniteABC
            |
            |object R2FiniteABCWitness:
            |  val a: String = new R2FiniteABC().r2AValue
            |  val b: String = new R2FiniteABC().r2BValue
            |  val c: String = new R2FiniteABC().r2CValue
            |""".stripMargin
        )
      )
    )
  }

  test("R2 preserves source work before the generated R1 tail") {
    val (outcome, trace) =
      compileSnippetWithTrace(
        source(
          """@r2A
            |@r2C
            |class R2SourceOrder
            |
            |object R2SourceOrderWitness:
            |  val a: String = new R2SourceOrder().r2AValue
            |  val b: String = new R2SourceOrder().r2BValue
            |  val c: String = new R2SourceOrder().r2CValue
            |""".stripMargin
        ),
        withTrace = true
      )
    assertSucceeded(outcome)
    assertEquals(
      trace.map(_.split(" ").head.stripPrefix("handler=")),
      List(
        "demo.InternalR2AExpander",
        "demo.InternalR2CExpander",
        "demo.InternalR2BExpander"
      )
    )
  }

  test("R2 preserves directive FIFO when one result emits B then C") {
    val (outcome, trace) =
      compileSnippetWithTrace(
        source("""@r2A
                 |class R2MultiFifo
                 |""".stripMargin),
        withTrace = true
      )
    assertSucceeded(outcome)
    assertEquals(
      trace.map(_.split(" ").head.stripPrefix("handler=")),
      List(
        "demo.InternalR2AExpander",
        "demo.InternalR2BExpander",
        "demo.InternalR2CExpander"
      )
    )
  }

  test("R2 appends directives emitted by generated work to the same FIFO") {
    val (outcome, trace) =
      compileSnippetWithTrace(
        source("""@r2A
                 |class R2ChainedFifo
                 |""".stripMargin),
        withTrace = true
      )
    assertSucceeded(outcome)
    assertEquals(
      trace.map(_.split(" ").head.stripPrefix("handler=")),
      List(
        "demo.InternalR2AExpander",
        "demo.InternalR2BExpander",
        "demo.InternalR2CExpander"
      )
    )
  }

  test("R2 forwards raw type positional and named argument trees unchanged") {
    assertSucceeded(
      compileSnippet(
        source(
          """@r2A
            |class R2Arguments
            |
            |object R2ArgumentsWitness:
            |  val observed: String = new R2Arguments().r2ArgumentsObserved
            |""".stripMargin
        )
      )
    )
  }

  test("R2 permits changing generated state to terminate through R1") {
    assertSucceeded(
      compileSnippet(
        source(
          """@r2ChangingSeed
            |class R2ChangingRepeat
            |
            |object R2ChangingRepeatWitness:
            |  val zero: String = new R2ChangingRepeat().r2Changed0
            |  val one: String = new R2ChangingRepeat().r2Changed1
            |  val two: String = new R2ChangingRepeat().r2Changed2
            |""".stripMargin
        )
      )
    )
  }

  test("R2 threads the latest companion through generated R1 work") {
    assertSucceeded(
      compileSnippet(
        source(
          """@r2A
            |class R2Companion
            |
            |object R2Companion:
            |  val preserved: Int = 42
            |
            |object R2CompanionWitness:
            |  val preserved: Int = R2Companion.preserved
            |  val a: String = R2Companion.r2ACompanionValue
            |  val b: String = R2Companion.r2BCompanionValue
            |""".stripMargin
        )
      )
    )
  }

  test("R2 preserves ordered additional output across generated work") {
    assertSucceeded(
      compileSnippet(
        source(
          """@r2A
            |class R2Additional
            |
            |object R2AdditionalWitness:
            |  val a = new R2AdditionalR2AExtra()
            |  val b = new R2AdditionalR2BExtra()
            |""".stripMargin
        )
      )
    )
  }

  test("R2 inherits R1 no-progress rejection") {
    assertRejected(
      compileSnippet(source("""@r2Self
                              |class R2SelfCycle
                              |""".stripMargin)),
      "category=FURTHER_EXPANSION_NO_PROGRESS",
      "handler=@r2Self",
      "generated/delegated",
      "trace="
    )
  }

  test("R2 inherits R1 repeated effective-state rejection") {
    assertRejected(
      compileSnippet(source("""@r2MutualSeed
                              |class R2MutualCycle
                              |""".stripMargin)),
      "category=FURTHER_EXPANSION_REPEATED_STATE",
      "handler=@r2MutualA",
      "trace="
    )
  }

  test("R2 inherits the R1 hard step budget") {
    assertRejected(
      compileSnippet(source("""@r2BudgetSeed
                              |class R2BudgetChain
                              |""".stripMargin)),
      "category=FURTHER_EXPANSION_STEP_BUDGET",
      "budget=32",
      "lastRequest=@r2Budget",
      "trace="
    )
  }

  test("R2 delegates unknown handler rejection to the R1 registry") {
    val (outcome, trace) =
      compileSnippetWithTrace(
        source("""@r2A
                 |class R2Unknown
                 |""".stripMargin),
        withTrace = true
      )
    assertRejected(
      outcome,
      "category=FURTHER_EXPANSION_UNKNOWN_HANDLER",
      "handler=@r2Unavailable",
      "requestedBy=demo.InternalR2AExpander"
    )
    assert(!trace.exists(_.contains("r2Unavailable")), trace.mkString("\n"))
  }

  test("R2 delegates target admission to the requested R1 participant") {
    val (outcome, trace) =
      compileSnippetWithTrace(
        source("""@r2A
                 |class R2Excluded
                 |""".stripMargin),
        withTrace = true
      )
    assertRejected(outcome, "@r2Restricted", "one top-level non-sealed ordinary trait")
    assert(
      !trace.exists(_.contains("InternalR2RestrictedExpander")),
      trace.mkString("\n")
    )
  }

  test("R2 cannot bypass StandaloneOnly on the requested handler") {
    val (outcome, trace) =
      compileSnippetWithTrace(
        source("""@r2A
                 |class R2StandaloneRequested
                 |""".stripMargin),
        withTrace = true
      )
    assertRejected(
      outcome,
      "category=FURTHER_EXPANSION_STANDALONE_HANDLER",
      "handler=@r2Standalone"
    )
    assert(
      !trace.exists(_.contains("InternalR2StandaloneExpander")),
      trace.mkString("\n")
    )
  }

  test("R2 cannot be emitted by a StandaloneOnly generator") {
    val (outcome, trace) =
      compileSnippetWithTrace(
        source("""@r2StandaloneGenerator
                 |class R2StandaloneGeneratorUser
                 |""".stripMargin),
        withTrace = true
      )
    assertRejected(
      outcome,
      "category=FURTHER_EXPANSION_STANDALONE_GENERATOR",
      "handler=@r2StandaloneGenerator",
      "requestedBy=demo.InternalR2StandaloneGeneratorExpander"
    )
    assert(!trace.exists(_.contains("InternalR2BExpander")), trace.mkString("\n"))
  }

  test("ordinary fresh handled output remains rejected without an R2 directive") {
    assertRejected(
      compileSnippet(source("""@r2A
                              |class R2FreshHandled
                              |""".stripMargin)),
      "reason=NEW_UNEXPECTED_HANDLED_ANNOTATION",
      "new unexpected handled annotation @r2B",
      "generated/delegated",
      "trace="
    )
  }

  test("R2 late generated failure rolls back primary companion and additions") {
    val (outcome, trace) =
      compileSnippetWithTrace(
        source(
          """@r2A
            |class R2LateFailure
            |
            |object R2LateFailure:
            |  val preserved: Int = 42
            |""".stripMargin
        ),
        withTrace = true
      )
    assertRejected(
      outcome,
      "category=NONFATAL_EXCEPTION",
      "handler=demo.InternalR2LateFailureExpander",
      "intentional generated R2 late failure",
      "trace="
    )
    assert(
      trace.exists(_.contains("InternalR2LateFailureExpander")),
      trace.mkString("\n")
    )
  }

  test("R2 delegates malformed generated output to existing output validation") {
    assertRejected(
      compileSnippet(source("""@r2A
                              |class R2MalformedOutput
                              |""".stripMargin)),
      "category=RAW_OUTPUT_INVARIANT",
      "handler=demo.InternalR2MalformedExpander",
      "invariant A (non-empty output)",
      "trace="
    )
  }
