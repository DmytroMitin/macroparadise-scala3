import dotty.tools.dotc.Main
import dotty.tools.dotc.interfaces.{Diagnostic, SimpleReporter}

import java.io.File
import java.nio.file.Files

class ConflictSpec extends munit.FunSuite:
  private val scalaVersion =
    sys.props.getOrElse(
      "macroparadise.testScalaVersion",
      "3.8.4"
    )
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

  private final class CollectingReporter extends SimpleReporter:
    val messages = scala.collection.mutable.ListBuffer.empty[String]

    override def report(diagnostic: Diagnostic): Unit =
      messages += diagnostic.message()

  private sealed trait CompileOutcome
  private object CompileOutcome:
    final case class ReportedErrors(messages: List[String], outputFiles: List[String]) extends CompileOutcome
    final case class Threw(throwable: Throwable, outputFiles: List[String]) extends CompileOutcome
    final case class Succeeded(outputFiles: List[String]) extends CompileOutcome

  private def compileSnippet(source: String, pluginOptions: Seq[String] = Nil): CompileOutcome =
    val tempDir = Files.createTempDirectory("macroparadise-conflicts")
    val sourceFile = tempDir.resolve("Snippet.scala")
    val outDir = tempDir.resolve("out")
    Files.createDirectories(outDir)
    Files.writeString(sourceFile, source)

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
          ) ++ pluginOptions.toArray ++ Array(
            sourceFile.toString
          ),
          reporter,
          null
        )

      val outputFiles = regularFiles(outDir)
      if result.hasErrors() then CompileOutcome.ReportedErrors(reporter.messages.toList, outputFiles)
      else CompileOutcome.Succeeded(outputFiles)
    catch
      case throwable: Throwable =>
        CompileOutcome.Threw(throwable, regularFiles(outDir))

  private def compileSnippetWithInvocationTrace(
      source: String,
      pluginOptions: Seq[String]
  ): (CompileOutcome, List[String]) =
    val trace = Files.createTempFile("macroparadise-composition-invocations", ".txt")
    val outcome =
      compileSnippet(
        source,
        pluginOptions :+ s"-P:macroparadise:externalHandlerInvocationTrace=$trace"
      )
    val lines =
      if Files.size(trace) == 0 then Nil
      else Files.readAllLines(trace).toArray.toList.map(_.toString)
    (outcome, lines)

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
    finally
      paths.close()

  private def assertDiagnostic(
      outcome: CompileOutcome,
      expectedFragments: String*
  ): Unit =
    outcome match
      case CompileOutcome.ReportedErrors(messages, _) =>
        val diagnostic = messages.mkString("\n")
        expectedFragments.foreach: fragment =>
          assert(diagnostic.contains(fragment), diagnostic)
      case CompileOutcome.Threw(throwable, _) =>
        fail(s"expected graceful diagnostic, got ${throwable.getClass.getName}: ${throwable.getMessage}")
      case other =>
        fail(s"expected graceful diagnostic, got $other")

  private def assertBoundaryDiagnostic(
      outcome: CompileOutcome,
      expectedStage: String,
      expectedFragments: String*
  ): Unit =
    outcome match
      case CompileOutcome.ReportedErrors(messages, outputFiles) =>
        val diagnostic = messages.mkString("\n")
        assert(diagnostic.contains(s"stage=$expectedStage"), diagnostic)
        expectedFragments.foreach: fragment =>
          assert(diagnostic.contains(fragment), diagnostic)
        assertEquals(outputFiles, Nil, s"unexpected partial class/Tasty output: ${outputFiles.mkString(", ")}")
        assert(!diagnostic.contains("internal compiler error"), diagnostic)
      case CompileOutcome.Threw(throwable, outputFiles) =>
        fail(
          s"expected controlled $expectedStage diagnostic, got ${throwable.getClass.getName}: " +
            s"${throwable.getMessage}; outputs=${outputFiles.mkString(", ")}"
        )
      case other =>
        fail(s"expected controlled $expectedStage diagnostic, got $other")

  private def assertRawValidationDiagnostic(
      outcome: CompileOutcome,
      expectedFragments: String*
  ): Unit =
    outcome match
      case CompileOutcome.ReportedErrors(messages, outputFiles) =>
        val diagnostic = messages.mkString("\n")
        expectedFragments.foreach: fragment =>
          assert(diagnostic.contains(fragment), diagnostic)
        assert(diagnostic.contains("stage=output-validation"), diagnostic)
        assertEquals(
          messages.count(_.contains("invalid raw expansion output")),
          1,
          diagnostic
        )
        assert(!diagnostic.contains("already defined"), diagnostic)
        assert(!diagnostic.contains("Not found"), diagnostic)
        assertEquals(outputFiles, Nil, s"unexpected partial class/Tasty output: ${outputFiles.mkString(", ")}")
      case CompileOutcome.Threw(throwable, _) =>
        fail(
          s"expected bounded validation diagnostic, got ${throwable.getClass.getName}: ${throwable.getMessage}\n" +
            throwable.getStackTrace.take(12).mkString("\n")
        )
      case other =>
        fail(s"expected bounded validation diagnostic, got $other")

  private def assertSingleDiagnostic(
      outcome: CompileOutcome,
      expectedFragments: String*
  ): Unit =
    outcome match
      case CompileOutcome.ReportedErrors(messages, _) =>
        val diagnostic = messages.mkString("\n")
        assertEquals(
          messages.count(_.contains("@externalTypedLabel")),
          1,
          diagnostic
        )
        expectedFragments.foreach: fragment =>
          assert(diagnostic.contains(fragment), diagnostic)
        assert(!diagnostic.contains("internal compiler error"), diagnostic)
      case CompileOutcome.Threw(throwable, _) =>
        fail(
          s"expected one focused diagnostic, got ${throwable.getClass.getName}: ${throwable.getMessage}\n" +
            throwable.getStackTrace.take(12).mkString("\n")
        )
      case other =>
        fail(s"expected one focused diagnostic, got $other")

  private def assertFocusedAdmissionDiagnostic(
      outcome: CompileOutcome,
      expectedFragments: String*
  ): Unit =
    outcome match
      case CompileOutcome.ReportedErrors(messages, _) =>
        val diagnostic = messages.mkString("\n")
        val focused =
          messages.filter: message =>
            message.contains("unsupported constructor shape") ||
              message.contains("unsupported class family") ||
              message.contains("unsupported generic class shape") ||
              message.contains("currently supports only top-level classes") ||
              message.contains("currently support only top-level classes")
        assertEquals(focused.size, 1, diagnostic)
        expectedFragments.foreach: fragment =>
          assert(diagnostic.contains(fragment), diagnostic)
        assert(!diagnostic.contains("internal compiler error"), diagnostic)
        assert(!diagnostic.contains("already defined"), diagnostic)
        assert(!diagnostic.contains("Not found: type gen"), diagnostic)
        assert(!diagnostic.contains("Not found: type debug"), diagnostic)
        assert(!diagnostic.contains("Not found: type external"), diagnostic)
      case CompileOutcome.Threw(throwable, _) =>
        fail(
          s"expected one pre-expansion diagnostic, got ${throwable.getClass.getName}: ${throwable.getMessage}\n" +
            throwable.getStackTrace.take(12).mkString("\n")
        )
      case other =>
        fail(s"expected one pre-expansion diagnostic, got $other")

  private def assertInvocationProtocolDiagnostic(
      outcome: CompileOutcome,
      expectedCategory: String,
      expectedFragments: String*
  ): Unit =
    outcome match
      case CompileOutcome.ReportedErrors(messages, outputFiles) =>
        val diagnostic = messages.mkString("\n")
        assertEquals(
          messages.count(_.contains(s"category=$expectedCategory")),
          1,
          diagnostic
        )
        expectedFragments.foreach: fragment =>
          assert(diagnostic.contains(fragment), diagnostic)
        val expectedStage =
          if expectedCategory == "NONFATAL_EXCEPTION" || expectedCategory == "LINKAGE_ERROR" then
            "invocation"
          else "output-validation"
        assert(diagnostic.contains(s"stage=$expectedStage"), diagnostic)
        assertEquals(outputFiles, Nil, s"unexpected partial class/Tasty output: ${outputFiles.mkString(", ")}")
        assert(!diagnostic.contains("internal compiler error"), diagnostic)
        assert(!diagnostic.contains("exception occurred while typechecking"), diagnostic)
        assert(!diagnostic.contains("already defined"), diagnostic)
        assert(!diagnostic.contains("Not found"), diagnostic)
      case CompileOutcome.Threw(throwable, outputFiles) =>
        fail(
          s"expected controlled protocol diagnostic, got ${throwable.getClass.getName}: ${throwable.getMessage}; " +
            s"outputs=${outputFiles.mkString(", ")}\n" +
            throwable.getStackTrace.take(12).mkString("\n")
        )
      case other =>
        fail(s"expected controlled protocol diagnostic, got $other")

  private def assertControlledHandlerRejection(
      outcome: CompileOutcome,
      expectedFragments: String*
  ): Unit =
    outcome match
      case CompileOutcome.ReportedErrors(messages, outputFiles) =>
        val diagnostic = messages.mkString("\n")
        expectedFragments.foreach: fragment =>
          assert(diagnostic.contains(fragment), diagnostic)
        assertEquals(outputFiles, Nil, s"unexpected partial class/Tasty output: ${outputFiles.mkString(", ")}")
        assert(!diagnostic.contains("internal compiler error"), diagnostic)
        assert(!diagnostic.contains("exception occurred while typechecking"), diagnostic)
        assert(!diagnostic.contains("already defined"), diagnostic)
        assert(!diagnostic.contains("Not found"), diagnostic)
      case CompileOutcome.Threw(throwable, outputFiles) =>
        fail(
          s"expected controlled handler rejection, got ${throwable.getClass.getName}: ${throwable.getMessage}; " +
            s"outputs=${outputFiles.mkString(", ")}"
        )
      case other =>
        fail(s"expected controlled handler rejection, got $other")

  private def protocolOptions(handlerClassName: String): Seq[String] =
    Seq(
      s"-P:macroparadise:handlerClasspath=$handlerJar",
      s"-P:macroparadise:handler=$handlerClassName"
    )

  private def standaloneProtocolSource(annotationName: String, className: String): String =
    s"""package invocationprotocol
       |
       |import paradise3.$annotationName
       |
       |@$annotationName
       |class $className
       |""".stripMargin

  private def companionProtocolSource(annotationName: String, className: String): String =
    s"""package invocationprotocol
       |
       |import paradise3.$annotationName
       |
       |@$annotationName
       |class $className
       |
       |object $className:
       |  val preserved: Int = 42
       |
       |object ${className}Witness:
       |  val preserved: Int = $className.preserved
       |""".stripMargin

  test("existing sibling reports a graceful diagnostic") {
    val outcome =
      compileSnippet(
        """package conflicts
          |
          |import paradise3.gen
          |
          |class UserMeta
          |
          |@gen
          |class User(val name: String)
          |""".stripMargin
      )

    assertDiagnostic(
      outcome,
      "generated sibling `UserMeta` already exists",
      "@gen cannot generate sibling for `User`",
      "unsupported"
    )
  }

  test("external sibling top-level conflict rejects before partial expansion") {
    val outcome =
      compileSnippet(
        """package conflicts
          |
          |import paradise3.{externalDebug, externalSiblingDebug}
          |
          |@externalDebug
          |@externalSiblingDebug
          |class ExternalSiblingConflict
          |
          |class ExternalSiblingConflictExternalMeta
          |""".stripMargin,
        pluginOptions = Seq(
          s"-P:macroparadise:handlerClasspath=$handlerJar"
        )
      )

    assertControlledHandlerRejection(
      outcome,
      "generated sibling `ExternalSiblingConflictExternalMeta` already exists",
      "@externalSiblingDebug cannot generate sibling for `ExternalSiblingConflict`",
      "top-level conflict",
      "unsupported"
    )
  }

  test("external sibling accumulated additional-name conflict rejects the whole composition") {
    val outcome =
      compileSnippet(
        """package conflicts
          |
          |import paradise3.externalSiblingDebug
          |import scala.annotation.StaticAnnotation
          |
          |final class compositionDuplicatesKnownAdditional extends StaticAnnotation
          |
          |@externalSiblingDebug
          |@compositionDuplicatesKnownAdditional
          |class ExternalSiblingAccumulatedConflict
          |""".stripMargin,
        pluginOptions = Seq(
          s"-P:macroparadise:handlerClasspath=$handlerJar",
          "-P:macroparadise:handler=demo.CompositionDuplicatesKnownAdditionalExpander"
        )
      )

    assertRawValidationDiagnostic(
      outcome,
      "external handler `demo.CompositionDuplicatesKnownAdditionalExpander`",
      "@compositionDuplicatesKnownAdditional",
      "primary `ExternalSiblingAccumulatedConflict`",
      "invariant G (no known top-level conflict) failed",
      "additional output name `ExternalSiblingAccumulatedConflictExternalMeta`"
    )
  }

  test("external sibling conflict after companion merge restores every selected output lane") {
    val outcome =
      compileSnippet(
        """package conflicts
          |
          |import paradise3.{externalCompanionDebug, externalSiblingDebug}
          |
          |class CompanionSiblingKnownConflictExternalMeta
          |
          |@externalCompanionDebug
          |@externalSiblingDebug
          |class CompanionSiblingKnownConflict
          |
          |object CompanionSiblingKnownConflict:
          |  val preserved: Int = 42
          |
          |object CompanionSiblingKnownConflictWitness:
          |  val preserved: Int = CompanionSiblingKnownConflict.preserved
          |""".stripMargin,
        pluginOptions = Seq(
          s"-P:macroparadise:handlerClasspath=$handlerJar"
        )
      )

    assertControlledHandlerRejection(
      outcome,
      "generated sibling `CompanionSiblingKnownConflictExternalMeta` already exists",
      "@externalSiblingDebug cannot generate sibling for `CompanionSiblingKnownConflict`",
      "top-level conflict",
      "unsupported"
    )
  }

  test("known additional-name conflict after companion and sibling participation rejects transactionally") {
    val outcome =
      compileSnippet(
        """package conflicts
          |
          |import paradise3.{externalCompanionDebug, externalSiblingDebug}
          |import scala.annotation.StaticAnnotation
          |
          |final class compositionDuplicatesKnownAdditional extends StaticAnnotation
          |
          |@externalCompanionDebug
          |@externalSiblingDebug
          |@compositionDuplicatesKnownAdditional
          |class CompanionSiblingAccumulatedConflict
          |
          |object CompanionSiblingAccumulatedConflict:
          |  val preserved: Int = 84
          |
          |object CompanionSiblingAccumulatedConflictWitness:
          |  val preserved: Int = CompanionSiblingAccumulatedConflict.preserved
          |""".stripMargin,
        pluginOptions = Seq(
          s"-P:macroparadise:handlerClasspath=$handlerJar",
          "-P:macroparadise:handler=demo.CompositionDuplicatesKnownAdditionalExpander"
        )
      )

    assertRawValidationDiagnostic(
      outcome,
      "external handler `demo.CompositionDuplicatesKnownAdditionalExpander`",
      "@compositionDuplicatesKnownAdditional",
      "primary `CompanionSiblingAccumulatedConflict`",
      "invariant G (no known top-level conflict) failed",
      "additional output name `CompanionSiblingAccumulatedConflictExternalMeta`"
    )
  }

  test("late controlled failure rolls back companion merge and external sibling output") {
    val outcome =
      compileSnippet(
        """package invocationprotocol
          |
          |import paradise3.{externalCompanionDebug, externalSiblingDebug}
          |import scala.annotation.StaticAnnotation
          |
          |final class compositionFailsAfterCompanionAndSibling extends StaticAnnotation
          |
          |@externalCompanionDebug
          |@externalSiblingDebug
          |@compositionFailsAfterCompanionAndSibling
          |class CompanionSiblingLateFailureUser
          |
          |object CompanionSiblingLateFailureUser:
          |  val preserved: Int = 126
          |
          |object CompanionSiblingLateFailureWitness:
          |  val preserved: Int = CompanionSiblingLateFailureUser.preserved
          |""".stripMargin,
        pluginOptions = Seq(
          s"-P:macroparadise:handlerClasspath=$handlerJar",
          "-P:macroparadise:handler=demo.CompositionFailsAfterCompanionAndSiblingExpander"
        )
      )

    assertInvocationProtocolDiagnostic(
      outcome,
      "NONFATAL_EXCEPTION",
      "annotation=@compositionFailsAfterCompanionAndSibling",
      "handler=demo.CompositionFailsAfterCompanionAndSiblingExpander",
      "class=CompanionSiblingLateFailureUser",
      "cause=java.lang.IllegalStateException",
      "message=late companion and sibling composition fixture failure"
    )
  }

  test("annotated top-level object reports a graceful diagnostic") {
    val outcome =
      compileSnippet(
        """package conflicts
          |
          |import paradise3.gen
          |
          |@gen
          |object User
          |""".stripMargin
      )

    assertDiagnostic(
      outcome,
      "@gen currently supports only top-level classes",
      "unsupported target `object User`"
    )
  }

  test("debug annotated top-level object reports a graceful diagnostic") {
    val outcome =
      compileSnippet(
        """package conflicts
          |
          |import paradise3.debug
          |
          |@debug
          |object User
          |""".stripMargin
      )

    assertDiagnostic(
      outcome,
      "@debug currently supports only top-level classes",
      "unsupported target `object User`"
    )
  }

  test("external companion annotation on a top-level object reports a graceful diagnostic") {
    val (outcome, invocations) =
      compileSnippetWithInvocationTrace(
        """package conflicts
          |
          |import paradise3.externalCompanionDebug
          |
          |@externalCompanionDebug
          |object User
          |""".stripMargin,
        pluginOptions = Seq(
          s"-P:macroparadise:handlerClasspath=$handlerJar"
        )
      )

    assertDiagnostic(
      outcome,
      "@externalCompanionDebug currently supports only top-level classes",
      "unsupported target `object User`"
    )
    assertEquals(invocations, Nil)
  }

  test("annotated top-level trait reports a graceful diagnostic") {
    val outcome =
      compileSnippet(
        """package conflicts
          |
          |import paradise3.gen
          |
          |@gen
          |trait User
          |""".stripMargin
      )

    assertDiagnostic(
      outcome,
      "@gen currently supports only top-level classes",
      "unsupported target `trait User`"
    )
  }

  test("nested annotated classes report a graceful diagnostic") {
    val outcome =
      compileSnippet(
        """package conflicts
          |
          |import paradise3.gen
          |
          |object Outer {
          |  @gen
          |  class User(val name: String)
          |}
          |""".stripMargin
      )

    assertDiagnostic(
      outcome,
      "@gen currently supports only top-level classes",
      "unsupported target `nested class User`"
    )
  }

  test("unsupported built-in annotation composition reports a clear diagnostic") {
    val outcome =
      compileSnippet(
        """package conflicts
          |
          |import paradise3.gen
          |import paradise3.debug
          |
          |@gen
          |@debug
          |class User(val name: String)
          |""".stripMargin
      )

    assertDiagnostic(
      outcome,
      "composition admission failure",
      "category=STANDALONE_COMPOSITION_PARTICIPANT",
      "@gen, @debug (source order)",
      "@debug declares StandaloneOnly"
    )
  }

  test("missing explicit external handler reports a clear diagnostic") {
    val outcome =
      compileSnippet(
        """package conflicts
          |
          |class User
          |""".stripMargin,
        pluginOptions = Seq("-P:macroparadise:handler=missing.DoesNotExist")
      )

    assertBoundaryDiagnostic(
      outcome,
      "loading",
      "category=HANDLER_LOAD_FAILURE",
      "handlerClasspathConfigured=false",
      "handlerClasspathEntries=0",
      "missing -P:macroparadise:handlerClasspath=<handler-jar-or-path-list>",
      "ordinary source compilation classpath"
    )
  }

  test("missing metadata-discovered handler reports a clear diagnostic") {
    val outcome =
      compileSnippet(
        """package conflicts
          |
          |import paradise3.metadataMissing
          |
          |@metadataMissing
          |class MissingMetadataUser
          |""".stripMargin,
        pluginOptions = Seq(
          s"-P:macroparadise:handlerClasspath=$handlerJar"
        )
      )

    assertBoundaryDiagnostic(
      outcome,
      "loading",
      "category=HANDLER_LOAD_FAILURE",
      "handlerClasspathConfigured=true",
      "handlerClasspathEntries=1",
      "explicit external handler classpath cannot load selected handler `missing.DoesNotExist`"
    )
  }

  test("empty metadata-discovered handler reports a clear diagnostic") {
    val outcome =
      compileSnippet(
        """package conflicts
          |
          |import paradise3.metadataEmpty
          |
          |@metadataEmpty
          |class EmptyMetadataUser
          |""".stripMargin,
        pluginOptions = Seq(
          s"-P:macroparadise:handlerClasspath=$handlerJar"
        )
      )

    assertBoundaryDiagnostic(
      outcome,
      "discovery",
      "category=METADATA_DISCOVERY_FAILURE",
      "empty annotation metadata expander class name for `paradise3.metadataEmpty`"
    )
  }

  test("metadata discovery uses runtime reader and short-circuits TASTy readers during plugin compilation") {
    val traceFile = Files.createTempFile("macroparadise-metadata-reader", ".trace")
    val outcome =
      compileSnippet(
        """package conflicts
          |
          |import paradise3.metadataMissing
          |
          |@metadataMissing
          |class StructuredMetadataUser
          |""".stripMargin,
        pluginOptions = Seq(
          s"-P:macroparadise:handlerClasspath=$handlerJar",
          s"-P:macroparadise:metadataReaderTrace=$traceFile"
        )
      )

    assertBoundaryDiagnostic(
      outcome,
      "loading",
      "category=HANDLER_LOAD_FAILURE",
      "handlerClasspathConfigured=true",
      "handlerClasspathEntries=1",
      "explicit external handler classpath cannot load selected handler `missing.DoesNotExist`"
    )
    val trace = Files.readString(traceFile)
    assert(
      trace.contains("runtime paradise3.metadataMissing Found(missing.DoesNotExist)"),
      trace
    )
    assert(!trace.contains("structured paradise3.metadataMissing "), trace)
    assert(!trace.contains("string paradise3.metadataMissing "), trace)
  }

  test("non-expander explicit handler reports a clear diagnostic") {
    val outcome =
      compileSnippet(
        """package conflicts
          |
          |class User
          |""".stripMargin,
        pluginOptions = Seq(
          s"-P:macroparadise:handlerClasspath=$handlerJar",
          "-P:macroparadise:handler=demo.NotAnExpander"
        )
      )

    assertBoundaryDiagnostic(
      outcome,
      "loading",
      "category=HANDLER_TYPE_MISMATCH",
      "external annotation handler `demo.NotAnExpander` does not implement paradise3.api.ParadiseAnnotationExpander"
    )
  }

  test("throwing explicit handler reports a clear diagnostic") {
    val outcome =
      compileSnippet(
        """package conflicts
          |
          |class User
          |""".stripMargin,
        pluginOptions = Seq(
          s"-P:macroparadise:handlerClasspath=$handlerJar",
          "-P:macroparadise:handler=demo.ThrowingExpander"
        )
      )

    assertBoundaryDiagnostic(
      outcome,
      "loading",
      "category=CONSTRUCTOR_FAILURE",
      "external annotation handler `demo.ThrowingExpander` failed to instantiate",
      "boom during handler construction"
    )
  }

  test("duplicate external handler annotation name reports a clear diagnostic") {
    val outcome =
      compileSnippet(
        """package conflicts
          |
          |class User
          |""".stripMargin,
        pluginOptions = Seq(
          s"-P:macroparadise:handlerClasspath=$handlerJar",
          "-P:macroparadise:handler=demo.ExternalDebugExpander",
          "-P:macroparadise:handler=demo.DuplicateExternalDebugExpander"
        )
      )

    assertBoundaryDiagnostic(
      outcome,
      "loading",
      "category=DUPLICATE_HANDLER_REGISTRATION",
      "duplicate annotation handler registration for `externalDebug`"
    )
  }

  test("the same explicit handler class repeated is rejected atomically") {
    val outcome =
      compileSnippet(
        """package conflicts
          |
          |class User
          |""".stripMargin,
        pluginOptions = Seq(
          s"-P:macroparadise:handlerClasspath=$handlerJar",
          "-P:macroparadise:handler=demo.ExternalDebugExpander",
          "-P:macroparadise:handler=demo.ExternalDebugExpander"
        )
      )

    assertBoundaryDiagnostic(
      outcome,
      "loading",
      "category=DUPLICATE_HANDLER_REGISTRATION",
      "annotation=@externalDebug",
      "handler=demo.ExternalDebugExpander",
      "duplicate annotation handler registration for `externalDebug`"
    )
  }

  test("external handler conflicting with built-in annotation reports a clear diagnostic") {
    val outcome =
      compileSnippet(
        """package conflicts
          |
          |class User
          |""".stripMargin,
        pluginOptions = Seq(
          s"-P:macroparadise:handlerClasspath=$handlerJar",
          "-P:macroparadise:handler=demo.GenNameExpander"
        )
      )

    assertBoundaryDiagnostic(
      outcome,
      "loading",
      "category=DUPLICATE_HANDLER_REGISTRATION",
      "duplicate annotation handler registration for `gen`"
    )
  }

  test("external typed label rejects a non-literal value without handler fallback output") {
    val outcome =
      compileSnippet(
        """package conflicts
          |
          |import paradise3.externalTypedLabel
          |
          |@externalTypedLabel[Int](identity("dynamic"))
          |class ExternalTypedNonLiteral
          |""".stripMargin,
        pluginOptions = Seq(
          s"-P:macroparadise:handlerClasspath=$handlerJar"
        )
      )

    assertSingleDiagnostic(
      outcome,
      "@externalTypedLabel",
      "parameter `value` (argument 0)",
      "requires a string literal",
      "found raw Apply"
    )
  }

  test("external typed label rejects a missing explicit type argument") {
    val outcome =
      compileSnippet(
        """package conflicts
          |
          |import paradise3.externalTypedLabel
          |
          |@externalTypedLabel("missing type argument")
          |class ExternalTypedMissingType
          |""".stripMargin,
        pluginOptions = Seq(
          s"-P:macroparadise:handlerClasspath=$handlerJar"
        )
      )

    assertSingleDiagnostic(
      outcome,
      "@externalTypedLabel",
      "requires exactly one explicit type argument",
      "found 0"
    )
  }

  test("external typed label rejects an unsupported named parameter") {
    val outcome =
      compileSnippet(
        """package conflicts
          |
          |import paradise3.externalTypedLabel
          |
          |@externalTypedLabel[Int](other = "x")
          |class ExternalTypedWrongNamed
          |""".stripMargin,
        pluginOptions = Seq(
          s"-P:macroparadise:handlerClasspath=$handlerJar"
        )
      )

    assertSingleDiagnostic(
      outcome,
      "@externalTypedLabel",
      "argument 0",
      "unsupported named parameter `other`",
      "expected `value`"
    )
  }

  test("external typed label rejects extra term arguments") {
    val outcome =
      compileSnippet(
        """package conflicts
          |
          |import paradise3.externalTypedLabel
          |
          |@externalTypedLabel[Int]("first", "second")
          |class ExternalTypedExtraTerm
          |""".stripMargin,
        pluginOptions = Seq(
          s"-P:macroparadise:handlerClasspath=$handlerJar"
        )
      )

    assertSingleDiagnostic(
      outcome,
      "@externalTypedLabel",
      "requires exactly one term argument for parameter `value`",
      "found 2"
    )
  }

  test("external typed label rejects extra type arguments") {
    val outcome =
      compileSnippet(
        """package conflicts
          |
          |import paradise3.externalTypedLabel
          |
          |@externalTypedLabel[Int, String]("value")
          |class ExternalTypedExtraType
          |""".stripMargin,
        pluginOptions = Seq(
          s"-P:macroparadise:handlerClasspath=$handlerJar"
        )
      )

    assertSingleDiagnostic(
      outcome,
      "@externalTypedLabel",
      "requires exactly one explicit type argument",
      "found 2"
    )
  }

  test("empty explicit handler option reports a clear diagnostic") {
    val outcome =
      compileSnippet(
        """package conflicts
          |
          |class User
          |""".stripMargin,
        pluginOptions = Seq("-P:macroparadise:handler=")
      )

    assertDiagnostic(
      outcome,
      "empty external annotation handler option"
    )
  }

  test("ordinary external invocation failure is controlled") {
    val outcome =
      compileSnippet(
        standaloneProtocolSource("invocationThrows", "InvocationThrowsUser"),
        protocolOptions("demo.InvocationThrowsExpander")
      )

    assertInvocationProtocolDiagnostic(
      outcome,
      "NONFATAL_EXCEPTION",
      "annotation=@invocationThrows",
      "handler=demo.InvocationThrowsExpander",
      "class=InvocationThrowsUser",
      "cause=java.lang.IllegalStateException",
      "message=fixture ordinary failure"
    )
  }

  test("external invocation LinkageError is controlled") {
    val outcome =
      compileSnippet(
        standaloneProtocolSource("invocationLinkageError", "InvocationLinkageUser"),
        protocolOptions("demo.InvocationLinkageErrorExpander")
      )

    assertInvocationProtocolDiagnostic(
      outcome,
      "LINKAGE_ERROR",
      "annotation=@invocationLinkageError",
      "handler=demo.InvocationLinkageErrorExpander",
      "class=InvocationLinkageUser",
      "cause=java.lang.NoClassDefFoundError",
      "message=fixture/missing/InvocationDependency"
    )
  }

  test("null external outcome is rejected without pattern-match failure") {
    val outcome =
      compileSnippet(
        standaloneProtocolSource("invocationNullOutcome", "InvocationNullUser"),
        protocolOptions("demo.InvocationNullOutcomeExpander")
      )

    assertInvocationProtocolDiagnostic(
      outcome,
      "NULL_OUTCOME",
      "annotation=@invocationNullOutcome",
      "handler=demo.InvocationNullOutcomeExpander",
      "class=InvocationNullUser",
      "returned null instead of ExpansionOutcome"
    )
  }

  test("selected external handler NotApplicable is a deterministic rejection") {
    val outcome =
      compileSnippet(
        standaloneProtocolSource("invocationNotApplicable", "InvocationNotApplicableUser"),
        protocolOptions("demo.InvocationNotApplicableExpander")
      )

    assertInvocationProtocolDiagnostic(
      outcome,
      "SELECTED_HANDLER_NOT_APPLICABLE",
      "annotation=@invocationNotApplicable",
      "handler=demo.InvocationNotApplicableExpander",
      "class=InvocationNotApplicableUser",
      "selected handler declined the admitted target"
    )
  }

  test("empty external rejection diagnostics are synthesized") {
    val outcome =
      compileSnippet(
        standaloneProtocolSource("invocationEmptyRejected", "InvocationEmptyRejectedUser"),
        protocolOptions("demo.InvocationEmptyRejectedExpander")
      )

    assertInvocationProtocolDiagnostic(
      outcome,
      "EMPTY_REJECTION_DIAGNOSTICS",
      "annotation=@invocationEmptyRejected",
      "handler=demo.InvocationEmptyRejectedExpander",
      "class=InvocationEmptyRejectedUser",
      "returned Rejected without a diagnostic"
    )
  }

  test("null external rejection diagnostics are replaced") {
    val outcome =
      compileSnippet(
        standaloneProtocolSource(
          "invocationNullRejectedDiagnostics",
          "InvocationNullRejectedDiagnosticsUser"
        ),
        protocolOptions("demo.InvocationNullRejectedDiagnosticsExpander")
      )

    assertInvocationProtocolDiagnostic(
      outcome,
      "NULL_REJECTION_DIAGNOSTICS",
      "annotation=@invocationNullRejectedDiagnostics",
      "handler=demo.InvocationNullRejectedDiagnosticsExpander",
      "class=InvocationNullRejectedDiagnosticsUser",
      "returned Rejected with a null diagnostics list"
    )
  }

  test("null external rejected fallback is replaced by the current primary") {
    val outcome =
      compileSnippet(
        standaloneProtocolSource(
          "invocationNullRejectedFallback",
          "InvocationNullRejectedFallbackUser"
        ),
        protocolOptions("demo.InvocationNullRejectedFallbackExpander")
      )

    assertInvocationProtocolDiagnostic(
      outcome,
      "NULL_REJECTED_FALLBACK",
      "annotation=@invocationNullRejectedFallback",
      "handler=demo.InvocationNullRejectedFallbackExpander",
      "class=InvocationNullRejectedFallbackUser",
      "returned Rejected with a null fallback"
    )
  }

  test("wrong-name rejected fallback is replaced by the current primary") {
    val outcome =
      compileSnippet(
        standaloneProtocolSource("invocationWrongFallback", "InvocationWrongFallbackUser"),
        protocolOptions("demo.InvocationWrongFallbackExpander")
      )

    assertInvocationProtocolDiagnostic(
      outcome,
      "INVALID_REJECTED_FALLBACK_NAME",
      "annotation=@invocationWrongFallback",
      "handler=demo.InvocationWrongFallbackExpander",
      "class=InvocationWrongFallbackUser",
      "returned fallback `WrongRejectedFallback`",
      "current primary `InvocationWrongFallbackUser`"
    )
  }

  test("companion-consuming invocation failure restores the user companion") {
    val outcome =
      compileSnippet(
        companionProtocolSource("companionInvocationThrows", "CompanionInvocationThrowsUser"),
        protocolOptions("demo.CompanionInvocationThrowsExpander")
      )

    assertInvocationProtocolDiagnostic(
      outcome,
      "NONFATAL_EXCEPTION",
      "annotation=@companionInvocationThrows",
      "handler=demo.CompanionInvocationThrowsExpander",
      "class=CompanionInvocationThrowsUser",
      "cause=java.lang.IllegalArgumentException",
      "message=companion fixture failure"
    )
  }

  test("companion-consuming LinkageError restores the user companion") {
    val outcome =
      compileSnippet(
        companionProtocolSource(
          "companionInvocationLinkageError",
          "CompanionInvocationLinkageUser"
        ),
        protocolOptions("demo.CompanionInvocationLinkageErrorExpander")
      )

    assertInvocationProtocolDiagnostic(
      outcome,
      "LINKAGE_ERROR",
      "annotation=@companionInvocationLinkageError",
      "handler=demo.CompanionInvocationLinkageErrorExpander",
      "class=CompanionInvocationLinkageUser",
      "cause=java.lang.NoSuchMethodError",
      "message=fixture companion linkage"
    )
  }

  test("companion-consuming null outcome restores the user companion") {
    val outcome =
      compileSnippet(
        companionProtocolSource(
          "companionInvocationNullOutcome",
          "CompanionInvocationNullOutcomeUser"
        ),
        protocolOptions("demo.CompanionInvocationNullOutcomeExpander")
      )

    assertInvocationProtocolDiagnostic(
      outcome,
      "NULL_OUTCOME",
      "annotation=@companionInvocationNullOutcome",
      "handler=demo.CompanionInvocationNullOutcomeExpander",
      "class=CompanionInvocationNullOutcomeUser"
    )
  }

  test("companion-consuming NotApplicable restores the user companion") {
    val outcome =
      compileSnippet(
        companionProtocolSource(
          "companionInvocationNotApplicable",
          "CompanionInvocationNotApplicableUser"
        ),
        protocolOptions("demo.CompanionInvocationNotApplicableExpander")
      )

    assertInvocationProtocolDiagnostic(
      outcome,
      "SELECTED_HANDLER_NOT_APPLICABLE",
      "annotation=@companionInvocationNotApplicable",
      "handler=demo.CompanionInvocationNotApplicableExpander",
      "class=CompanionInvocationNotApplicableUser"
    )
  }

  test("companion-consuming valid rejection restores the user companion") {
    val outcome =
      compileSnippet(
        companionProtocolSource(
          "companionInvocationRejected",
          "CompanionInvocationRejectedUser"
        ),
        protocolOptions("demo.CompanionInvocationRejectedExpander")
      )

    assertControlledHandlerRejection(
      outcome,
      "fixture companion rejection"
    )
  }

  test("companion-consuming malformed rejection restores the user companion") {
    val outcome =
      compileSnippet(
        companionProtocolSource(
          "companionInvocationWrongFallback",
          "CompanionInvocationWrongFallbackUser"
        ),
        protocolOptions("demo.CompanionInvocationWrongFallbackExpander")
      )

    assertInvocationProtocolDiagnostic(
      outcome,
      "INVALID_REJECTED_FALLBACK_NAME",
      "annotation=@companionInvocationWrongFallback",
      "handler=demo.CompanionInvocationWrongFallbackExpander",
      "class=CompanionInvocationWrongFallbackUser",
      "returned fallback `WrongRejectedFallback`"
    )
  }

  test("later admitted composition failure rolls back every earlier output lane") {
    val outcome =
      compileSnippet(
        """package invocationprotocol
          |
          |import paradise3.{externalCompanionDebug, externalDebug, externalSiblingDebug, gen}
          |
          |@gen
          |@externalDebug
          |@externalSiblingDebug
          |@externalCompanionDebug
          |class InvocationCompositionFailureUser(name: String):
          |  def generatedHello: String = "original"
          |
          |object InvocationCompositionFailureUser:
          |  val preserved: Int = 42
          |  def generatedFactory(name: String): InvocationCompositionFailureUser =
          |    new InvocationCompositionFailureUser(name)
          |
          |object InvocationCompositionFailureWitness:
          |  val preserved: Int = InvocationCompositionFailureUser.preserved
          |""".stripMargin,
        pluginOptions = Seq(
          s"-P:macroparadise:handlerClasspath=$handlerJar"
        )
      )

    assertInvocationProtocolDiagnostic(
      outcome,
      "NONFATAL_EXCEPTION",
      "annotation=@externalCompanionDebug",
      "handler=demo.ExternalCompanionDebugExpander",
      "class=InvocationCompositionFailureUser",
      "cause=java.lang.IllegalStateException",
      "message=later admitted composition fixture failure"
    )
  }

  test("standalone-only participant rejects atomically before any handler invocation") {
    val (outcome, invocations) =
      compileSnippetWithInvocationTrace(
        """package compositioncontract
          |
          |import paradise3.{externalDebug, externalMarker}
          |
          |@externalDebug
          |@externalMarker
          |class StandaloneParticipantUser
          |""".stripMargin,
        Seq(
          s"-P:macroparadise:handlerClasspath=$handlerJar",
          "-P:macroparadise:handler=demo.ExternalMarkerExpander"
        )
      )

    assertBoundaryDiagnostic(
      outcome,
      "admission",
      "category=STANDALONE_COMPOSITION_PARTICIPANT",
      "@externalDebug, @externalMarker (source order)",
      "@externalMarker declares StandaloneOnly"
    )
    assertEquals(invocations, Nil)
  }

  test("composable handler dropping a later annotation rolls back earlier generated lanes") {
    val outcome =
      compileSnippet(
        """package compositioncontract
          |
          |import paradise3.{externalDebug, gen}
          |import scala.annotation.StaticAnnotation
          |
          |final class compositionDropsLater extends StaticAnnotation
          |
          |@gen
          |@compositionDropsLater
          |@externalDebug
          |class DropsLaterUser(val name: String)
          |""".stripMargin,
        pluginOptions = Seq(
          s"-P:macroparadise:handlerClasspath=$handlerJar",
          "-P:macroparadise:handler=demo.CompositionDropsLaterExpander"
        )
      )

    assertBoundaryDiagnostic(
      outcome,
      "output-validation",
      "category=COMPOSITION_ANNOTATION_PRESERVATION",
      "annotation=@compositionDropsLater",
      "later handled annotation @externalDebug was not preserved by identity"
    )
  }

  test("composable handler retaining its current annotation is rejected transactionally") {
    val outcome =
      compileSnippet(
        """package compositioncontract
          |
          |import paradise3.externalDebug
          |import scala.annotation.StaticAnnotation
          |
          |final class compositionRetainsCurrent extends StaticAnnotation
          |
          |@compositionRetainsCurrent
          |@externalDebug
          |class RetainsCurrentUser
          |""".stripMargin,
        pluginOptions = Seq(
          s"-P:macroparadise:handlerClasspath=$handlerJar",
          "-P:macroparadise:handler=demo.CompositionRetainsCurrentExpander"
        )
      )

    assertBoundaryDiagnostic(
      outcome,
      "output-validation",
      "category=COMPOSITION_ANNOTATION_PRESERVATION",
      "annotation=@compositionRetainsCurrent",
      "current handled annotation was not consumed"
    )
  }

  test("reconstructing a later same-name annotation does not satisfy identity preservation") {
    val outcome =
      compileSnippet(
        """package compositioncontract
          |
          |import paradise3.externalDebug
          |import scala.annotation.StaticAnnotation
          |
          |final class compositionReconstructsLater extends StaticAnnotation
          |
          |@compositionReconstructsLater
          |@externalDebug
          |class ReconstructsLaterUser
          |""".stripMargin,
        pluginOptions = Seq(
          s"-P:macroparadise:handlerClasspath=$handlerJar",
          "-P:macroparadise:handler=demo.CompositionReconstructsLaterExpander"
        )
      )

    assertBoundaryDiagnostic(
      outcome,
      "output-validation",
      "category=COMPOSITION_ANNOTATION_PRESERVATION",
      "annotation=@compositionReconstructsLater",
      "later handled annotation @externalDebug was not preserved by identity"
    )
  }

  test("reconstructing the consumed current annotation violates handled closure") {
    val outcome =
      compileSnippet(
        """package compositionclosure
          |
          |import paradise3.externalDebug
          |import scala.annotation.StaticAnnotation
          |
          |final class compositionReconstructsCurrent extends StaticAnnotation
          |
          |@compositionReconstructsCurrent
          |@externalDebug
          |class ReconstructedCurrentUser
          |""".stripMargin,
        pluginOptions = Seq(
          s"-P:macroparadise:handlerClasspath=$handlerJar",
          "-P:macroparadise:handler=demo.CompositionReconstructsCurrentExpander"
        )
      )

    assertBoundaryDiagnostic(
      outcome,
      "output-validation",
      "category=COMPOSITION_ANNOTATION_PRESERVATION",
      "reason=RECONSTRUCTED_CURRENT_HANDLED_ANNOTATION",
      "unexpected reconstructed current handled annotation @compositionReconstructsCurrent"
    )
  }

  test("preserving a later annotation plus a reconstructed duplicate violates handled closure") {
    val outcome =
      compileSnippet(
        """package compositionclosure
          |
          |import paradise3.externalDebug
          |import scala.annotation.StaticAnnotation
          |
          |final class compositionDuplicatesLater extends StaticAnnotation
          |
          |@compositionDuplicatesLater
          |@externalDebug
          |class DuplicatedLaterUser
          |""".stripMargin,
        pluginOptions = Seq(
          s"-P:macroparadise:handlerClasspath=$handlerJar",
          "-P:macroparadise:handler=demo.CompositionDuplicatesLaterExpander"
        )
      )

    assertBoundaryDiagnostic(
      outcome,
      "output-validation",
      "category=COMPOSITION_ANNOTATION_PRESERVATION",
      "reason=EXTRA_RECONSTRUCTED_LATER_HANDLED_DUPLICATE",
      "unexpected duplicate/reconstructed later handled annotation @externalDebug"
    )
  }

  test("a later step cannot reintroduce an already consumed handled annotation") {
    val outcome =
      compileSnippet(
        """package compositionclosure
          |
          |import paradise3.{externalDebug, gen}
          |import scala.annotation.StaticAnnotation
          |
          |final class compositionReintroducesConsumed extends StaticAnnotation
          |
          |@gen
          |@compositionReintroducesConsumed
          |@externalDebug
          |class ReintroducedConsumedUser(val name: String)
          |
          |object ReintroducedConsumedUser:
          |  val preserved: Int = 42
          |""".stripMargin,
        pluginOptions = Seq(
          s"-P:macroparadise:handlerClasspath=$handlerJar",
          "-P:macroparadise:handler=demo.CompositionReintroducesConsumedExpander"
        )
      )

    assertBoundaryDiagnostic(
      outcome,
      "output-validation",
      "category=COMPOSITION_ANNOTATION_PRESERVATION",
      "reason=REINTRODUCED_CONSUMED_HANDLED_ANNOTATION",
      "reintroduced already-consumed handled annotation @gen"
    )
  }

  test("a step cannot introduce a different annotation handled by this plugin run") {
    val outcome =
      compileSnippet(
        """package compositionclosure
          |
          |import paradise3.{debug, externalDebug}
          |import scala.annotation.StaticAnnotation
          |
          |final class compositionIntroducesDifferentHandled extends StaticAnnotation
          |
          |@compositionIntroducesDifferentHandled
          |@externalDebug
          |class IntroducedDifferentHandledUser
          |""".stripMargin,
        pluginOptions = Seq(
          s"-P:macroparadise:handlerClasspath=$handlerJar",
          "-P:macroparadise:handler=demo.CompositionIntroducesDifferentHandledExpander"
        )
      )

    assertBoundaryDiagnostic(
      outcome,
      "output-validation",
      "category=COMPOSITION_ANNOTATION_PRESERVATION",
      "reason=NEW_UNEXPECTED_HANDLED_ANNOTATION",
      "new unexpected handled annotation @debug"
    )
  }

  test("mixed target profiles reject before invocation when one participant excludes the target") {
    val (outcome, invocations) =
      compileSnippetWithInvocationTrace(
        """package compositioncontract
          |
          |import paradise3.{mixedRestrictedCompanion, mixedUnionCompanion}
          |
          |trait MixedProfileBound
          |
          |@mixedUnionCompanion
          |@mixedRestrictedCompanion
          |trait MixedTargetProfilesOutside[
          |  A <: MixedProfileBound,
          |  B <: MixedProfileBound
          |]
          |""".stripMargin,
        Seq(
          s"-P:macroparadise:handlerClasspath=$handlerJar"
        )
      )

    outcome match
      case CompileOutcome.ReportedErrors(messages, outputFiles) =>
        val diagnostic = messages.mkString("\n")
        assert(
          diagnostic.contains(
            "@mixedRestrictedCompanion requires one top-level non-sealed ordinary trait with exactly one invariant, ordinary unbounded type parameter"
          ),
          diagnostic
        )
        assert(diagnostic.contains("found 2 type parameters"), diagnostic)
        assert(!diagnostic.contains("INCOMPATIBLE_COMPOSITION_TARGET_PROFILES"), diagnostic)
        assertEquals(
          outputFiles,
          Nil,
          s"unexpected partial class/Tasty output: ${outputFiles.mkString(", ")}"
        )
      case CompileOutcome.Threw(throwable, outputFiles) =>
        fail(
          s"expected participant-specific admission diagnostic, got ${throwable.getClass.getName}: " +
            s"${throwable.getMessage}; outputs=${outputFiles.mkString(", ")}"
        )
      case other =>
        fail(s"expected participant-specific admission diagnostic, got $other")
    assertEquals(invocations, Nil)
  }

  test("mixed-profile late failure invokes both participants and rolls back the companion transaction") {
    val (outcome, invocations) =
      compileSnippetWithInvocationTrace(
        """package compositioncontract
          |
          |import paradise3.{mixedRestrictedCompanion, mixedUnionCompanion}
          |
          |@mixedUnionCompanion
          |@mixedRestrictedCompanion
          |trait MixedProfileLateFailureUser[A]
          |
          |object MixedProfileLateFailureUser:
          |  val preserved: Int = 126
          |
          |object MixedProfileLateFailureWitness:
          |  val preserved: Int = MixedProfileLateFailureUser.preserved
          |""".stripMargin,
        Seq(
          s"-P:macroparadise:handlerClasspath=$handlerJar"
        )
      )

    assertInvocationProtocolDiagnostic(
      outcome,
      "NONFATAL_EXCEPTION",
      "annotation=@mixedRestrictedCompanion",
      "handler=demo.MixedRestrictedCompanionExpander",
      "class=MixedProfileLateFailureUser",
      "cause=java.lang.IllegalStateException",
      "message=mixed-profile late-step fixture failure"
    )
    assertEquals(
      invocations,
      List(
        "handler=demo.MixedUnionCompanionExpander annotation=mixedUnionCompanion class=MixedProfileLateFailureUser",
        "handler=demo.MixedRestrictedCompanionExpander annotation=mixedRestrictedCompanion class=MixedProfileLateFailureUser"
      )
    )
  }

  test("raw output validation remains active inside source-ordered composition") {
    val outcome =
      compileSnippet(
        """package compositioncontract
          |
          |import paradise3.{externalDebug, malformedMissingPrimary}
          |
          |@externalDebug
          |@malformedMissingPrimary
          |class ComposedMalformedRawUser
          |""".stripMargin,
        pluginOptions = Seq(
          s"-P:macroparadise:handlerClasspath=$handlerJar",
          "-P:macroparadise:handler=demo.MalformedMissingPrimaryExpander"
        )
      )

    assertRawValidationDiagnostic(
      outcome,
      "external handler `demo.MalformedMissingPrimaryExpander`",
      "@malformedMissingPrimary",
      "primary `ComposedMalformedRawUser`",
      "invariant B (primary first) failed"
    )
  }

  test("empty raw external output is rejected before splicing") {
    val outcome =
      compileSnippet(
        """package malformed
          |
          |import paradise3.malformedEmptyOutput
          |
          |@malformedEmptyOutput
          |class EmptyOutputUser
          |""".stripMargin,
        pluginOptions = Seq(
          s"-P:macroparadise:handlerClasspath=$handlerJar",
          "-P:macroparadise:handler=demo.MalformedEmptyOutputExpander"
        )
      )

    assertRawValidationDiagnostic(
      outcome,
      "external handler `demo.MalformedEmptyOutputExpander`",
      "@malformedEmptyOutput",
      "primary `EmptyOutputUser`",
      "invariant A (non-empty output) failed",
      "returned no trees"
    )
  }

  test("raw external output missing the primary is rejected before splicing") {
    val outcome =
      compileSnippet(
        """package malformed
          |
          |import paradise3.malformedMissingPrimary
          |
          |@malformedMissingPrimary
          |class MissingPrimaryUser
          |""".stripMargin,
        pluginOptions = Seq(
          s"-P:macroparadise:handlerClasspath=$handlerJar",
          "-P:macroparadise:handler=demo.MalformedMissingPrimaryExpander"
        )
      )

    assertRawValidationDiagnostic(
      outcome,
      "external handler `demo.MalformedMissingPrimaryExpander`",
      "@malformedMissingPrimary",
      "primary `MissingPrimaryUser`",
      "invariant B (primary first) failed",
      "TypeDef `WrongPrimary`"
    )
  }

  test("duplicate raw primary output is rejected before compiler duplicate handling") {
    val outcome =
      compileSnippet(
        """package malformed
          |
          |import paradise3.malformedDuplicatePrimary
          |
          |@malformedDuplicatePrimary
          |class DuplicatePrimaryUser
          |""".stripMargin,
        pluginOptions = Seq(
          s"-P:macroparadise:handlerClasspath=$handlerJar",
          "-P:macroparadise:handler=demo.MalformedDuplicatePrimaryExpander"
        )
      )

    assertRawValidationDiagnostic(
      outcome,
      "external handler `demo.MalformedDuplicatePrimaryExpander`",
      "@malformedDuplicatePrimary",
      "primary `DuplicatePrimaryUser`",
      "invariant C (exactly one primary) failed",
      "found 2"
    )
  }

  test("raw additional output conflicting with a known top-level name is rejected") {
    val outcome =
      compileSnippet(
        """package malformed
          |
          |import paradise3.malformedConflictingAdditional
          |
          |class KnownConflict
          |
          |@malformedConflictingAdditional
          |class ConflictingAdditionalUser
          |""".stripMargin,
        pluginOptions = Seq(
          s"-P:macroparadise:handlerClasspath=$handlerJar",
          "-P:macroparadise:handler=demo.MalformedConflictingAdditionalExpander"
        )
      )

    assertRawValidationDiagnostic(
      outcome,
      "external handler `demo.MalformedConflictingAdditionalExpander`",
      "@malformedConflictingAdditional",
      "primary `ConflictingAdditionalUser`",
      "invariant G (no known top-level conflict) failed",
      "additional output name `KnownConflict`"
    )
  }

  test("late raw companion output is rejected before composition or splicing") {
    val outcome =
      compileSnippet(
        """package malformed
          |
          |import paradise3.malformedLateCompanion
          |
          |@malformedLateCompanion
          |class LateCompanionUser
          |
          |object LateCompanionUser:
          |  val preserved: Int = 42
          |
          |object LateCompanionWitness:
          |  val preserved: Int = LateCompanionUser.preserved
          |""".stripMargin,
        pluginOptions = Seq(
          s"-P:macroparadise:handlerClasspath=$handlerJar",
          "-P:macroparadise:handler=demo.MalformedLateCompanionExpander"
        )
      )

    assertRawValidationDiagnostic(
      outcome,
      "external handler `demo.MalformedLateCompanionExpander`",
      "@malformedLateCompanion",
      "primary `LateCompanionUser`",
      "invariant E (companion immediately after primary) failed",
      "must immediately follow the primary"
    )
  }

  test("@gen rejects a missing constructor parameter before expansion") {
    val outcome =
      compileSnippet(
        """import paradise3.gen
          |@gen class MissingName
          |""".stripMargin
      )
    assertFocusedAdmissionDiagnostic(
      outcome,
      "unsupported constructor shape for @gen on `MissingName`",
      "exactly `name: String`",
      "found 0 term parameter clause(s)"
    )
  }

  test("@gen rejects a differently named constructor parameter") {
    val outcome =
      compileSnippet(
        """import paradise3.gen
          |@gen class WrongName(other: String)
          |""".stripMargin
      )
    assertFocusedAdmissionDiagnostic(
      outcome,
      "unsupported constructor shape for @gen on `WrongName`",
      "found parameter `other`"
    )
  }

  test("@gen rejects a wrong raw constructor parameter type") {
    val outcome =
      compileSnippet(
        """import paradise3.gen
          |@gen class WrongType(name: Int)
          |""".stripMargin
      )
    assertFocusedAdmissionDiagnostic(
      outcome,
      "unsupported constructor shape for @gen on `WrongType`",
      "raw type identifier `Int`",
      "syntactic identifier `String`"
    )
  }

  test("@gen rejects an extra required constructor parameter") {
    val outcome =
      compileSnippet(
        """import paradise3.gen
          |@gen class Extra(name: String, age: Int)
          |""".stripMargin
      )
    assertFocusedAdmissionDiagnostic(
      outcome,
      "unsupported constructor shape for @gen on `Extra`",
      "parameter counts [2]"
    )
  }

  test("@gen rejects multiple constructor parameter clauses") {
    val outcome =
      compileSnippet(
        """import paradise3.gen
          |@gen class MultipleClauses(name: String)(age: Int)
          |""".stripMargin
      )
    assertFocusedAdmissionDiagnostic(
      outcome,
      "unsupported constructor shape for @gen on `MultipleClauses`",
      "found 2 term parameter clause(s)",
      "parameter counts [1, 1]"
    )
  }

  test("@gen rejects an abstract class before generated construction") {
    val outcome =
      compileSnippet(
        """import paradise3.gen
          |@gen abstract class AbstractUser(name: String)
          |""".stripMargin
      )
    assertFocusedAdmissionDiagnostic(
      outcome,
      "unsupported class family for @gen on `AbstractUser`",
      "`generatedFactory` constructs the annotated class"
    )
  }

  test("external handled annotation rejects a generic class through the common envelope") {
    val outcome =
      compileSnippet(
        """import paradise3.externalDebug
          |@externalDebug class GenericUser[A]
          |""".stripMargin,
        pluginOptions = Seq(s"-P:macroparadise:handlerClasspath=$handlerJar")
      )
    assertFocusedAdmissionDiagnostic(
      outcome,
      "@externalDebug currently supports non-generic classes",
      "unsupported generic class shape `GenericUser`",
      "1 raw type parameter"
    )
  }

  test("external handled annotation on a trait reports one focused target diagnostic") {
    val outcome =
      compileSnippet(
        """import paradise3.externalDebug
          |@externalDebug trait ExternalTrait
          |""".stripMargin,
        pluginOptions = Seq(s"-P:macroparadise:handlerClasspath=$handlerJar")
      )
    assertFocusedAdmissionDiagnostic(
      outcome,
      "@externalDebug currently supports only top-level classes",
      "unsupported target `trait ExternalTrait`"
    )
  }

  test("external handled annotation on a nested class reports one focused target diagnostic") {
    val outcome =
      compileSnippet(
        """import paradise3.externalDebug
          |object Outer:
          |  @externalDebug class Nested
          |""".stripMargin,
        pluginOptions = Seq(s"-P:macroparadise:handlerClasspath=$handlerJar")
      )
    assertFocusedAdmissionDiagnostic(
      outcome,
      "@externalDebug currently supports only top-level classes",
      "unsupported target `nested class Nested`"
    )
  }

  test("composition admission rejects before an earlier handler can emit partial output") {
    val outcome =
      compileSnippet(
        """import paradise3.{externalDebug, gen}
          |@externalDebug
          |@gen
          |class AtomicInvalid
          |""".stripMargin,
        pluginOptions = Seq(s"-P:macroparadise:handlerClasspath=$handlerJar")
      )
    assertFocusedAdmissionDiagnostic(
      outcome,
      "unsupported constructor shape for @gen on `AtomicInvalid`",
      "found 0 term parameter clause(s)"
    )
  }

  test("multiple handled annotations on one unsupported target produce one source-ordered diagnostic") {
    val outcome =
      compileSnippet(
        """import paradise3.{externalDebug, gen}
          |@gen
          |@externalDebug
          |trait MultiTrait
          |""".stripMargin,
        pluginOptions = Seq(s"-P:macroparadise:handlerClasspath=$handlerJar")
      )
    assertFocusedAdmissionDiagnostic(
      outcome,
      "handled annotations @gen, @externalDebug currently support only top-level classes",
      "unsupported target `trait MultiTrait`"
    )
  }

  test("qualified same-simple annotations select distinct metadata handlers in one compilation") {
    val outcome =
      compileSnippet(
        """@qualifiedone.audit class QualifiedOneUser
          |@qualifiedtwo.audit class QualifiedTwoUser
          |
          |object QualifiedIdentityWitness:
          |  val one = new QualifiedOneUser().qualifiedOneAuditName
          |  val two = new QualifiedTwoUser().qualifiedTwoAuditName
          |""".stripMargin,
        pluginOptions = Seq(s"-P:macroparadise:handlerClasspath=$handlerJar")
      )

    outcome match
      case CompileOutcome.Succeeded(outputFiles) =>
        assert(outputFiles.exists(_.endsWith("QualifiedOneUser.class")), outputFiles.mkString("\n"))
        assert(outputFiles.exists(_.endsWith("QualifiedTwoUser.class")), outputFiles.mkString("\n"))
      case other =>
        fail(s"expected qualified collision success, got $other")
  }

  test("one explicit import canonicalizes a short external annotation identity") {
    val outcome =
      compileSnippet(
        """import qualifiedone.audit
          |@audit class ImportedShortAuditUser
          |object ImportedShortAuditWitness:
          |  val value = new ImportedShortAuditUser().qualifiedOneAuditName
          |""".stripMargin,
        pluginOptions = Seq(s"-P:macroparadise:handlerClasspath=$handlerJar")
      )

    outcome match
      case CompileOutcome.Succeeded(outputFiles) =>
        assert(outputFiles.exists(_.endsWith("ImportedShortAuditUser.class")), outputFiles.mkString("\n"))
      case other => fail(s"expected explicit-import short annotation success, got $other")
  }

  test("an exact canonical handler wins over a registered legacy simple-name handler") {
    val outcome =
      compileSnippet(
        """import qualifiedone.audit
          |@audit class CanonicalOverLegacyUser
          |object CanonicalOverLegacyWitness:
          |  val value = new CanonicalOverLegacyUser().qualifiedOneAuditName
          |""".stripMargin,
        pluginOptions = Seq(
          s"-P:macroparadise:handlerClasspath=$handlerJar",
          "-P:macroparadise:handler=demo.LegacySimpleAuditExpander",
          "-P:macroparadise:handler=demo.QualifiedOneAuditExpander"
        )
      )

    outcome match
      case CompileOutcome.Succeeded(outputFiles) =>
        assert(outputFiles.exists(_.endsWith("CanonicalOverLegacyUser.class")), outputFiles.mkString("\n"))
      case other => fail(s"expected canonical handler precedence, got $other")
  }

  test("a reconstructed imported canonical annotation violates composition closure") {
    val outcome =
      compileSnippet(
        """import qualifiedunknown.audit
          |import paradise3.externalDebug
          |@audit
          |@externalDebug
          |class ReconstructedImportedCanonicalUser
          |""".stripMargin,
        pluginOptions = Seq(
          s"-P:macroparadise:handlerClasspath=$handlerJar",
          "-P:macroparadise:handler=demo.QualifiedImportedReconstructsCurrentExpander"
        )
      )

    assertBoundaryDiagnostic(
      outcome,
      "output-validation",
      "category=COMPOSITION_ANNOTATION_PRESERVATION",
      "reason=RECONSTRUCTED_CURRENT_HANDLED_ANNOTATION",
      "unexpected reconstructed current handled annotation @qualifiedunknown.audit"
    )
  }

  test("a nested import shadowing a package import fails at the bounded resolver") {
    val outcome =
      compileSnippet(
        """import qualifiedone.audit
          |object LocalImportScope:
          |  import qualifiedtwo.audit
          |  @audit class NestedAuditUser
          |""".stripMargin,
        pluginOptions = Seq(s"-P:macroparadise:handlerClasspath=$handlerJar")
      )

    assertDiagnostic(
      outcome,
      "unsupported local/nested import scope for short annotation `@audit`",
      "use a qualified annotation"
    )
  }

  test("two explicit imports for one short annotation fail with deterministic canonical candidates") {
    val outcome =
      compileSnippet(
        """import qualifiedtwo.audit
          |import qualifiedone.audit
          |@audit class AmbiguousImportedShortAuditUser
          |""".stripMargin,
        pluginOptions = Seq(s"-P:macroparadise:handlerClasspath=$handlerJar")
      )

    assertDiagnostic(
      outcome,
      "ambiguous explicit-import annotation identity for `@audit`",
      "qualifiedone.audit",
      "qualifiedtwo.audit"
    )
  }

  test("a direct short annotation without an explicit witness keeps the existing fail-closed discovery boundary") {
    val outcome =
      compileSnippet(
        "@audit class UnwitnessedShortAuditUser",
        pluginOptions = Seq(s"-P:macroparadise:handlerClasspath=$handlerJar")
      )

    assertBoundaryDiagnostic(
      outcome,
      "discovery",
      "category=METADATA_DISCOVERY_FAILURE",
      "ambiguous runtime annotation metadata for `audit`"
    )
  }

  test("qualified metadata binding mismatch reports both exact identities") {
    val outcome =
      compileSnippet(
        """@qualifiedwrong.audit class WrongQualifiedBindingUser
          |""".stripMargin,
        pluginOptions = Seq(s"-P:macroparadise:handlerClasspath=$handlerJar")
      )

    assertBoundaryDiagnostic(
      outcome,
      "loading",
      "category=METADATA_HANDLER_ANNOTATION_MISMATCH",
      "annotation=@qualifiedwrong.audit",
      "declaredAnnotation=@qualifiedtwo.audit"
    )
  }

  test("explicit-import metadata binding mismatch reports both canonical identities") {
    val outcome =
      compileSnippet(
        """import qualifiedwrong.audit
          |@audit class WrongImportedBindingUser
          |""".stripMargin,
        pluginOptions = Seq(s"-P:macroparadise:handlerClasspath=$handlerJar")
      )

    assertBoundaryDiagnostic(
      outcome,
      "loading",
      "category=METADATA_HANDLER_ANNOTATION_MISMATCH",
      "annotation=@qualifiedwrong.audit",
      "declaredAnnotation=@qualifiedtwo.audit"
    )
  }

  test("qualified built-in lookalike is handled externally and is not reserved") {
    val outcome =
      compileSnippet(
        """@qualifiedlookalike.gen class QualifiedGenUser
          |object QualifiedGenWitness:
          |  val value = new QualifiedGenUser().qualifiedGenName
          |""".stripMargin,
        pluginOptions = Seq(s"-P:macroparadise:handlerClasspath=$handlerJar")
      )

    outcome match
      case CompileOutcome.Succeeded(_) => ()
      case other => fail(s"expected qualified built-in lookalike success, got $other")
  }

  test("duplicate exact qualified handler registration is rejected") {
    val outcome =
      compileSnippet(
        "class DuplicateQualifiedRegistrationUser",
        pluginOptions = Seq(
          s"-P:macroparadise:handlerClasspath=$handlerJar",
          "-P:macroparadise:handler=demo.QualifiedOneAuditExpander",
          "-P:macroparadise:handler=demo.DuplicateQualifiedOneAuditExpander"
        )
      )

    assertBoundaryDiagnostic(
      outcome,
      "loading",
      "category=DUPLICATE_HANDLER_REGISTRATION",
      "annotation=@qualifiedone.audit"
    )
  }

  test("same simple name under distinct qualified registrations is allowed") {
    val outcome =
      compileSnippet(
        """@qualifiedone.audit class ExplicitQualifiedOneUser
          |@qualifiedtwo.audit class ExplicitQualifiedTwoUser
          |object ExplicitQualifiedWitness:
          |  val one = new ExplicitQualifiedOneUser().qualifiedOneAuditName
          |  val two = new ExplicitQualifiedTwoUser().qualifiedTwoAuditName
          |""".stripMargin,
        pluginOptions = Seq(
          s"-P:macroparadise:handlerClasspath=$handlerJar",
          "-P:macroparadise:handler=demo.QualifiedOneAuditExpander",
          "-P:macroparadise:handler=demo.QualifiedTwoAuditExpander"
        )
      )

    outcome match
      case CompileOutcome.Succeeded(_) => ()
      case other => fail(s"expected distinct qualified registrations to coexist, got $other")
  }

  test("unknown qualified annotation remains unhandled and is not stripped") {
    val outcome =
      compileSnippet("@qualifiedunknown.audit class UnknownQualifiedAuditUser")

    outcome match
      case CompileOutcome.Succeeded(_) => ()
      case other => fail(s"expected unknown qualified annotation to remain ordinary, got $other")
  }

  test("handled qualified annotation strips only itself and preserves an unhandled qualified annotation") {
    val outcome =
      compileSnippet(
        """@qualifiedone.audit
          |@qualifiedunknown.audit
          |class QualifiedClosureUser
          |object QualifiedClosureWitness:
          |  val value = new QualifiedClosureUser().qualifiedOneAuditName
          |""".stripMargin,
        pluginOptions = Seq(s"-P:macroparadise:handlerClasspath=$handlerJar")
      )

    outcome match
      case CompileOutcome.Succeeded(_) => ()
      case other => fail(s"expected qualified closure preservation success, got $other")
  }

  test("same-target qualified handlers retain exact identities under existing standalone composition policy") {
    val outcome =
      compileSnippet(
        """@qualifiedone.audit
          |@qualifiedtwo.audit
          |class QualifiedSameTargetUser
          |""".stripMargin,
        pluginOptions = Seq(s"-P:macroparadise:handlerClasspath=$handlerJar")
      )

    assertBoundaryDiagnostic(
      outcome,
      "admission",
      "@qualifiedone.audit",
      "@qualifiedtwo.audit"
    )
  }

  test("aliased qualified annotation is outside bounded syntactic resolution and remains unhandled") {
    val outcome =
      compileSnippet(
        """import qualifiedone.{audit as oneAudit}
          |@oneAudit class AliasedQualifiedAuditUser
          |""".stripMargin,
        pluginOptions = Seq(s"-P:macroparadise:handlerClasspath=$handlerJar")
      )

    outcome match
      case CompileOutcome.Succeeded(_) => ()
      case other => fail(s"expected unresolved alias to remain an ordinary annotation, got $other")
  }

  test("wildcard-imported same-simple annotation fails closed at the measured ambiguity boundary") {
    val outcome =
      compileSnippet(
        """import qualifiedone.*
          |@audit class WildcardQualifiedAuditUser
          |""".stripMargin,
        pluginOptions = Seq(s"-P:macroparadise:handlerClasspath=$handlerJar")
      )

    assertBoundaryDiagnostic(
      outcome,
      "discovery",
      "ambiguous runtime annotation metadata for `audit`"
    )
  }
