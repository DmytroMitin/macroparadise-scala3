package macroparadise

import dotty.tools.dotc.core.Contexts.Context
import scala.tasty.inspector.Inspector

import java.io.File
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.jar.JarFile

class AnnotationMetadataReaderSpec extends munit.FunSuite:
  private val scalaVersion =
    sys.props.getOrElse(
      "macroparadise.testScalaVersion",
      "3.8.5-RC1-bin-20260405-9478256-NIGHTLY"
    )
  private val pluginApiJar =
    codeSourcePath(classOf[paradise3.api.expander])

  private val currentMarkerJar =
    new File(
      s"plugin-test-markers/target/scala-$scalaVersion/macroparadise-scala3-plugin-test-markers_3-0.1.0.jar"
    ).getAbsolutePath

  private val legacyMarkerJar =
    new File(
      s"legacy-metadata-marker-fixture/target/scala-$scalaVersion/macroparadise-scala3-legacy-metadata-marker-fixture_3-0.1.0.jar"
    ).getAbsolutePath

  private val apiLoader = getClass.getClassLoader

  private def codeSourcePath(clazz: Class[?]): String =
    new File(clazz.getProtectionDomain.getCodeSource.getLocation.toURI)
      .getAbsolutePath

  private def withLegacyLoader[A](body: URLClassLoader => A): A =
    val loader =
      URLClassLoader(
        Array(new File(legacyMarkerJar).toURI.toURL),
        apiLoader
      )
    try body(loader)
    finally loader.close()

  test("legacy fixture jar contains only the consumable old-format marker") {
    val jar = JarFile(legacyMarkerJar)
    try
      val entries = Set.newBuilder[String]
      val jarEntries = jar.entries()
      while jarEntries.hasMoreElements do
        entries += jarEntries.nextElement().getName
      val entryNames = entries.result()

      assert(entryNames.contains("paradise3/legacyExternalDebug.class"))
      assert(entryNames.contains("paradise3/legacyExternalDebug.tasty"))
      assert(!entryNames.contains("paradise3/api/expander.class"))
      assert(!entryNames.contains("paradise3/api/expander.tasty"))

      val tastyStream =
        jar.getInputStream(
          jar.getJarEntry("paradise3/legacyExternalDebug.tasty")
        )
      val tastyBytes =
        try tastyStream.readAllBytes()
        finally tastyStream.close()
      val tastyText = String(tastyBytes, StandardCharsets.ISO_8859_1)
      assert(tastyText.contains("demo.LegacyExternalDebugExpander"), tastyText)
    finally jar.close()
  }

  test("legacy marker classfile has no runtime-visible expander metadata") {
    val process =
      ProcessBuilder(
        "javap",
        "-v",
        "-classpath",
        legacyMarkerJar,
        "paradise3.legacyExternalDebug"
      ).redirectErrorStream(true).start()
    val output =
      String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    val exitCode = process.waitFor()

    assertEquals(exitCode, 0, output)
    assert(!output.contains("RuntimeVisibleAnnotations"), output)
    assert(!output.contains("paradise3.api.expander"), output)
  }

  test("runtime reader loads the real legacy marker without runtime metadata or obsolete carrier identity") {
    withLegacyLoader: legacyLoader =>
      val reader = RuntimeAnnotationMetadataReader(legacyLoader)

      assertEquals(
        reader.findExpanderClass("legacyExternalDebug"),
        MetadataLookupResult.NotFound
      )
      val markerClass =
        Class.forName("paradise3.legacyExternalDebug", false, legacyLoader)
      val carrierFromLegacyLoader =
        Class.forName("paradise3.api.expander", false, legacyLoader)
      val currentCarrier =
        Class.forName("paradise3.api.expander", false, apiLoader)

      assert(markerClass.getClassLoader eq legacyLoader)
      assert(carrierFromLegacyLoader eq currentCarrier)
      assert(carrierFromLegacyLoader.getClassLoader eq apiLoader)
  }

  test("TastyInspector reader recovers the handler from the real legacy marker jar") {
    val reader =
      TastyInspectorAnnotationMetadataReader(
        List(legacyMarkerJar, pluginApiJar)
      )

    assertEquals(
      reader.findExpanderClass("legacyExternalDebug"),
      MetadataLookupResult.Found("demo.LegacyExternalDebugExpander")
    )
  }

  test("production reader uses validated explicit structured metadata paths") {
    val validatedPath =
      StructuredMetadataDistributionContract
        .validatePath(legacyMarkerJar)
        .toOption
        .get
    val reader =
      TastyInspectorAnnotationMetadataReader.fromPaths(
        paths = List(validatedPath),
        dependencyClasspath = List(legacyMarkerJar, pluginApiJar)
      )

    assertEquals(
      reader.findExpanderClass("legacyExternalDebug"),
      MetadataLookupResult.Found("demo.LegacyExternalDebugExpander")
    )
  }

  test("production reader accepts explicit paths and stops after structured Found") {
    val traceFile =
      Files.createTempFile("macroparadise-explicit-production-reader", ".trace")
    val validatedPath =
      StructuredMetadataDistributionContract
        .validatePath(legacyMarkerJar)
        .toOption
        .get

    try
      withLegacyLoader: legacyLoader =>
        val reader =
          AnnotationMetadataReader.production(
            legacyLoader,
            List(validatedPath),
            MetadataReaderTrace.fromPath(Some(traceFile))
          )
        assertEquals(
          reader.findExpanderClass("legacyExternalDebug"),
          MetadataLookupResult.Found("demo.LegacyExternalDebugExpander")
        )
        assertEquals(
          Files
            .readString(traceFile)
            .linesIterator
            .filter(_.contains("paradise3.legacyExternalDebug"))
            .toList,
          List(
            "runtime paradise3.legacyExternalDebug NotFound",
            "structured paradise3.legacyExternalDebug Found(demo.LegacyExternalDebugExpander)"
          )
        )
    finally Files.deleteIfExists(traceFile)
  }

  test("explicit structured metadata paths return NotFound for an unrelated annotation") {
    val validatedPath =
      StructuredMetadataDistributionContract
        .validatePath(legacyMarkerJar)
        .toOption
        .get
    val absent =
      TastyInspectorAnnotationMetadataReader.explicit(
        tastyFiles = Nil,
        jars = Nil,
        dependencyClasspath = List(pluginApiJar)
      )
    val wrong =
      TastyInspectorAnnotationMetadataReader.fromPaths(
        paths = List(validatedPath),
        dependencyClasspath = List(legacyMarkerJar, pluginApiJar)
      )

    assertEquals(
      absent.findExpanderClass("legacyExternalDebug"),
      MetadataLookupResult.NotFound
    )
    assertEquals(
      wrong.findExpanderClass("externalMarker"),
      MetadataLookupResult.NotFound
    )
  }

  test("structured metadata option preserves path order and rejects normalized duplicates") {
    val secondDirectory =
      Files.createTempDirectory("macroparadise-structured-metadata")
    val paradiseDirectory = secondDirectory.resolve("paradise3")
    Files.createDirectories(paradiseDirectory)
    Files.writeString(paradiseDirectory.resolve("other.tasty"), "fixture")

    try
      val parsed =
        StructuredMetadataDistributionContract.parseAndValidate(
          List(
            s"structuredMetadataPath=$legacyMarkerJar",
            s"structuredMetadataPath=$secondDirectory"
          )
        )
      val paths = parsed.toOption.flatten.get
      assertEquals(
        paths.map(_.value),
        List(
          new File(legacyMarkerJar).toPath.toRealPath().toString,
          secondDirectory.toRealPath().toString
        )
      )

      val duplicate =
        StructuredMetadataDistributionContract.parseAndValidate(
          List(
            s"structuredMetadataPath=$legacyMarkerJar",
            s"structuredMetadataPath=$legacyMarkerJar"
          )
        )
      assert(duplicate.isLeft)
      assert(duplicate.left.toOption.get.contains("duplicate"))
    finally
      Files.deleteIfExists(paradiseDirectory.resolve("other.tasty"))
      Files.deleteIfExists(paradiseDirectory)
      Files.deleteIfExists(secondDirectory)
  }

  test("structured metadata option rejects blank and absent paths") {
    val blank =
      StructuredMetadataDistributionContract.parseAndValidate(
        List("structuredMetadataPath= ")
      )
    val result =
      StructuredMetadataDistributionContract.parseAndValidate(
        List(
          s"structuredMetadataPath=${new File("target/absent-structured-metadata.jar").getAbsolutePath}"
        )
      )

    assert(blank.isLeft)
    assert(blank.left.toOption.get.contains("empty experimental"))
    assert(result.isLeft)
    assert(result.left.toOption.get.contains("does not exist"))
  }

  test("structured metadata option rejects the obsolete carrier output") {
    val result =
      StructuredMetadataDistributionContract.validatePath(pluginApiJar)

    assert(result.isLeft)
    assert(result.left.toOption.get.contains("exposes obsolete carrier"))
  }

  test("structured metadata option rejects a duplicate compiler identity") {
    val compilerJar =
      codeSourcePath(classOf[dotty.tools.dotc.core.Contexts.Context])
    val result =
      StructuredMetadataDistributionContract.validatePath(compilerJar)

    assert(result.isLeft)
    assert(result.left.toOption.get.contains("conflicting compiler/Scala/TASTy/API identity"))
  }

  test("structured metadata option rejects malformed and non-jar files") {
    val malformed = Files.createTempFile("macroparadise-malformed-marker", ".jar")
    val nonJar = Files.createTempFile("macroparadise-marker", ".txt")
    Files.writeString(malformed, "not a jar")

    try
      val malformedResult =
        StructuredMetadataDistributionContract.validatePath(malformed.toString)
      val nonJarResult =
        StructuredMetadataDistributionContract.validatePath(nonJar.toString)
      assert(malformedResult.isLeft)
      assert(malformedResult.left.toOption.get.contains("is malformed"))
      assert(nonJarResult.isLeft)
      assert(nonJarResult.left.toOption.get.contains("must be a readable JAR or directory"))
    finally
      Files.deleteIfExists(malformed)
      Files.deleteIfExists(nonJar)
  }

  test("structured metadata runtime audit proves the exact active pair and parent-first identities") {
    val compilerPath = codeSourcePath(classOf[Context])
    val inspectorPath = codeSourcePath(classOf[Inspector])

    StructuredMetadataDistributionContract.auditRuntime(apiLoader, apiLoader) match
      case StructuredMetadataDistributionContract.InspectorAudit.Ready(evidence) =>
        assert(
          evidence.contains(
            s"compiler=$compilerPath version=$scalaVersion artifact=exact-loaded"
          ),
          evidence
        )
        assert(
          evidence.contains(
            s"inspector=$inspectorPath version=$scalaVersion artifact=thin-exact"
          ),
          evidence
        )
        assert(evidence.exists(_.contains("identity=dotty.tools.dotc.core.Contexts$Context")))
        assert(evidence.exists(_.contains("identity=scala.tasty.inspector.Inspector")))
        assert(evidence.exists(_.contains("identity=scala.quoted.Quotes")))
        assert(evidence.exists(_.contains("identity=paradise3.api.expander")))
        assert(evidence.exists(_.contains("identity=paradise3.api.ParadiseAnnotationExpander")))
      case other =>
        fail(s"expected ready structured metadata distribution audit, got $other")
  }

  test("structured metadata runtime audit rejects a child-first compiler identity") {
    val compilerUrl =
      classOf[Context].getProtectionDomain.getCodeSource.getLocation
    val childFirstCompilerLoader =
      new URLClassLoader(Array(compilerUrl), apiLoader):
        override protected def loadClass(
            name: String,
            resolve: Boolean
        ): Class[?] =
          if name == classOf[Context].getName then
            val loaded =
              Option(findLoadedClass(name)).getOrElse(findClass(name))
            if resolve then resolveClass(loaded)
            loaded
          else super.loadClass(name, resolve)

    try
      StructuredMetadataDistributionContract.auditRuntime(
        childFirstCompilerLoader,
        apiLoader
      ) match
        case StructuredMetadataDistributionContract.InspectorAudit.Invalid(message) =>
          assert(message.contains("plugin loader duplicated"))
          assert(message.contains(classOf[Context].getName))
        case other =>
          fail(s"expected rejected child-first compiler identity, got $other")
    finally childFirstCompilerLoader.close()
  }

  test("printable-string reader recovers the exact handler from the real legacy TASTy resource") {
    withLegacyLoader: legacyLoader =>
      val reader = TastyStringAnnotationMetadataReader(legacyLoader)

      assertEquals(
        reader.findExpanderClass("legacyExternalDebug"),
        MetadataLookupResult.Found("demo.LegacyExternalDebugExpander")
      )
  }

  test("production reader traces runtime and structured misses before real legacy string recovery") {
    val traceFile =
      Files.createTempFile("macroparadise-legacy-production-reader", ".trace")

    try
      withLegacyLoader: legacyLoader =>
        val reader =
          AnnotationMetadataReader.production(
            legacyLoader,
            Nil,
            MetadataReaderTrace.fromPath(Some(traceFile))
          )

        assertEquals(
          reader.findExpanderClass("legacyExternalDebug"),
          MetadataLookupResult.Found("demo.LegacyExternalDebugExpander")
        )
        assertEquals(
          Files
            .readString(traceFile)
            .linesIterator
            .filter(_.contains("paradise3.legacyExternalDebug"))
            .toList,
          List(
            "runtime paradise3.legacyExternalDebug NotFound",
            "structured paradise3.legacyExternalDebug NotFound",
            "string paradise3.legacyExternalDebug Found(demo.LegacyExternalDebugExpander)"
          )
        )
    finally Files.deleteIfExists(traceFile)
  }

  test("production current marker still short-circuits after a real runtime Found") {
    val traceFile =
      Files.createTempFile("macroparadise-current-production-reader", ".trace")

    try
      val reader =
        AnnotationMetadataReader.production(
          apiLoader,
          Nil,
          MetadataReaderTrace.fromPath(Some(traceFile))
        )

      assertEquals(
        reader.findExpanderClass("externalDebug"),
        MetadataLookupResult.Found("demo.ExternalDebugExpander")
      )
      assertEquals(
        Files
          .readString(traceFile)
          .linesIterator
          .filter(_.contains("paradise3.externalDebug"))
          .toList,
        List(
          "runtime paradise3.externalDebug Found(demo.ExternalDebugExpander)"
        )
      )
    finally Files.deleteIfExists(traceFile)
  }

  test("runtime metadata reader finds the exact expander metadata on a precompiled marker") {
    val reader = RuntimeAnnotationMetadataReader(apiLoader)

    assertEquals(
      reader.findExpanderClass("externalDebug"),
      MetadataLookupResult.Found("demo.ExternalDebugExpander")
    )
    val markerClass = Class.forName("paradise3.externalDebug", false, apiLoader)
    val currentCarrier =
      Class.forName("paradise3.api.expander", false, apiLoader)
    assert(markerClass.getClassLoader eq currentCarrier.getClassLoader)
  }

  test("runtime metadata reader uses an exact qualified annotation class identity") {
    val reader = RuntimeAnnotationMetadataReader(apiLoader)

    assertEquals(
      reader.findExpanderClass("qualifiedone.audit"),
      MetadataLookupResult.Found("demo.QualifiedOneAuditExpander")
    )
    assertEquals(
      reader.findExpanderClass("qualifiedunknown.audit"),
      MetadataLookupResult.NotFound
    )
  }

  test("structured and string compatibility readers retain an exact qualified identity") {
    val structured =
      TastyInspectorAnnotationMetadataReader(List(currentMarkerJar, pluginApiJar))
    val string = TastyStringAnnotationMetadataReader(apiLoader)

    assertEquals(
      structured.findExpanderClass("qualifiedtwo.audit"),
      MetadataLookupResult.Found("demo.QualifiedTwoAuditExpander")
    )
    assertEquals(
      string.findExpanderClass("qualifiedtwo.audit"),
      MetadataLookupResult.Found("demo.QualifiedTwoAuditExpander")
    )
  }

  test("runtime metadata reader fails closed for an ambiguous simple external name") {
    val reader = RuntimeAnnotationMetadataReader(apiLoader)

    reader.findExpanderClass("audit") match
      case MetadataLookupResult.Failed(message) =>
        assert(message.contains("ambiguous runtime annotation metadata for `audit`"), message)
        assert(message.contains("qualifiedone.audit"), message)
        assert(message.contains("qualifiedtwo.audit"), message)
      case other =>
        fail(s"expected ambiguous simple-name failure, found $other")
  }

  test("runtime metadata reader returns NotFound when marker has no expander metadata") {
    val reader = RuntimeAnnotationMetadataReader(apiLoader)

    assertEquals(reader.findExpanderClass("externalMarker"), MetadataLookupResult.NotFound)
    assertEquals(reader.findExpanderClass("doesNotExist"), MetadataLookupResult.NotFound)
  }

  test("runtime metadata reader preserves empty expander diagnostic") {
    val reader = RuntimeAnnotationMetadataReader(apiLoader)

    assertEquals(
      reader.findExpanderClass("metadataEmpty"),
      MetadataLookupResult.Failed(
        "empty annotation metadata expander class name for `paradise3.metadataEmpty`"
      )
    )
  }

  test("runtime metadata reader does not initialize the marker class") {
    val propertyName = "macroparadise.metadataInitializationProbe"
    System.clearProperty(propertyName)

    try
      val reader = RuntimeAnnotationMetadataReader(apiLoader)
      assertEquals(
        reader.findExpanderClass("MetadataInitializationProbe"),
        MetadataLookupResult.Found("demo.ExternalDebugExpander")
      )
      assertEquals(Option(System.getProperty(propertyName)), None)
    finally
      System.clearProperty(propertyName)
  }

  test("TastyInspector metadata reader finds expander metadata on precompiled marker") {
    val reader = TastyInspectorAnnotationMetadataReader(List(currentMarkerJar, pluginApiJar))

    assertEquals(reader.findExpanderClass("externalDebug"), MetadataLookupResult.Found("demo.ExternalDebugExpander"))
  }

  test("TastyInspector metadata reader returns NotFound when marker has no expander metadata") {
    val reader = TastyInspectorAnnotationMetadataReader(List(currentMarkerJar, pluginApiJar))

    assertEquals(reader.findExpanderClass("externalMarker"), MetadataLookupResult.NotFound)
  }

  test("TastyInspector metadata reader preserves empty expander diagnostic") {
    val reader = TastyInspectorAnnotationMetadataReader(List(currentMarkerJar, pluginApiJar))

    assertEquals(
      reader.findExpanderClass("metadataEmpty"),
      MetadataLookupResult.Failed("empty annotation metadata expander class name for `paradise3.metadataEmpty`"),
      ""
    )
  }

  test("structured-first reader treats fallback NotFound as unhandled when structured reader is unavailable") {
    val reader =
      StructuredFirstAnnotationMetadataReader(
        new AnnotationMetadataReader:
          def findExpanderClass(annotationName: String): MetadataLookupResult =
            MetadataLookupResult.Failed("structured unavailable"),
        new AnnotationMetadataReader:
          def findExpanderClass(annotationName: String): MetadataLookupResult =
            MetadataLookupResult.NotFound,
        MetadataReaderTrace.disabled
      )

    assertEquals(reader.findExpanderClass("ordinaryAnnotation"), MetadataLookupResult.NotFound)
  }

  test("structured-first reader skips fallback after structured metadata is found") {
    var fallbackInvoked = false
    val reader =
      StructuredFirstAnnotationMetadataReader(
        new AnnotationMetadataReader:
          def findExpanderClass(annotationName: String): MetadataLookupResult =
            MetadataLookupResult.Found("demo.StructuredExpander"),
        new AnnotationMetadataReader:
          def findExpanderClass(annotationName: String): MetadataLookupResult =
            fallbackInvoked = true
            MetadataLookupResult.Found("demo.FallbackExpander"),
        MetadataReaderTrace.disabled
      )

    assertEquals(
      reader.findExpanderClass("structuredAnnotation"),
      MetadataLookupResult.Found("demo.StructuredExpander")
    )
    assertEquals(fallbackInvoked, false)
  }

  test("structured-first reader uses fallback metadata when the structured reader is unavailable") {
    val reader =
      StructuredFirstAnnotationMetadataReader(
        new AnnotationMetadataReader:
          def findExpanderClass(annotationName: String): MetadataLookupResult =
            MetadataLookupResult.Failed("structured unavailable"),
        new AnnotationMetadataReader:
          def findExpanderClass(annotationName: String): MetadataLookupResult =
            MetadataLookupResult.Found("demo.FallbackExpander"),
        MetadataReaderTrace.disabled
      )

    assertEquals(
      reader.findExpanderClass("fallbackAnnotation"),
      MetadataLookupResult.Found("demo.FallbackExpander")
    )
  }

  test("runtime-first reader skips every compatibility reader after runtime metadata is found") {
    var compatibilityInvoked = false
    val reader =
      RuntimeFirstAnnotationMetadataReader(
        new AnnotationMetadataReader:
          def findExpanderClass(annotationName: String): MetadataLookupResult =
            MetadataLookupResult.Found("demo.RuntimeExpander"),
        new AnnotationMetadataReader:
          def findExpanderClass(annotationName: String): MetadataLookupResult =
            compatibilityInvoked = true
            MetadataLookupResult.Found("demo.CompatibilityExpander"),
        MetadataReaderTrace.disabled
      )

    assertEquals(
      reader.findExpanderClass("runtimeAnnotation"),
      MetadataLookupResult.Found("demo.RuntimeExpander")
    )
    assertEquals(compatibilityInvoked, false)
  }

  test("runtime-first reader falls back after runtime NotFound") {
    val reader =
      RuntimeFirstAnnotationMetadataReader(
        new AnnotationMetadataReader:
          def findExpanderClass(annotationName: String): MetadataLookupResult =
            MetadataLookupResult.NotFound,
        new AnnotationMetadataReader:
          def findExpanderClass(annotationName: String): MetadataLookupResult =
            MetadataLookupResult.Found("demo.CompatibilityExpander"),
        MetadataReaderTrace.disabled
      )

    assertEquals(
      reader.findExpanderClass("legacyAnnotation"),
      MetadataLookupResult.Found("demo.CompatibilityExpander")
    )
  }

  test("runtime-first reader recovers through compatibility lookup after runtime failure") {
    val reader =
      RuntimeFirstAnnotationMetadataReader(
        new AnnotationMetadataReader:
          def findExpanderClass(annotationName: String): MetadataLookupResult =
            MetadataLookupResult.Failed("runtime failed"),
        new AnnotationMetadataReader:
          def findExpanderClass(annotationName: String): MetadataLookupResult =
            MetadataLookupResult.Found("demo.CompatibilityExpander"),
        MetadataReaderTrace.disabled
      )

    assertEquals(
      reader.findExpanderClass("recoverableAnnotation"),
      MetadataLookupResult.Found("demo.CompatibilityExpander")
    )
  }

  test("runtime-first reader retains the runtime failure when compatibility has no metadata") {
    val reader =
      RuntimeFirstAnnotationMetadataReader(
        new AnnotationMetadataReader:
          def findExpanderClass(annotationName: String): MetadataLookupResult =
            MetadataLookupResult.Failed("runtime failed"),
        new AnnotationMetadataReader:
          def findExpanderClass(annotationName: String): MetadataLookupResult =
            MetadataLookupResult.NotFound,
        MetadataReaderTrace.disabled
      )

    assertEquals(
      reader.findExpanderClass("brokenAnnotation"),
      MetadataLookupResult.Failed("runtime failed")
    )
  }

  test("runtime-first trace is deterministic and stops after Found") {
    val traceFile = Files.createTempFile("macroparadise-runtime-reader", ".trace")

    try
      var compatibilityInvoked = false
      val reader =
        RuntimeFirstAnnotationMetadataReader(
          new AnnotationMetadataReader:
            def findExpanderClass(annotationName: String): MetadataLookupResult =
              MetadataLookupResult.Found("demo.RuntimeExpander"),
          new AnnotationMetadataReader:
            def findExpanderClass(annotationName: String): MetadataLookupResult =
              compatibilityInvoked = true
              MetadataLookupResult.NotFound,
          MetadataReaderTrace.fromPath(Some(traceFile))
        )

      assertEquals(
        reader.findExpanderClass("runtimeAnnotation"),
        MetadataLookupResult.Found("demo.RuntimeExpander")
      )
      assertEquals(
        Files
          .readString(traceFile)
          .linesIterator
          .filter(_.contains("paradise3.runtimeAnnotation"))
          .toList,
        List("runtime paradise3.runtimeAnnotation Found(demo.RuntimeExpander)")
      )
      assertEquals(compatibilityInvoked, false)
    finally Files.deleteIfExists(traceFile)
  }

  test("production trace records every attempted reader in deterministic order") {
    val traceFile = Files.createTempFile("macroparadise-production-reader", ".trace")

    try
      val reader =
        AnnotationMetadataReader.production(
          apiLoader,
          Nil,
          MetadataReaderTrace.fromPath(Some(traceFile))
        )

      assertEquals(
        reader.findExpanderClass("externalMarker"),
        MetadataLookupResult.NotFound
      )
      assertEquals(
        Files
          .readString(traceFile)
          .linesIterator
          .filter(_.contains("paradise3.externalMarker"))
          .toList,
        List(
          "runtime paradise3.externalMarker NotFound",
          "structured paradise3.externalMarker NotFound",
          "string paradise3.externalMarker NotFound"
        )
      )
    finally Files.deleteIfExists(traceFile)
  }
