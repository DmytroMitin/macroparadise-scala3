object BuildDependencyCoordinatePolicySpec {
  val CaseCount = 25

  def run(): Unit = {
    import BuildDependencyCoordinatePolicy._

    var completed = 0
    def check(name: String)(body: => Unit): Unit = {
      try {
        body
        completed += 1
      } catch {
        case error: Throwable =>
          throw new AssertionError(s"dependency-coordinate policy spec failed: $name", error)
      }
    }
    def expectFailure(fragment: String, dependencies: Seq[Dependency], all: Seq[Dependency] = Nil): Unit = {
      val actualAll = if (all.isEmpty) dependencies else all
      val result = verify(dependencies, actualAll, validShape)
      assert(result.errors.exists(_.contains(fragment)), result.errors.mkString("; "))
    }

    val compiler = correctedCompiler(ExpectedScalaVersion)

    check("valid exact coordinate") {
      assert(verify(Seq(compiler), Seq(compiler), validShape).errors.isEmpty)
    }
    check("main-build organization contamination") {
      expectFailure(
        "organization must be org.scala-lang",
        Seq(compiler.copy(organization = "orgP069.scala-lang"))
      )
    }
    check("experimental API organization contamination") {
      expectFailure(
        "prompt-number contamination",
        Seq(compiler.copy(organization = "orgP77.scala-lang"))
      )
    }
    check("wrong organization without prompt number") {
      expectFailure(
        "organization must be org.scala-lang",
        Seq(compiler.copy(organization = "example.invalid"))
      )
    }
    check("wrong compiler artifact") {
      expectFailure(
        "exactly one direct scala3-compiler",
        Seq(compiler.copy(artifact = "scala-compiler"))
      )
    }
    check("wrong compiler version") {
      expectFailure(
        "compiler version must equal",
        Seq(compiler.copy(version = "0.0.0"))
      )
    }
    check("duplicate compiler dependencies") {
      expectFailure("exactly one direct scala3-compiler", Seq(compiler, compiler))
    }
    check("test-only compiler dependency") {
      expectFailure(
        "ordinary compile scope",
        Seq(compiler.copy(configuration = "test"))
      )
    }
    check("provided-only compiler dependency") {
      expectFailure(
        "ordinary compile scope",
        Seq(compiler.copy(configuration = "provided"))
      )
    }
    check("missing compiler dependency") {
      expectFailure("exactly one direct scala3-compiler", Seq.empty)
    }
    check("unexpected classifier") {
      expectFailure(
        "must not declare classifiers",
        Seq(compiler.copy(classifiers = List("sources")))
      )
    }
    check("contamination elsewhere in build") {
      expectFailure(
        "prompt-number contamination",
        Seq(compiler),
        Seq(compiler, Dependency("orgP123.example", "fixture", "1"))
      )
    }
    check("root aggregate drift") {
      val result = verify(
        Seq(compiler),
        Seq(compiler),
        validShape.copy(rootAggregate = validShape.rootAggregate - "pluginApi")
      )
      assert(result.errors.exists(_.contains("root aggregate drift")))
    }
    check("marker project identity drift") {
      val result = verify(
        Seq(compiler),
        Seq(compiler),
        validShape.copy(pluginTestMarkersProjectId = "pluginApi")
      )
      assert(result.errors.exists(_.contains("pluginTestMarkers project identity drift")))
    }
    check("marker project is not separate") {
      val result = verify(
        Seq(compiler),
        Seq(compiler),
        validShape.copy(pluginTestMarkersIsSeparate = false)
      )
      assert(result.errors.exists(_.contains("rooted at plugin-test-markers")))
    }
    check("marker project lost API dependency") {
      val result = verify(
        Seq(compiler),
        Seq(compiler),
        validShape.copy(pluginTestMarkersDependsOnPluginApi = false)
      )
      assert(result.errors.exists(_.contains("must depend on pluginApi")))
    }
    check("API ownership reversal") {
      val result = verify(
        Seq(compiler),
        Seq(compiler),
        validShape.copy(pluginApiDependsOnPluginTestMarkers = true)
      )
      assert(result.errors.exists(_.contains("must not depend on pluginTestMarkers")))
    }
    check("experimental API verifier disappearance") {
      val result = verify(
        Seq(compiler),
        Seq(compiler),
        validShape.copy(
          surfaceBaselineExists = false,
          surfaceTaskLabels = Set.empty
        )
      )
      assert(result.errors.exists(_.contains("surface baseline file is missing")))
      assert(result.errors.exists(_.contains("surface verifier tasks are missing")))
    }
    check("producer project identity and separation") {
      val result = verify(
        Seq(compiler),
        Seq(compiler),
        validShape.copy(
          embeddedProducerProjectId = "plugin",
          embeddedProducerIsSeparate = false
        )
      )
      assert(result.errors.exists(_.contains("producer project identity drift")))
      assert(result.errors.exists(_.contains("rooted at embedded-producer-plugin")))
    }
    check("producer project depends only on pluginApi") {
      val result = verify(
        Seq(compiler),
        Seq(compiler),
        validShape.copy(embeddedProducerProjectDependencies = Set("pluginApi", "plugin"))
      )
      assert(result.errors.exists(_.contains("depend only on pluginApi")))
    }
    check("forbidden reverse producer dependencies") {
      val result = verify(
        Seq(compiler),
        Seq(compiler),
        validShape.copy(
          pluginApiDependsOnEmbeddedProducer = true,
          consumerPluginDependsOnEmbeddedProducer = true,
          sbtIntegrationMentionsEmbeddedProducer = true
        )
      )
      assert(result.errors.exists(_.contains("pluginApi must not depend")))
      assert(result.errors.exists(_.contains("consumer plugin must not depend")))
      assert(result.errors.exists(_.contains("sbt integration must remain unchanged")))
    }
    check("producer compiler dependency is exact") {
      val result = verify(
        Seq(compiler),
        Seq(compiler),
        validShape.copy(
          embeddedProducerDependencies = Seq(compiler.copy(version = "0.0.0"))
        )
      )
      assert(result.errors.exists(_.contains("producer compiler version must equal")))
    }
    check("producer peer product dependency is forbidden") {
      val peer = Dependency("example", "quasiquotes-scala3", "1")
      val result = verify(
        Seq(compiler),
        Seq(compiler, peer),
        validShape.copy(embeddedProducerDependencies = Seq(compiler, peer))
      )
      assert(result.errors.exists(_.contains("must not depend on peer product")))
    }

    check("unexpected producer library dependency is forbidden") {
      val extra = Dependency("com.example", "unrelated-helper", "1.0.0")
      val result = verify(
        Seq(compiler),
        Seq(compiler, extra),
        validShape.copy(embeddedProducerDependencies = Seq(compiler, extra))
      )
      assert(
        result.errors.exists(_.contains("unexpected direct library dependency")),
        result.errors.mkString("; ")
      )
    }


    check("spoofed producer Scala library dependency is forbidden") {
      val spoofed = Dependency("com.example", "scala3-library_3", "1.0.0")
      val result = verify(
        Seq(compiler),
        Seq(compiler, spoofed),
        validShape.copy(embeddedProducerDependencies = Seq(compiler, spoofed))
      )
      assert(
        result.errors.exists(_.contains("Scala library organization must be org.scala-lang")) &&
          result.errors.exists(_.contains("Scala library version must equal scalaVersion")),
        result.errors.mkString("; ")
      )
    }

    assert(completed == CaseCount, s"expected $CaseCount cases, completed $completed")
  }

  private def validShape: BuildDependencyCoordinatePolicy.BuildShape =
    BuildDependencyCoordinatePolicy.BuildShape(
      scalaVersion = BuildDependencyCoordinatePolicy.ExpectedScalaVersion,
      sbtVersion = BuildDependencyCoordinatePolicy.ExpectedSbtVersion,
      jdkFeature = BuildDependencyCoordinatePolicy.ExpectedJdkFeature,
      embeddedProducerProjectId = BuildDependencyCoordinatePolicy.ExpectedEmbeddedProducerProjectId,
      embeddedProducerIsSeparate = true,
      embeddedProducerProjectDependencies = Set("pluginApi"),
      embeddedProducerDependencies = Seq(
        BuildDependencyCoordinatePolicy.correctedCompiler(
          BuildDependencyCoordinatePolicy.ExpectedScalaVersion
        )
      ),
      pluginApiDependsOnEmbeddedProducer = false,
      consumerPluginDependsOnEmbeddedProducer = false,
      sbtIntegrationMentionsEmbeddedProducer = false,
      pluginApiProjectId = BuildDependencyCoordinatePolicy.ExpectedPluginApiProjectId,
      pluginApiIsSeparate = true,
      pluginTestMarkersProjectId = BuildDependencyCoordinatePolicy.ExpectedPluginTestMarkersProjectId,
      pluginTestMarkersIsSeparate = true,
      pluginTestMarkersDependsOnPluginApi = true,
      pluginApiDependsOnPluginTestMarkers = false,
      rootAggregate = BuildDependencyCoordinatePolicy.ExpectedRootAggregate,
      surfaceBaselineExists = true,
      surfaceTaskLabels = BuildDependencyCoordinatePolicy.RequiredSurfaceTasks
    )
}
