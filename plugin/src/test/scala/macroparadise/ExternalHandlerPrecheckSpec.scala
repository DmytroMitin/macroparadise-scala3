package macroparadise

import java.nio.file.Path
import java.nio.file.{Files, StandardOpenOption}
import java.util.jar.{JarEntry, JarOutputStream}

class ExternalHandlerPrecheckSpec extends munit.FunSuite:
  import ExternalHandlerPrecheck.*

  test("malformed declared annotation fails through the canonical production authority") {
    val failure = failed(validateDeclaredAnnotation("starter.marker..generateGreeting"))

    assertEquals(failure.category, "INVALID_HANDLER_ANNOTATION_NAME")
    assert(failure.detail.contains("canonical simple or dot-qualified"), failure.detail)
  }

  test("qualified declared annotation is preserved without final-segment reduction") {
    assertEquals(
      validateDeclaredAnnotation("starter.marker.generateGreeting"),
      Right("starter.marker.generateGreeting")
    )
  }

  test("compiler mismatch fails before artifact and handler work") {
    val failure = failed(
      validateEnvironment(
        Environment(
          expectedScalaVersion = "3.8.4",
          actualScalaVersion = "3.8.5-RC1-bin-20260405-9478256-NIGHTLY",
          expectedJdkMajor = 25,
          actualJdkMajor = 25
        )
      )
    )

    assertEquals(failure.category, "EXACT_COMPILER_MISMATCH")
  }

  test("JDK mismatch fails before artifact and handler work") {
    val failure = failed(
      validateEnvironment(
        Environment(
          expectedScalaVersion = "3.8.4",
          actualScalaVersion = "3.8.4",
          expectedJdkMajor = 25,
          actualJdkMajor = 26
        )
      )
    )

    assertEquals(failure.category, "EXACT_JDK_MISMATCH")
  }

  test("marker metadata must select the exact configured handler class") {
    val failure = failed(
      validateMetadataSelection(
        markerIdentity = "starter.marker.generateGreeting",
        metadataHandlerClass = "starter.handler.GenerateGreetingHandler",
        expectedAnnotation = "starter.marker.generateGreeting",
        expectedHandlerClass = "starter.handler.OtherHandler"
      )
    )

    assertEquals(failure.category, "METADATA_HANDLER_CLASS_MISMATCH")
    assert(failure.detail.contains("starter.handler.GenerateGreetingHandler"), failure.detail)
    assert(failure.detail.contains("starter.handler.OtherHandler"), failure.detail)
  }

  test("handler compile linkage rejects the production plugin artifact") {
    val pluginApi = Path.of("/artifacts/plugin-api.jar")
    val plugin = Path.of("/artifacts/plugin.jar")
    val failure = failed(
      validateHandlerCompileClasspath(
        pluginApi,
        plugin,
        Vector(
          ClasspathArtifact(
            pluginApi,
            Set("paradise3/api/ParadiseAnnotationExpander.class"),
            Set.empty
          ),
          ClasspathArtifact(
            plugin,
            Set("macroparadise/MacroParadisePlugin.class"),
            Set("macroparadise/MacroParadisePlugin")
          )
        )
      )
    )

    assertEquals(failure.category, "FORBIDDEN_HANDLER_DEPENDENCY")
    assert(failure.detail.contains("plugin.jar"), failure.detail)
  }

  test("handler compile linkage accepts only contract and exact compiler/runtime evidence") {
    val pluginApi = Path.of("/artifacts/plugin-api.jar")
    val plugin = Path.of("/artifacts/plugin.jar")
    val compiler = Path.of("/artifacts/scala3-compiler.jar")

    assertEquals(
      validateHandlerCompileClasspath(
        pluginApi,
        plugin,
        Vector(
          ClasspathArtifact(
            pluginApi,
            Set("paradise3/api/ParadiseAnnotationExpander.class"),
            Set.empty
          ),
          ClasspathArtifact(
            compiler,
            Set("dotty/tools/dotc/Main.class"),
            Set("dotty/tools/dotc/ast/untpd")
          )
        )
      ),
      Right(())
    )
  }

  test("artifact roles reject a marker artifact in the handler slot") {
    val root = Files.createTempDirectory("external-handler-precheck-role-")
    try
      val marker = jar(root.resolve("marker.jar"), Map(
        "starter/precheckfixtures/ValidMarker.class" -> Array[Byte](1)
      ))
      val failure = failed(
        validateArtifactRoles(
          ArtifactPaths(
            plugin = jar(root.resolve("plugin.jar"), Map(
              "macroparadise/MacroParadisePlugin.class" -> Array[Byte](1),
              "macroparadise/ExternalHandlerPrecheckMain.class" -> Array[Byte](1),
              "plugin.properties" -> Array[Byte](1)
            )),
            pluginApi = jar(root.resolve("plugin-api.jar"), Map(
              "paradise3/api/ParadiseAnnotationExpander.class" -> Array[Byte](1),
              "paradise3/api/expander.class" -> Array[Byte](1)
            )),
            marker = marker,
            handler = marker
          ),
          markerClassName = "starter.precheckfixtures.ValidMarker",
          handlerClassName = "starter.precheckfixtures.ValidHandler"
        )
      )

      assertEquals(failure.category, "WRONG_ARTIFACT_ROLE")
    finally deleteRecursively(root)
  }

  test("packaged declaration and binding precheck succeeds without expansion") {
    val root = Files.createTempDirectory("external-handler-precheck-packaged-")
    val expansionTrace = root.resolve("expand.trace")
    val property = "macroparadise.precheck.expandTrace"
    val previous = Option(System.getProperty(property))
    try
      System.setProperty(property, expansionTrace.toString)
      val plugin = jar(root.resolve("plugin.jar"), Map(
        "macroparadise/MacroParadisePlugin.class" -> Array[Byte](1),
        "macroparadise/ExternalHandlerPrecheckMain.class" -> Array[Byte](1),
        "plugin.properties" -> Array[Byte](1)
      ))
      val pluginApi = jar(root.resolve("plugin-api.jar"), Map(
        "paradise3/api/ParadiseAnnotationExpander.class" -> Array[Byte](1),
        "paradise3/api/expander.class" -> Array[Byte](1)
      ))
      val marker = jarFromResources(
        root.resolve("marker.jar"),
        List("starter/precheckfixtures/ValidMarker.class")
      )
      val handler = jarFromResources(
        root.resolve("handler.jar"),
        List("starter/precheckfixtures/ValidHandler.class")
      )
      val compiler = jar(root.resolve("scala3-compiler.jar"), Map(
        "dotty/tools/dotc/Main.class" -> Array[Byte](1)
      ))

      val success = ExternalHandlerPrecheck.run(
        Request(
          artifacts = ArtifactPaths(plugin, pluginApi, marker, handler),
          handlerCompileClasspath = Vector(pluginApi, compiler),
          markerClassName = "starter.precheckfixtures.ValidMarker",
          expectedHandlerClassName = "starter.precheckfixtures.ValidHandler",
          expectedAnnotationName = "starter.precheckfixtures.ValidMarker",
          environment = Environment(
            expectedScalaVersion = "3.8.4",
            actualScalaVersion = "3.8.4",
            expectedJdkMajor = 25,
            actualJdkMajor = 25
          ),
          parentLoader = getClass.getClassLoader
        )
      ) match
        case Right(value) => value
        case Left(failure) => fail(failure.render)

      assertEquals(success.annotationName, "starter.precheckfixtures.ValidMarker")
      assertEquals(success.handlerClassName, "starter.precheckfixtures.ValidHandler")
      assert(success.parentFirstContractIdentity)
      assert(!Files.exists(expansionTrace), "declaration precheck invoked expand")
    finally
      previous match
        case Some(value) => System.setProperty(property, value)
        case None => System.clearProperty(property)
      deleteRecursively(root)
  }

  test("missing metadata handler names the selecting marker expectations and supplied artifact") {
    val failure = packagedFailure(
      markerClassName = "starter.precheckfixtures.MissingHandlerMarker",
      markerResource = "starter/precheckfixtures/MissingHandlerMarker.class",
      expectedHandlerClassName = "starter.precheckfixtures.DoesNotExist",
      expectedAnnotationName = "starter.precheckfixtures.MissingHandlerMarker",
      handlerResources = List("starter/precheckfixtures/NotAHandler.class")
    )

    assertEquals(failure.category, "HANDLER_CLASS_LOADING_FAILURE")
    Vector(
      "failureStage=handler-artifact",
      "markerIdentity=starter.precheckfixtures.MissingHandlerMarker",
      "expectedAnnotation=starter.precheckfixtures.MissingHandlerMarker",
      "metadataHandler=starter.precheckfixtures.DoesNotExist",
      "expectedHandler=starter.precheckfixtures.DoesNotExist",
      "markerArtifact=",
      "handlerArtifact=",
      "does not contain expected handler class"
    ).foreach(fragment => assert(failure.detail.contains(fragment), failure.detail))
  }

  test("non-handler metadata target retains full authoring context at contract failure") {
    val failure = packagedFailure(
      markerClassName = "starter.precheckfixtures.InvalidContractMarker",
      markerResource = "starter/precheckfixtures/InvalidContractMarker.class",
      expectedHandlerClassName = "starter.precheckfixtures.NotAHandler",
      expectedAnnotationName = "starter.precheckfixtures.InvalidContractMarker",
      handlerResources = List("starter/precheckfixtures/NotAHandler.class")
    )

    assertEquals(failure.category, "HANDLER_CONTRACT_IDENTITY_FAILURE")
    Vector(
      "failureStage=handler-contract",
      "markerIdentity=starter.precheckfixtures.InvalidContractMarker",
      "expectedAnnotation=starter.precheckfixtures.InvalidContractMarker",
      "metadataHandler=starter.precheckfixtures.NotAHandler",
      "expectedHandler=starter.precheckfixtures.NotAHandler",
      "handlerArtifact="
    ).foreach(fragment => assert(failure.detail.contains(fragment), failure.detail))
  }

  test("descriptor mismatch exposes deterministic independent binding facts") {
    val failure = packagedFailure(
      markerClassName = "starter.precheckfixtures.BindingMismatchMarker",
      markerResource = "starter/precheckfixtures/BindingMismatchMarker.class",
      expectedHandlerClassName = "starter.precheckfixtures.BindingMismatchHandler",
      expectedAnnotationName = "starter.precheckfixtures.BindingMismatchMarker",
      handlerResources = List("starter/precheckfixtures/BindingMismatchHandler.class")
    )

    assertEquals(failure.category, "METADATA_HANDLER_ANNOTATION_MISMATCH")
    Vector(
      "failureStage=metadata-binding",
      "markerIdentity=starter.precheckfixtures.BindingMismatchMarker",
      "expectedAnnotation=starter.precheckfixtures.BindingMismatchMarker",
      "metadataHandler=starter.precheckfixtures.BindingMismatchHandler",
      "expectedHandler=starter.precheckfixtures.BindingMismatchHandler",
      "declaredAnnotation=starter.precheckfixtures.OtherMarker",
      "markerArtifact=",
      "handlerArtifact="
    ).foreach(fragment => assert(failure.detail.contains(fragment), failure.detail))
    assert(!failure.detail.contains("requestedLoader="), failure.detail)
  }

  test("whitespace marker metadata has a specific syntax failure with authoring context") {
    val failure = packagedFailure(
      markerClassName = "starter.precheckfixtures.WhitespaceMetadataMarker",
      markerResource = "starter/precheckfixtures/WhitespaceMetadataMarker.class",
      expectedHandlerClassName = "   ",
      expectedAnnotationName = "starter.precheckfixtures.WhitespaceMetadataMarker",
      handlerResources = List("starter/precheckfixtures/NotAHandler.class")
    )

    assertEquals(failure.category, "INVALID_METADATA_HANDLER_CLASS_NAME")
    Vector(
      "failureStage=metadata-selection",
      "markerIdentity=starter.precheckfixtures.WhitespaceMetadataMarker",
      "metadataHandler=<whitespace>",
      "expectedHandler=<whitespace>",
      "expected a canonical simple or dot-qualified handler class name"
    ).foreach(fragment => assert(failure.detail.contains(fragment), failure.detail))
  }

  test("precheck help names every required role and the preconsumer boundary") {
    val usage = ExternalHandlerPrecheckMain.usage

    Vector(
      "--plugin=<plugin.jar>",
      "--plugin-api=<plugin-api.jar>",
      "--marker=<marker.jar>",
      "--handler=<handler.jar>",
      "--handler-compile-classpath=<path-list>",
      "--marker-class=<qualified-marker-class>",
      "--expected-handler-class=<qualified-handler-class>",
      "--expected-annotation=<qualified-annotation-name>",
      "--expected-scala-version=<exact-version>",
      "--expected-jdk-major=<major>"
    ).foreach(option => assert(usage.contains(option), usage))
    assert(usage.contains("consumerCompilationStarted=false"), usage)
    assert(usage.contains("expansionInvoked=false"), usage)
    Vector(
      "failureStage",
      "markerIdentity",
      "expectedAnnotation",
      "metadataHandler",
      "expectedHandler",
      "markerArtifact",
      "handlerArtifact"
    ).foreach(field => assert(usage.contains(field), usage))
    assert(ExternalHandlerPrecheckMain.helpRequested(Array("--help")))
    assert(!ExternalHandlerPrecheckMain.helpRequested(Array.empty[String]))
    assert(!ExternalHandlerPrecheckMain.helpRequested(Array("--help=true")))
  }

  test("precheck help presents additive compact mode and names each derived witness") {
    val usage = ExternalHandlerPrecheckMain.usage

    assert(usage.contains("--compact"), usage)
    assert(usage.contains("plugin: executing ExternalHandlerPrecheckMain code source"), usage)
    assert(usage.contains("plugin-api: parent-loaded ParadiseAnnotationExpander code source"), usage)
    assert(usage.contains("marker-class: canonical expected-annotation identity"), usage)
  }

  test("precheck argument failure output identifies the stopped stage before usage") {
    val rendered = ExternalHandlerPrecheckMain.renderFailure(
      Failure("PRECHECK_ARGUMENT_FAILURE", "missing required argument --plugin")
    )

    assert(rendered.startsWith("EXTERNAL_HANDLER_AUTHORING_PRECHECK_FAILED stage=preconsumer"), rendered)
    assert(rendered.contains("consumerCompilationStarted=false"), rendered)
    assert(rendered.contains("expansionInvoked=false"), rendered)
    assert(rendered.contains("category=PRECHECK_ARGUMENT_FAILURE"), rendered)
    assert(rendered.contains("Usage:"), rendered)
  }

  test("precheck command rejects a missing required artifact argument") {
    val failure = failed(
      ExternalHandlerPrecheckMain.parse(
        Array(
          "--plugin=/artifacts/plugin.jar",
          "--plugin-api=/artifacts/plugin-api.jar"
        ),
        getClass.getClassLoader,
        actualScalaVersion = "3.8.4",
        actualJdkMajor = 25
      )
    )

    assertEquals(failure.category, "PRECHECK_ARGUMENT_FAILURE")
    assert(failure.detail.contains("--marker"), failure.detail)
  }

  test("precheck command parses explicit artifact paths and handler compile classpath") {
    val separator = java.io.File.pathSeparator
    val request = ExternalHandlerPrecheckMain.parse(
      Array(
        "--plugin=/artifacts/plugin.jar",
        "--plugin-api=/artifacts/plugin-api.jar",
        "--marker=/artifacts/marker.jar",
        "--handler=/artifacts/handler.jar",
        s"--handler-compile-classpath=/artifacts/plugin-api.jar${separator}/artifacts/compiler.jar",
        "--marker-class=starter.marker.generateGreeting",
        "--expected-handler-class=starter.handler.GenerateGreetingHandler",
        "--expected-annotation=starter.marker.generateGreeting",
        "--expected-scala-version=3.8.4",
        "--expected-jdk-major=25"
      ),
      getClass.getClassLoader,
      actualScalaVersion = "3.8.4",
      actualJdkMajor = 25
    ) match
      case Right(value) => value
      case Left(failure) => fail(failure.render)

    assertEquals(request.artifacts.marker, Path.of("/artifacts/marker.jar"))
    assertEquals(
      request.handlerCompileClasspath,
      Vector(Path.of("/artifacts/plugin-api.jar"), Path.of("/artifacts/compiler.jar"))
    )
    assertEquals(request.environment.actualJdkMajor, 25)
  }

  test("compact command derives runtime artifacts and marker class without dropping independent expectations") {
    val separator = java.io.File.pathSeparator
    val request = ExternalHandlerPrecheckMain.parseCompact(
      Array(
        "--compact",
        "--marker=/artifacts/marker.jar",
        "--handler=/artifacts/handler.jar",
        s"--handler-compile-classpath=/runtime/plugin-api.jar${separator}/artifacts/compiler.jar",
        "--expected-handler-class=starter.handler.GenerateGreetingHandler",
        "--expected-annotation=starter.marker.generateGreeting",
        "--expected-scala-version=3.8.4",
        "--expected-jdk-major=25"
      ),
      getClass.getClassLoader,
      actualScalaVersion = "3.8.4",
      actualJdkMajor = 25,
      ExternalHandlerPrecheckMain.RuntimeArtifacts(
        plugin = Path.of("/runtime/plugin.jar"),
        pluginApi = Path.of("/runtime/plugin-api.jar")
      )
    ) match
      case Right(value) => value
      case Left(failure) => fail(failure.render)

    assertEquals(request.artifacts.plugin, Path.of("/runtime/plugin.jar"))
    assertEquals(request.artifacts.pluginApi, Path.of("/runtime/plugin-api.jar"))
    assertEquals(request.markerClassName, "starter.marker.generateGreeting")
    assertEquals(request.expectedHandlerClassName, "starter.handler.GenerateGreetingHandler")
    assertEquals(request.environment.expectedJdkMajor, 25)
  }

  test("compact runtime derivation rejects an unpackaged classes directory") {
    val failure = failed(
      ExternalHandlerPrecheckMain.artifactPathFromCodeSource(
        getClass,
        role = "plugin"
      )
    )

    assertEquals(failure.category, "COMPACT_PRECHECK_DERIVATION_FAILURE")
    assert(failure.detail.contains("regular JAR file"), failure.detail)
  }

  test("compact derived plugin API is checked against independent handler compile evidence") {
    val root = Files.createTempDirectory("external-handler-precheck-compact-api-")
    try
      val plugin = jar(root.resolve("plugin.jar"), Map(
        "macroparadise/MacroParadisePlugin.class" -> Array[Byte](1),
        "macroparadise/ExternalHandlerPrecheckMain.class" -> Array[Byte](1),
        "plugin.properties" -> Array[Byte](1)
      ))
      val runtimeApi = jar(root.resolve("runtime-api.jar"), Map(
        "paradise3/api/ParadiseAnnotationExpander.class" -> Array[Byte](1),
        "paradise3/api/expander.class" -> Array[Byte](1)
      ))
      val echoedApi = jar(root.resolve("echoed-api.jar"), Map(
        "paradise3/api/ParadiseAnnotationExpander.class" -> Array[Byte](1),
        "paradise3/api/expander.class" -> Array[Byte](1)
      ))
      val marker = jar(root.resolve("marker.jar"), Map(
        "starter/marker/generateGreeting.class" -> Array[Byte](1)
      ))
      val handler = jar(root.resolve("handler.jar"), Map(
        "starter/handler/GenerateGreetingHandler.class" -> Array[Byte](1)
      ))
      val compiler = jar(root.resolve("compiler.jar"), Map(
        "dotty/tools/dotc/Main.class" -> Array[Byte](1)
      ))
      val separator = java.io.File.pathSeparator
      val request = ExternalHandlerPrecheckMain.parseCompact(
        Array(
          "--compact",
          s"--marker=$marker",
          s"--handler=$handler",
          s"--handler-compile-classpath=$echoedApi${separator}$compiler",
          "--expected-handler-class=starter.handler.GenerateGreetingHandler",
          "--expected-annotation=starter.marker.generateGreeting",
          "--expected-scala-version=3.8.4",
          "--expected-jdk-major=25"
        ),
        getClass.getClassLoader,
        actualScalaVersion = "3.8.4",
        actualJdkMajor = 25,
        ExternalHandlerPrecheckMain.RuntimeArtifacts(plugin, runtimeApi)
      ) match
        case Right(value) => value
        case Left(failure) => fail(failure.render)

      val failure = failed(ExternalHandlerPrecheck.run(request))
      assertEquals(failure.category, "HANDLER_CONTRACT_CLASSPATH_MISMATCH")
      assert(failure.detail.contains("found 0"), failure.detail)
    finally deleteRecursively(root)
  }

  test("compact self-contained plugin selects the ordinary authoring API from handler compile evidence") {
    val root = Files.createTempDirectory("external-handler-precheck-compact-embedded-api-")
    try
      val plugin = jar(root.resolve("plugin.jar"), Map(
        "macroparadise/MacroParadisePlugin.class" -> Array[Byte](1),
        "macroparadise/ExternalHandlerPrecheckMain.class" -> Array[Byte](1),
        "paradise3/api/ParadiseAnnotationExpander.class" -> Array[Byte](1),
        "paradise3/api/expander.class" -> Array[Byte](1),
        "plugin.properties" -> Array[Byte](1)
      ))
      val authoringApi = jar(root.resolve("plugin-api.jar"), Map(
        "paradise3/api/ParadiseAnnotationExpander.class" -> Array[Byte](1),
        "paradise3/api/expander.class" -> Array[Byte](1)
      ))
      val compiler = jar(root.resolve("compiler.jar"), Map(
        "dotty/tools/dotc/Main.class" -> Array[Byte](1)
      ))

      assertEquals(
        ExternalHandlerPrecheckMain.selectCompactAuthoringApi(
          ExternalHandlerPrecheckMain.RuntimeArtifacts(plugin, plugin),
          Vector(authoringApi, compiler)
        ),
        Right(authoringApi)
      )
    finally deleteRecursively(root)
  }

  private def jarFromResources(path: Path, resources: List[String]): Path =
    jar(
      path,
      resources.map: resource =>
        val stream = Option(getClass.getClassLoader.getResourceAsStream(resource))
          .getOrElse(fail(s"missing test resource $resource"))
        try resource -> stream.readAllBytes()
        finally stream.close()
      .toMap
    )

  private def packagedFailure(
      markerClassName: String,
      markerResource: String,
      expectedHandlerClassName: String,
      expectedAnnotationName: String,
      handlerResources: List[String]
  ): Failure =
    val root = Files.createTempDirectory("external-handler-precheck-authoring-")
    try
      val plugin = jar(root.resolve("plugin.jar"), Map(
        "macroparadise/MacroParadisePlugin.class" -> Array[Byte](1),
        "macroparadise/ExternalHandlerPrecheckMain.class" -> Array[Byte](1),
        "plugin.properties" -> Array[Byte](1)
      ))
      val pluginApi = jar(root.resolve("plugin-api.jar"), Map(
        "paradise3/api/ParadiseAnnotationExpander.class" -> Array[Byte](1),
        "paradise3/api/expander.class" -> Array[Byte](1)
      ))
      val marker = jarFromResources(root.resolve("marker.jar"), List(markerResource))
      val handler = jarFromResources(root.resolve("handler.jar"), handlerResources)
      val compiler = jar(root.resolve("scala3-compiler.jar"), Map(
        "dotty/tools/dotc/Main.class" -> Array[Byte](1)
      ))

      failed(
        ExternalHandlerPrecheck.run(
          Request(
            artifacts = ArtifactPaths(plugin, pluginApi, marker, handler),
            handlerCompileClasspath = Vector(pluginApi, compiler),
            markerClassName = markerClassName,
            expectedHandlerClassName = expectedHandlerClassName,
            expectedAnnotationName = expectedAnnotationName,
            environment = Environment(
              expectedScalaVersion = "3.8.4",
              actualScalaVersion = "3.8.4",
              expectedJdkMajor = 25,
              actualJdkMajor = 25
            ),
            parentLoader = getClass.getClassLoader
          )
        )
      )
    finally deleteRecursively(root)

  private def jar(path: Path, entries: Map[String, Array[Byte]]): Path =
    Files.createDirectories(path.getParent)
    val output = JarOutputStream(
      Files.newOutputStream(path, StandardOpenOption.CREATE_NEW)
    )
    try
      entries.toList.sortBy(_._1).foreach: (name, bytes) =>
        output.putNextEntry(JarEntry(name))
        output.write(bytes)
        output.closeEntry()
    finally output.close()
    path

  private def deleteRecursively(path: Path): Unit =
    if Files.exists(path) then
      val paths = Files.walk(path)
      try paths.sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete)
      finally paths.close()

  private def failed[A](result: Either[Failure, A]): Failure =
    result match
      case Left(failure) => failure
      case Right(value) => fail(s"unexpected success: $value")
