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
          expectedScalaVersion = "3.8.5-RC1-bin-20260405-9478256-NIGHTLY",
          actualScalaVersion = "3.8.4",
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
          expectedScalaVersion = "3.8.5-RC1-bin-20260405-9478256-NIGHTLY",
          actualScalaVersion = "3.8.5-RC1-bin-20260405-9478256-NIGHTLY",
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
            Set("macroparadise/HelloWorldPlugin.class"),
            Set("macroparadise/HelloWorldPlugin")
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
              "macroparadise/HelloWorldPlugin.class" -> Array[Byte](1),
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
        "macroparadise/HelloWorldPlugin.class" -> Array[Byte](1),
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
            expectedScalaVersion = "3.8.5-RC1-bin-20260405-9478256-NIGHTLY",
            actualScalaVersion = "3.8.5-RC1-bin-20260405-9478256-NIGHTLY",
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

  test("precheck command rejects a missing required artifact argument") {
    val failure = failed(
      ExternalHandlerPrecheckMain.parse(
        Array(
          "--plugin=/artifacts/plugin.jar",
          "--plugin-api=/artifacts/plugin-api.jar"
        ),
        getClass.getClassLoader,
        actualScalaVersion = "3.8.5-RC1-bin-20260405-9478256-NIGHTLY",
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
        "--expected-scala-version=3.8.5-RC1-bin-20260405-9478256-NIGHTLY",
        "--expected-jdk-major=25"
      ),
      getClass.getClassLoader,
      actualScalaVersion = "3.8.5-RC1-bin-20260405-9478256-NIGHTLY",
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
