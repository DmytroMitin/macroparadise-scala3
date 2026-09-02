import dotty.tools.dotc.Main
import dotty.tools.dotc.interfaces.{Diagnostic, SimpleReporter}

import java.io.File
import java.nio.file.Files

class FurtherExpansionSpec extends munit.FunSuite:
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

  private val handlerClasses =
    List(
      "demo.InternalR1AExpander",
      "demo.InternalR1BExpander",
      "demo.InternalR1CExpander",
      "demo.InternalR1SelfExpander",
      "demo.InternalR1MutualSeedExpander",
      "demo.InternalR1MutualAExpander",
      "demo.InternalR1MutualBExpander",
      "demo.InternalR1ChangingSeedExpander",
      "demo.InternalR1ChangingExpander",
      "demo.InternalR1BudgetSeedExpander",
      "demo.InternalR1BudgetExpander",
      "demo.InternalR1LateFailureExpander",
      "demo.InternalR1MalformedExpander",
      "demo.InternalR1RestrictedExpander",
      "demo.InternalR1StandaloneExpander",
      "demo.InternalR1StandaloneGeneratorExpander",
      "demo.InternalR1FreshHandledExpander"
    )

  private val handlerOptions =
    Seq(s"-P:macroparadise:handlerClasspath=$handlerJar") ++
      handlerClasses.map(name => s"-P:macroparadise:handler=$name")

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

  private def compileSnippet(
      source: String,
      withTrace: Boolean = false
  ): (CompileOutcome, List[String]) =
    val tempDir = Files.createTempDirectory("macroparadise-r1")
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
          ) ++ (handlerOptions ++ traceOptions).toArray ++ Array(
            sourceFile.toString
          ),
          reporter,
          null
        )
      val outputFiles = regularFiles(outDir)
      val outcome =
        if result.hasErrors() then
          CompileOutcome.ReportedErrors(reporter.messages.toList, outputFiles)
        else CompileOutcome.Succeeded(outputFiles)
      (outcome, traceLines(traceFile))
    catch
      case throwable: Throwable =>
        (
          CompileOutcome.Threw(throwable, regularFiles(outDir)),
          traceLines(traceFile)
        )

  private def traceLines(path: java.nio.file.Path): List[String] =
    if !Files.isRegularFile(path) || Files.size(path) == 0 then Nil
    else Files.readAllLines(path).toArray.toList.map(_.toString)

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

  private def assertSucceeded(outcome: CompileOutcome): Unit =
    outcome match
      case CompileOutcome.Succeeded(outputFiles) =>
        assert(outputFiles.nonEmpty, "successful compilation emitted no output")
      case CompileOutcome.ReportedErrors(messages, outputFiles) =>
        fail(
          s"expected success, got diagnostics=${messages.mkString(" | ")} outputs=${outputFiles.mkString(",")}"
        )
      case CompileOutcome.Threw(throwable, outputFiles) =>
        fail(
          s"expected success, got ${throwable.getClass.getName}: ${throwable.getMessage}; outputs=${outputFiles.mkString(",")}"
        )

  private def assertRejected(
      outcome: CompileOutcome,
      expectedFragments: String*
  ): Unit =
    outcome match
      case CompileOutcome.ReportedErrors(messages, outputFiles) =>
        val diagnostic = messages.mkString("\n")
        expectedFragments.foreach(fragment => assert(diagnostic.contains(fragment), diagnostic))
        assertEquals(outputFiles, Nil, s"partial class/Tasty output: ${outputFiles.mkString(",")}")
        assert(!diagnostic.contains("internal compiler error"), diagnostic)
      case CompileOutcome.Threw(throwable, outputFiles) =>
        fail(
          s"expected controlled rejection, got ${throwable.getClass.getName}: ${throwable.getMessage}; outputs=${outputFiles.mkString(",")}"
        )
      case other => fail(s"expected controlled rejection, got $other")

  private def source(body: String): String =
    s"""package internalr1
       |
       |import scala.annotation.StaticAnnotation
       |
       |final class r1A extends StaticAnnotation
       |final class r1B extends StaticAnnotation
       |final class r1C extends StaticAnnotation
       |final class r1Self extends StaticAnnotation
       |final class r1MutualSeed extends StaticAnnotation
       |final class r1ChangingSeed extends StaticAnnotation
       |final class r1BudgetSeed extends StaticAnnotation
       |final class r1StandaloneGenerator extends StaticAnnotation
       |
       |$body
       |""".stripMargin

  test("R1 executes a finite same-target A to B chain") {
    val (outcome, _) =
      compileSnippet(
        source(
          """@r1A
            |class R1FiniteAB
            |
            |object R1FiniteABWitness:
            |  val a: String = new R1FiniteAB().r1AValue
            |  val b: String = new R1FiniteAB().r1BValue
            |""".stripMargin
        )
      )
    assertSucceeded(outcome)
  }

  test("R1 executes a finite same-target A to B to C chain") {
    val (outcome, _) =
      compileSnippet(
        source(
          """@r1A
            |class R1FiniteABC
            |
            |object R1FiniteABCWitness:
            |  val a: String = new R1FiniteABC().r1AValue
            |  val b: String = new R1FiniteABC().r1BValue
            |  val c: String = new R1FiniteABC().r1CValue
            |""".stripMargin
        )
      )
    assertSucceeded(outcome)
  }

  test("R1 preserves source order before generated FIFO") {
    val (outcome, trace) =
      compileSnippet(
        source(
          """@r1A
            |@r1C
            |class R1SourceOrder
            |
            |object R1SourceOrderWitness:
            |  val a: String = new R1SourceOrder().r1AValue
            |  val b: String = new R1SourceOrder().r1BValue
            |  val c: String = new R1SourceOrder().r1CValue
            |""".stripMargin
        ),
        withTrace = true
      )
    assertSucceeded(outcome)
    assertEquals(
      trace.map(_.split(" ").head.stripPrefix("handler=")),
      List(
        "demo.InternalR1AExpander",
        "demo.InternalR1CExpander",
        "demo.InternalR1BExpander"
      )
    )
  }

  test("R1 preserves FIFO when one step requests B then C") {
    val (outcome, trace) =
      compileSnippet(source("""@r1A
                              |class R1MultiFifo
                              |""".stripMargin), withTrace = true)
    assertSucceeded(outcome)
    assertEquals(
      trace.map(_.split(" ").head.stripPrefix("handler=")),
      List(
        "demo.InternalR1AExpander",
        "demo.InternalR1BExpander",
        "demo.InternalR1CExpander"
      )
    )
  }

  test("R1 forwards positional named and type argument syntax unchanged") {
    val (outcome, _) =
      compileSnippet(
        source(
          """@r1A
            |class R1Arguments
            |
            |object R1ArgumentsWitness:
            |  val observed: String = new R1Arguments().r1ArgumentsObserved
            |""".stripMargin
        )
      )
    assertSucceeded(outcome)
  }

  test("R1 allows a repeated handler when request and target state change") {
    val (outcome, _) =
      compileSnippet(
        source(
          """@r1ChangingSeed
            |class R1ChangingRepeat
            |
            |object R1ChangingRepeatWitness:
            |  val zero: String = new R1ChangingRepeat().r1Changed0
            |  val one: String = new R1ChangingRepeat().r1Changed1
            |  val two: String = new R1ChangingRepeat().r1Changed2
            |""".stripMargin
        )
      )
    assertSucceeded(outcome)
  }

  test("R1 threads the latest companion through generated work") {
    val (outcome, _) =
      compileSnippet(
        source(
          """@r1A
            |class R1Companion
            |
            |object R1Companion:
            |  val preserved: Int = 42
            |
            |object R1CompanionWitness:
            |  val preserved: Int = R1Companion.preserved
            |  val a: String = R1Companion.r1ACompanionValue
            |  val b: String = R1Companion.r1BCompanionValue
            |""".stripMargin
        )
      )
    assertSucceeded(outcome)
  }

  test("R1 keeps ordered additions from source and generated steps") {
    val (outcome, _) =
      compileSnippet(
        source(
          """@r1A
            |class R1Additional
            |
            |object R1AdditionalWitness:
            |  val a = new R1AdditionalR1AExtra()
            |  val b = new R1AdditionalR1BExtra()
            |""".stripMargin
        )
      )
    assertSucceeded(outcome)
  }

  test("R1 rejects a generated self request with no effective progress") {
    val (outcome, _) =
      compileSnippet(source("""@r1Self
                              |class R1SelfCycle
                              |""".stripMargin))
    assertRejected(
      outcome,
      "category=FURTHER_EXPANSION_NO_PROGRESS",
      "handler=@r1Self",
      "generated/delegated",
      "trace="
    )
  }

  test("R1 rejects a mutual exact repeated effective state") {
    val (outcome, _) =
      compileSnippet(source("""@r1MutualSeed
                              |class R1MutualCycle
                              |""".stripMargin))
    assertRejected(
      outcome,
      "category=FURTHER_EXPANSION_REPEATED_STATE",
      "handler=@r1MutualA",
      "trace="
    )
  }

  test("R1 stops a changing-state chain at the private hard budget") {
    val (outcome, _) =
      compileSnippet(source("""@r1BudgetSeed
                              |class R1BudgetChain
                              |""".stripMargin))
    assertRejected(
      outcome,
      "category=FURTHER_EXPANSION_STEP_BUDGET",
      "budget=32",
      "lastRequest=@r1Budget",
      "trace="
    )
  }

  test("R1 rejects an unknown requested handler before invocation") {
    val (outcome, trace) =
      compileSnippet(source("""@r1A
                              |class R1Unknown
                              |""".stripMargin), withTrace = true)
    assertRejected(
      outcome,
      "category=FURTHER_EXPANSION_UNKNOWN_HANDLER",
      "handler=@r1Unavailable",
      "requestedBy=demo.InternalR1AExpander"
    )
    assert(!trace.exists(_.contains("r1Unavailable")), trace.mkString("\n"))
  }

  test("R1 applies requested target admission before invocation") {
    val (outcome, trace) =
      compileSnippet(source("""@r1A
                              |class R1Excluded
                              |""".stripMargin), withTrace = true)
    assertRejected(
      outcome,
      "@r1Restricted",
      "one top-level non-sealed ordinary trait"
    )
    assert(!trace.exists(_.contains("InternalR1RestrictedExpander")), trace.mkString("\n"))
  }

  test("R1 does not bypass StandaloneOnly on the requested handler") {
    val (outcome, trace) =
      compileSnippet(source("""@r1A
                              |class R1StandaloneRequested
                              |""".stripMargin), withTrace = true)
    assertRejected(
      outcome,
      "category=FURTHER_EXPANSION_STANDALONE_HANDLER",
      "handler=@r1Standalone"
    )
    assert(!trace.exists(_.contains("InternalR1StandaloneExpander")), trace.mkString("\n"))
  }

  test("R1 does not let a StandaloneOnly generator enqueue sequential work") {
    val (outcome, trace) =
      compileSnippet(source("""@r1StandaloneGenerator
                              |class R1StandaloneGeneratorUser
                              |""".stripMargin), withTrace = true)
    assertRejected(
      outcome,
      "category=FURTHER_EXPANSION_STANDALONE_GENERATOR",
      "handler=@r1StandaloneGenerator",
      "requestedBy=demo.InternalR1StandaloneGeneratorExpander"
    )
    assert(!trace.exists(_.contains("InternalR1BExpander")), trace.mkString("\n"))
  }

  test("R1 still rejects an ordinary fresh returned handled annotation") {
    val (outcome, _) =
      compileSnippet(source("""@r1A
                              |class R1FreshHandled
                              |""".stripMargin))
    assertRejected(
      outcome,
      "reason=NEW_UNEXPECTED_HANDLED_ANNOTATION",
      "new unexpected handled annotation @r1B",
      "generated/delegated",
      "trace="
    )
  }

  test("R1 late generated failure rolls back primary companion and additions") {
    val (outcome, trace) =
      compileSnippet(
        source(
          """@r1A
            |class R1LateFailure
            |
            |object R1LateFailure:
            |  val preserved: Int = 42
            |""".stripMargin
        ),
        withTrace = true
      )
    assertRejected(
      outcome,
      "category=NONFATAL_EXCEPTION",
      "handler=demo.InternalR1LateFailureExpander",
      "intentional generated R1 late failure",
      "trace="
    )
    assert(trace.exists(_.contains("InternalR1LateFailureExpander")), trace.mkString("\n"))
  }

  test("R1 malformed generated output uses existing invariant rejection and rollback") {
    val (outcome, _) =
      compileSnippet(source("""@r1A
                              |class R1Malformed
                              |""".stripMargin))
    assertRejected(
      outcome,
      "category=RAW_OUTPUT_INVARIANT",
      "handler=demo.InternalR1MalformedExpander",
      "invariant A (non-empty output)",
      "trace="
    )
  }
