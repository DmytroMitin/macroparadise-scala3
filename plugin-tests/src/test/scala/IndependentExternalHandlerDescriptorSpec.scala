import java.io.File
import java.nio.file.{Files, Path}
import java.util.jar.{JarEntry, JarOutputStream}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.sys.process.*

class IndependentExternalHandlerDescriptorSpec extends munit.FunSuite:
  override val munitTimeout: Duration = 180.seconds

  private val scalaVersion =
    sys.props.getOrElse(
      "macroparadise.testScalaVersion",
      "3.8.5-RC1-bin-20260405-9478256-NIGHTLY"
    )
  private val pluginJar =
    file(s"plugin/target/scala-$scalaVersion/macroparadise-scala3-plugin_$scalaVersion-0.1.0.jar")
  private val pluginApiJar =
    file(s"plugin-api/target/scala-$scalaVersion/macroparadise-scala3-plugin-api_$scalaVersion-0.1.0.jar")
  private val producerSource =
    Path.of("plugin-api-handler-contract-probe/descriptor/IndependentDescriptorHandlers.scala").toAbsolutePath
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
      descriptorTrace: List[String],
      invocationTrace: List[String],
      metadataTrace: List[String]
  )

  test("descriptor fixture is a thin independently compiled pluginApi consumer") {
    withCompiledProbe: probe =>
      val entries = jarEntries(probe.artifact)
      assert(entries.contains("external/descriptorprobe/ExplicitSnapshotHandler.class"))
      assert(entries.contains("external/descriptorprobe/MetadataSnapshotHandler.class"))
      assert(entries.contains("external/descriptorprobe/MetadataProfileFailureHandler.class"))
      assert(entries.forall(name => !name.startsWith("paradise3/") && !name.startsWith("macroparadise/") && !name.startsWith("demo/")))
  }

  test("explicit registration freezes alternating declaration capabilities before one expansion") {
    withCompiledProbe: probe =>
      val handler = "external.descriptorprobe.ExplicitSnapshotHandler"
      val result = compileConsumer(
        "ExplicitSnapshot",
        handlerOptions(probe.artifact, Some(handler)),
        probe.artifact
      )

      assertSuccessfulReadOnce(result, handler, "ExplicitTarget")
  }

  test("metadata discovery uses the same read-once descriptor boundary") {
    withCompiledProbe: probe =>
      val handler = "external.descriptorprobe.MetadataSnapshotHandler"
      val result = compileConsumer(
        "MetadataSnapshot",
        handlerOptions(probe.artifact, None),
        probe.artifact,
        className = "MetadataTarget"
      )

      assertSuccessfulReadOnce(result, handler, "MetadataTarget")
  }

  test("all explicit descriptor failures are controlled loading failures with atomic output") {
    withCompiledProbe: probe =>
      val cases = List(
        ("AnnotationNonFatal", "AnnotationNonFatalHandler", "HANDLER_DECLARATION_FAILURE", "annotationName", "java.lang.IllegalStateException", "annotation nonfatal"),
        ("AnnotationLinkage", "AnnotationLinkageHandler", "HANDLER_DECLARATION_FAILURE", "annotationName", "java.lang.LinkageError", "annotation linkage"),
        ("ProfileNonFatal", "ProfileNonFatalHandler", "HANDLER_DECLARATION_FAILURE", "targetProfile", "java.lang.IllegalStateException", "profile nonfatal"),
        ("ProfileLinkage", "ProfileLinkageHandler", "HANDLER_DECLARATION_FAILURE", "targetProfile", "java.lang.LinkageError", "profile linkage"),
        ("CompositionNonFatal", "CompositionNonFatalHandler", "HANDLER_DECLARATION_FAILURE", "compositionPolicy", "java.lang.IllegalStateException", "composition nonfatal"),
        ("CompositionLinkage", "CompositionLinkageHandler", "HANDLER_DECLARATION_FAILURE", "compositionPolicy", "java.lang.LinkageError", "composition linkage"),
        ("CompanionNonFatal", "CompanionNonFatalHandler", "HANDLER_DECLARATION_FAILURE", "consumesExistingCompanion", "java.lang.IllegalStateException", "companion nonfatal"),
        ("CompanionLinkage", "CompanionLinkageHandler", "HANDLER_DECLARATION_FAILURE", "consumesExistingCompanion", "java.lang.LinkageError", "companion linkage"),
        ("NullAnnotation", "NullAnnotationHandler", "INVALID_HANDLER_ANNOTATION_NAME", "annotationName", "", "handler returned null"),
        ("BlankAnnotation", "BlankAnnotationHandler", "INVALID_HANDLER_ANNOTATION_NAME", "annotationName", "", "empty or whitespace-only"),
        ("NullProfile", "NullProfileHandler", "NULL_TARGET_PROFILE", "targetProfile", "", "handler returned null"),
        ("NullComposition", "NullCompositionHandler", "NULL_COMPOSITION_POLICY", "compositionPolicy", "", "handler returned null")
      )

      cases.zipWithIndex.foreach:
        case ((marker, handlerSimpleName, category, accessor, cause, detail), index) =>
          val handler = s"external.descriptorprobe.$handlerSimpleName"
          val result = compileConsumer(
            marker,
            handlerOptions(probe.artifact, Some(handler)),
            probe.artifact,
            className = s"FailedTarget$index",
            target = "class"
          )
          assertControlledFailure(result, handler, category, accessor, cause, detail)
  }

  test("metadata-discovered descriptor failure is controlled and cannot fall back to class-only") {
    withCompiledProbe: probe =>
      val handler = "external.descriptorprobe.MetadataProfileFailureHandler"
      val result = compileConsumer(
        "MetadataProfileFailure",
        handlerOptions(probe.artifact, None),
        probe.artifact,
        className = "MetadataFailedTarget"
      )

      assertControlledFailure(
        result,
        handler,
        "HANDLER_DECLARATION_FAILURE",
        "targetProfile",
        "java.lang.IllegalStateException",
        "metadata profile failure"
      )
  }

  test("explicit matching metadata reuses one exact descriptor capture without reload") {
    withCompiledProbe: probe =>
      val handler = "external.descriptorprobe.ExplicitMetadataReuseHandler"
      val result = compileBindingConsumer(
        List("ExplicitMetadataReuse" -> "ExplicitReuseTarget"),
        handlerOptions(probe.artifact, Some(handler)),
        probe.artifact
      )

      assert(!result.hasErrors, result.messages.mkString("\n"))
      assertBindingReadOnce(result, handler)
      assertEquals(result.descriptorTrace.count(_ == s"expand handler=$handler class=ExplicitReuseTarget"), 1)
      assertEquals(result.invocationTrace.count(_.contains(s"handler=$handler")), 1)
  }

  test("explicit mismatched metadata is diagnosed after exact-instance reuse") {
    withCompiledProbe: probe =>
      val handler = "external.descriptorprobe.ExplicitMetadataMismatchHandler"
      val result = compileBindingConsumer(
        List("ExplicitMetadataWrong" -> "ExplicitMismatchTarget"),
        handlerOptions(probe.artifact, Some(handler)),
        probe.artifact
      )

      assertBindingMismatch(
        result,
        "ExplicitMetadataWrong",
        handler,
        "ExplicitMetadataDeclared",
        expectedDiagnostics = 1
      )
      assertBindingReadOnce(result, handler)
      assertEquals(result.descriptorTrace.count(_.startsWith("expand ")), 0)
      assertEquals(result.invocationTrace.count(_.contains(s"handler=$handler")), 0)
  }

  test("sorted matching then mismatched relations share one class capture and preserve the match") {
    withCompiledProbe: probe =>
      val handler = "external.descriptorprobe.MatchThenMismatchHandler"
      val result = compileBindingConsumer(
        List(
          "AMatchFirst" -> "MatchFirstTarget",
          "ZMismatchSecond" -> "MismatchSecondTarget"
        ),
        handlerOptions(probe.artifact, None),
        probe.artifact
      )

      assertBindingMismatch(
        result,
        "ZMismatchSecond",
        handler,
        "AMatchFirst",
        expectedDiagnostics = 1
      )
      assertBindingReadOnce(result, handler)
      assertEquals(result.descriptorTrace.count(_ == s"expand handler=$handler class=MatchFirstTarget"), 1)
      assertEquals(result.descriptorTrace.count(_.contains("class=MismatchSecondTarget")), 0)
  }

  test("sorted mismatched then matching relations do not poison the cached match") {
    withCompiledProbe: probe =>
      val handler = "external.descriptorprobe.MismatchThenMatchHandler"
      val result = compileBindingConsumer(
        List(
          "AMismatchFirst" -> "MismatchFirstTarget",
          "ZMatchSecond" -> "MatchSecondTarget"
        ),
        handlerOptions(probe.artifact, None),
        probe.artifact
      )

      assertBindingMismatch(
        result,
        "AMismatchFirst",
        handler,
        "ZMatchSecond",
        expectedDiagnostics = 1
      )
      assertBindingReadOnce(result, handler)
      assertEquals(result.descriptorTrace.count(_ == s"expand handler=$handler class=MatchSecondTarget"), 1)
      assertEquals(result.descriptorTrace.count(_.contains("class=MismatchFirstTarget")), 0)
  }

  test("two mismatched relations report independently after one class capture") {
    withCompiledProbe: probe =>
      val handler = "external.descriptorprobe.BothMismatchHandler"
      val result = compileBindingConsumer(
        List(
          "ABothMismatch" -> "FirstMismatchTarget",
          "ZBothMismatch" -> "SecondMismatchTarget"
        ),
        handlerOptions(probe.artifact, None),
        probe.artifact
      )

      assertBindingMismatch(
        result,
        "ABothMismatch",
        handler,
        "DeclaredElsewhere",
        expectedDiagnostics = 2
      )
      assert(result.messages.mkString("\n").contains("annotation=@ZBothMismatch"))
      assertBindingReadOnce(result, handler)
      assertEquals(result.descriptorTrace.count(_.startsWith("expand ")), 0)
      assertEquals(result.invocationTrace.count(_.contains(s"handler=$handler")), 0)
  }

  test("standalone metadata mismatch is atomic with zero handler expansion and output") {
    withCompiledProbe: probe =>
      val handler = "external.descriptorprobe.StandaloneMismatchHandler"
      val result = compileBindingConsumer(
        List("StandaloneMismatch" -> "StandaloneMismatchTarget"),
        handlerOptions(probe.artifact, None),
        probe.artifact
      )

      assertBindingMismatch(
        result,
        "StandaloneMismatch",
        handler,
        "StandaloneDeclared",
        expectedDiagnostics = 1
      )
      assertBindingReadOnce(result, handler)
      assertEquals(result.descriptorTrace.count(_.startsWith("expand ")), 0)
      assertEquals(result.invocationTrace.count(_.contains(s"handler=$handler")), 0)
  }

  test("one run-scoped handler instance serves metadata and explicit multi-unit runs and resets between runs") {
    withCompiledProbe: probe =>
      val handler = "external.descriptorprobe.RunScopedStateHandler"
      val relations = List(
        "RunScopedMetadata" -> "RunScopedFirstTarget",
        "RunScopedMetadata" -> "RunScopedSecondTarget"
      )
      val metadataResult = compileMultiUnitConsumer(
        relations,
        handlerOptions(probe.artifact, None),
        probe.artifact
      )
      val explicitResult = compileMultiUnitConsumer(
        relations,
        handlerOptions(probe.artifact, Some(handler)),
        probe.artifact
      )

      assertRunScopedSuccess(metadataResult, handler, relations.map(_._2).toSet)
      assertRunScopedSuccess(explicitResult, handler, relations.map(_._2).toSet)

      val firstFreshRun = compileMultiUnitConsumer(
        List("RunScopedMetadata" -> "FreshRunFirstTarget"),
        handlerOptions(probe.artifact, None),
        probe.artifact
      )
      val secondFreshRun = compileMultiUnitConsumer(
        List("RunScopedMetadata" -> "FreshRunSecondTarget"),
        handlerOptions(probe.artifact, None),
        probe.artifact
      )

      assertRunScopedSuccess(firstFreshRun, handler, Set("FreshRunFirstTarget"))
      assertRunScopedSuccess(secondFreshRun, handler, Set("FreshRunSecondTarget"))
      assertNotEquals(runScopedInstanceIds(firstFreshRun, handler), runScopedInstanceIds(secondFreshRun, handler))
  }

  test("cross-unit relations validate independently in both orders and for repeated mismatches") {
    withCompiledProbe: probe =>
      val matchThenMismatchHandler = "external.descriptorprobe.MatchThenMismatchHandler"
      val matchThenMismatch = compileMultiUnitConsumer(
        List(
          "AMatchFirst" -> "CrossUnitMatchFirstTarget",
          "ZMismatchSecond" -> "CrossUnitMismatchSecondTarget"
        ),
        handlerOptions(probe.artifact, None),
        probe.artifact
      )
      assertBindingMismatch(
        matchThenMismatch,
        "ZMismatchSecond",
        matchThenMismatchHandler,
        "AMatchFirst",
        expectedDiagnostics = 1
      )
      assertBindingReadOnce(matchThenMismatch, matchThenMismatchHandler)
      assertEquals(matchThenMismatch.descriptorTrace.count(_ == s"expand handler=$matchThenMismatchHandler class=CrossUnitMatchFirstTarget"), 1)
      assertEquals(matchThenMismatch.descriptorTrace.count(_.contains("class=CrossUnitMismatchSecondTarget")), 0)

      val mismatchThenMatchHandler = "external.descriptorprobe.MismatchThenMatchHandler"
      val mismatchThenMatch = compileMultiUnitConsumer(
        List(
          "AMismatchFirst" -> "CrossUnitMismatchFirstTarget",
          "ZMatchSecond" -> "CrossUnitMatchSecondTarget"
        ),
        handlerOptions(probe.artifact, None),
        probe.artifact
      )
      assertBindingMismatch(
        mismatchThenMatch,
        "AMismatchFirst",
        mismatchThenMatchHandler,
        "ZMatchSecond",
        expectedDiagnostics = 1
      )
      assertBindingReadOnce(mismatchThenMatch, mismatchThenMatchHandler)
      assertEquals(mismatchThenMatch.descriptorTrace.count(_ == s"expand handler=$mismatchThenMatchHandler class=CrossUnitMatchSecondTarget"), 1)
      assertEquals(mismatchThenMatch.descriptorTrace.count(_.contains("class=CrossUnitMismatchFirstTarget")), 0)

      val bothMismatchHandler = "external.descriptorprobe.BothMismatchHandler"
      val bothMismatch = compileMultiUnitConsumer(
        List(
          "ABothMismatch" -> "CrossUnitFirstMismatchTarget",
          "ZBothMismatch" -> "CrossUnitSecondMismatchTarget"
        ),
        handlerOptions(probe.artifact, None),
        probe.artifact
      )
      assertBindingMismatch(
        bothMismatch,
        "ABothMismatch",
        bothMismatchHandler,
        "DeclaredElsewhere",
        expectedDiagnostics = 2
      )
      assert(bothMismatch.messages.mkString("\n").contains("annotation=@ZBothMismatch"))
      assertBindingReadOnce(bothMismatch, bothMismatchHandler)
      assertEquals(bothMismatch.descriptorTrace.count(_.startsWith("expand ")), 0)
  }

  test("failed metadata handler capture is cached within one run and retried in a fresh run") {
    withCompiledProbe: probe =>
      val handler = "external.descriptorprobe.RunScopedFailureHandler"
      val relations = List(
        "RunScopedFailure" -> "RunFailureFirstTarget",
        "RunScopedFailure" -> "RunFailureSecondTarget"
      )
      val firstRun = compileMultiUnitConsumer(
        relations,
        handlerOptions(probe.artifact, None),
        probe.artifact
      )
      val secondRun = compileMultiUnitConsumer(
        relations,
        handlerOptions(probe.artifact, None),
        probe.artifact
      )

      List(firstRun, secondRun).foreach: result =>
        assertControlledFailure(
          result,
          handler,
          "HANDLER_DECLARATION_FAILURE",
          "targetProfile",
          "java.lang.IllegalStateException",
          "run scoped descriptor failure",
          expectedRecoveryInvocations = 2
        )
        assertEquals(result.descriptorTrace.count(_ == s"construct handler=$handler"), 1)
        assertEquals(result.descriptorTrace.count(_ == s"annotationName handler=$handler read=1"), 1)
        assertEquals(result.descriptorTrace.count(_ == s"targetProfile handler=$handler read=1"), 1)
        assertEquals(result.descriptorTrace.count(_.contains(s"consumesExistingCompanion handler=$handler")), 0)
        assertEquals(
          result.messages.mkString("\n").split("category=HANDLER_DECLARATION_FAILURE", -1).length - 1,
          1
        )
        assertEquals(
          result.metadataTrace.count(_.startsWith("runtime-candidate external.descriptorprobe.RunScopedFailure ")),
          2
        )

      assertNotEquals(runScopedInstanceIds(firstRun, handler), runScopedInstanceIds(secondRun, handler))
  }

  private def assertRunScopedSuccess(
      result: CompileResult,
      handler: String,
      expectedClasses: Set[String]
  ): Unit =
    assert(!result.hasErrors, result.messages.mkString("\n"))
    expectedClasses.foreach: className =>
      assert(result.outputFiles.exists(_.endsWith(s"$className.class")), result.outputFiles.mkString(","))
      assert(result.outputFiles.exists(_.endsWith(s"$className.tasty")), result.outputFiles.mkString(","))
    assertBindingReadOnce(result, handler)
    assertEquals(runScopedInstanceIds(result, handler).size, 1)
    val states = result.descriptorTrace.collect:
      case line if line.startsWith(s"expandState handler=$handler ") =>
        val fields = line.split(' ').iterator.map(_.split("=", 2)).collect:
          case Array(name, value) => name -> value
        .toMap
        (fields("instance"), fields("ordinal").toInt, fields("class"))
    assertEquals(states.size, expectedClasses.size)
    assertEquals(states.map(_._1).toSet, runScopedInstanceIds(result, handler))
    assertEquals(states.map(_._2).toSet, (1 to expectedClasses.size).toSet)
    assertEquals(states.map(_._3).toSet, expectedClasses)
    assertEquals(result.invocationTrace.count(_.contains(s"handler=$handler")), expectedClasses.size)

  private def runScopedInstanceIds(result: CompileResult, handler: String): Set[String] =
    result.descriptorTrace.collect:
      case line if line.startsWith(s"instance handler=$handler id=") => line.stripPrefix(s"instance handler=$handler id=")
    .toSet

  private def assertSuccessfulReadOnce(
      result: CompileResult,
      handler: String,
      className: String
  ): Unit =
    assert(!result.hasErrors, result.messages.mkString("\n"))
    assert(result.outputFiles.exists(_.endsWith(s"$className.class")), result.outputFiles.mkString(","))
    assert(result.outputFiles.exists(_.endsWith(s"${className}$$.class")), result.outputFiles.mkString(","))
    assertEquals(result.descriptorTrace.count(_ == s"annotationName handler=$handler read=1"), 1)
    assertEquals(result.descriptorTrace.count(_ == s"targetProfile handler=$handler read=1"), 1)
    assertEquals(result.descriptorTrace.count(_ == s"compositionPolicy handler=$handler read=1"), 1)
    assertEquals(result.descriptorTrace.count(_ == s"consumesExistingCompanion handler=$handler read=1"), 1)
    assert(!result.descriptorTrace.exists(_.contains("read=2")), result.descriptorTrace.mkString("\n"))
    assertEquals(
      result.descriptorTrace.count(_ == s"expand handler=$handler existingCompanion=true"),
      1
    )
    assertEquals(result.invocationTrace.count(_.contains(s"handler=$handler")), 1)

  private def assertControlledFailure(
      result: CompileResult,
      handler: String,
      category: String,
      accessor: String,
      cause: String,
      detail: String,
      expectedRecoveryInvocations: Int = 0
  ): Unit =
    val messages = result.messages.mkString("\n")
    assert(result.hasErrors, messages)
    List(
      "stage=loading",
      s"category=$category",
      s"handler=$handler",
      s"accessor=$accessor",
      "loaderPolicy=parent-first",
      "requestedLoader=",
      "handlerLoader=",
      detail
    ).foreach(fragment => assert(messages.contains(fragment), messages))
    if cause.nonEmpty then assert(messages.contains(s"cause=$cause"), messages)
    List("internal compiler error", "ClassCastException", "MatchError", "exception occurred while typechecking")
      .foreach(fragment => assert(!messages.contains(fragment), messages))
    assertEquals(result.outputFiles, Nil)
    assertEquals(result.descriptorTrace.count(_.startsWith("expand ")), 0)
    if expectedRecoveryInvocations == 0 then
      assertEquals(result.invocationTrace, Nil)
    else
      assertEquals(result.invocationTrace.size, expectedRecoveryInvocations)
      assertEquals(
        result.invocationTrace.count(_.contains("handler=macroparadise.ExternalHandlerLoading$InvalidMetadataAnnotationExpander")),
        expectedRecoveryInvocations
      )
    assertEquals(result.invocationTrace.count(_.contains(s"handler=$handler")), 0)

  private def assertBindingReadOnce(
      result: CompileResult,
      handler: String
  ): Unit =
    assertEquals(result.descriptorTrace.count(_ == s"construct handler=$handler"), 1)
    assertEquals(result.descriptorTrace.count(_ == s"annotationName handler=$handler read=1"), 1)
    assertEquals(result.descriptorTrace.count(_ == s"targetProfile handler=$handler read=1"), 1)
    assertEquals(result.descriptorTrace.count(_ == s"compositionPolicy handler=$handler read=1"), 1)
    assertEquals(result.descriptorTrace.count(_ == s"consumesExistingCompanion handler=$handler read=1"), 1)
    assert(!result.descriptorTrace.exists(line => line.contains(s"handler=$handler") && line.contains("read=2")), result.descriptorTrace.mkString("\n"))

  private def assertBindingMismatch(
      result: CompileResult,
      metadataAnnotation: String,
      handler: String,
      declaredAnnotation: String,
      expectedDiagnostics: Int
  ): Unit =
    val messages = result.messages.mkString("\n")
    assert(result.hasErrors, messages)
    List(
      "stage=loading",
      "category=METADATA_HANDLER_ANNOTATION_MISMATCH",
      s"annotation=@$metadataAnnotation",
      s"metadataHandler=$handler",
      s"declaredAnnotation=@$declaredAnnotation",
      "loaderPolicy=parent-first",
      "requestedLoader=",
      "captured descriptor declares"
    ).foreach(fragment => assert(messages.contains(fragment), messages))
    assertEquals(
      messages.split("category=METADATA_HANDLER_ANNOTATION_MISMATCH", -1).length - 1,
      expectedDiagnostics
    )
    List("internal compiler error", "ClassCastException", "MatchError", "exception occurred while typechecking")
      .foreach(fragment => assert(!messages.contains(fragment), messages))
    assertEquals(result.outputFiles, Nil)

  private def withCompiledProbe(body: CompiledProbe => Unit): Unit =
    val root = Files.createTempDirectory("independent-descriptor-probe-")
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
      val artifact = root.resolve("independent-descriptor-handlers.jar").toFile
      writeJar(classes, artifact)
      body(CompiledProbe(root, artifact))
    finally deleteRecursively(root)

  private def handlerOptions(artifact: File, explicit: Option[String]): List[String] =
    List(s"-P:macroparadise:handlerClasspath=${artifact.getAbsolutePath}") ++
      explicit.toList.map(name => s"-P:macroparadise:handler=$name")

  private def compileConsumer(
      marker: String,
      pluginOptions: List[String],
      artifact: File,
      className: String = "ExplicitTarget",
      target: String = "trait"
  ): CompileResult =
    val root = artifact.toPath.getParent.resolve(s"consumer-${java.util.UUID.randomUUID()}")
    val sourceFile = root.resolve("Consumer.scala")
    val output = root.resolve("classes")
    val descriptorTrace = root.resolve("descriptor.trace")
    val invocationTrace = root.resolve("invocation.trace")
    val metadataTrace = root.resolve("metadata.trace")
    Files.createDirectories(output)
    val typeParameters = if target == "trait" then "[A]" else ""
    Files.writeString(
      sourceFile,
      s"""package external.descriptorconsumer
         |import external.descriptorprobe.$marker
         |@$marker $target $className$typeParameters
         |object $className { val kept: Int = 1 }
         |""".stripMargin
    )
    val (exitCode, messages) = runCompiler(
      List(
        "-classpath",
        (artifact :: pluginApiJar :: compilerUniverse).map(_.getAbsolutePath).distinct.mkString(File.pathSeparator),
        "-d",
        output.toString,
        s"-Xplugin:${pluginJar.getAbsolutePath}",
        "-Xplugin-require:macroparadise"
      ) ++ pluginOptions ++ List(
        s"-P:macroparadise:externalHandlerInvocationTrace=$invocationTrace",
        s"-P:macroparadise:metadataReaderTrace=$metadataTrace",
        sourceFile.toString
      ),
      List(
        "macroparadise.descriptorProbeTrace" -> descriptorTrace.toString
      )
    )
    CompileResult(
      exitCode != 0,
      messages,
      outputFiles(output),
      readLines(descriptorTrace),
      readLines(invocationTrace),
      readLines(metadataTrace)
    )

  private def compileBindingConsumer(
      markersAndTargets: List[(String, String)],
      pluginOptions: List[String],
      artifact: File
  ): CompileResult =
    val root = artifact.toPath.getParent.resolve(s"binding-consumer-${java.util.UUID.randomUUID()}")
    val sourceFile = root.resolve("Consumer.scala")
    val output = root.resolve("classes")
    val descriptorTrace = root.resolve("descriptor.trace")
    val invocationTrace = root.resolve("invocation.trace")
    val metadataTrace = root.resolve("metadata.trace")
    Files.createDirectories(output)
    val markerImports = markersAndTargets.map(_._1).distinct.sorted.mkString("{", ", ", "}")
    val definitions = markersAndTargets.map: (marker, target) =>
      s"@$marker class $target"
    Files.writeString(
      sourceFile,
      s"""package external.bindingconsumer
         |import external.descriptorprobe.$markerImports
         |${definitions.mkString("\n")}
         |""".stripMargin
    )
    val (exitCode, messages) = runCompiler(
      List(
        "-classpath",
        (artifact :: pluginApiJar :: compilerUniverse).map(_.getAbsolutePath).distinct.mkString(File.pathSeparator),
        "-d",
        output.toString,
        s"-Xplugin:${pluginJar.getAbsolutePath}",
        "-Xplugin-require:macroparadise"
      ) ++ pluginOptions ++ List(
        s"-P:macroparadise:externalHandlerInvocationTrace=$invocationTrace",
        s"-P:macroparadise:metadataReaderTrace=$metadataTrace",
        sourceFile.toString
      ),
      List(
        "macroparadise.descriptorProbeTrace" -> descriptorTrace.toString
      )
    )
    CompileResult(
      exitCode != 0,
      messages,
      outputFiles(output),
      readLines(descriptorTrace),
      readLines(invocationTrace),
      readLines(metadataTrace)
    )

  private def compileMultiUnitConsumer(
      markersAndTargets: List[(String, String)],
      pluginOptions: List[String],
      artifact: File
  ): CompileResult =
    val root = artifact.toPath.getParent.resolve(s"multi-unit-consumer-${java.util.UUID.randomUUID()}")
    val sources = root.resolve("sources")
    val output = root.resolve("classes")
    val descriptorTrace = root.resolve("descriptor.trace")
    val invocationTrace = root.resolve("invocation.trace")
    val metadataTrace = root.resolve("metadata.trace")
    Files.createDirectories(sources)
    Files.createDirectories(output)
    val sourceFiles = markersAndTargets.zipWithIndex.map:
      case ((marker, target), index) =>
        val sourceFile = sources.resolve(f"Unit$index%02d.scala")
        Files.writeString(
          sourceFile,
          s"""package external.multiunitconsumer
             |import external.descriptorprobe.$marker
             |@$marker class $target
             |""".stripMargin
        )
        sourceFile
    val (exitCode, messages) = runCompiler(
      List(
        "-classpath",
        (artifact :: pluginApiJar :: compilerUniverse).map(_.getAbsolutePath).distinct.mkString(File.pathSeparator),
        "-d",
        output.toString,
        s"-Xplugin:${pluginJar.getAbsolutePath}",
        "-Xplugin-require:macroparadise"
      ) ++ pluginOptions ++ List(
        s"-P:macroparadise:externalHandlerInvocationTrace=$invocationTrace",
        s"-P:macroparadise:metadataReaderTrace=$metadataTrace"
      ) ++ sourceFiles.map(_.toString),
      List(
        "macroparadise.descriptorProbeTrace" -> descriptorTrace.toString
      )
    )
    CompileResult(
      exitCode != 0,
      messages,
      outputFiles(output),
      readLines(descriptorTrace),
      readLines(invocationTrace),
      readLines(metadataTrace)
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
    val exitCode = Process(command, new File(".")).!(ProcessLogger(messages += _, messages += _))
    exitCode -> messages.toList

  private def outputFiles(root: Path): List[String] =
    val stream = Files.walk(root)
    try stream.iterator().asScala.filter(Files.isRegularFile(_)).map(root.relativize(_).toString.replace(File.separatorChar, '/')).toList.sorted
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
      files.foreach: source =>
        val entry = JarEntry(classes.relativize(source).toString.replace(File.separatorChar, '/'))
        entry.setTime(0L)
        output.putNextEntry(entry)
        output.write(Files.readAllBytes(source))
        output.closeEntry()
    finally output.close()

  private def jarEntries(artifact: File): List[String] =
    val jar = java.util.jar.JarFile(artifact)
    try jar.entries().asScala.map(_.getName).toList
    finally jar.close()

  private def file(path: String): File = new File(path).getAbsoluteFile

  private def codeSourcePath(clazz: Class[?]): File =
    new File(clazz.getProtectionDomain.getCodeSource.getLocation.toURI).getAbsoluteFile

  private def javaTool: String =
    new File(new File(System.getProperty("java.home"), "bin"), "java").getAbsolutePath

  private def deleteRecursively(root: Path): Unit =
    if Files.exists(root) then
      val stream = Files.walk(root)
      try stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.delete)
      finally stream.close()
