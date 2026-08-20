ThisBuild / version := "0.1.0"
ThisBuild / scalaVersion := "3.8.5-RC1-bin-20260405-9478256-NIGHTLY"
ThisBuild / resolvers += Resolver.scalaNightlyRepository
ThisBuild / publish / skip := true
ThisBuild / organization := "com.github.dmytromitin"
ThisBuild / organizationName := "com.github.dmytromitin"
ThisBuild / versionScheme := Some("early-semver")
ThisBuild / publishMavenStyle := true
ThisBuild / Compile / packageSrc / publishArtifact := true
ThisBuild / Compile / packageDoc / publishArtifact := true
ThisBuild / Test / publishArtifact := false
ThisBuild / licenses := List("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0"))
ThisBuild / homepage := Some(url("https://github.com/DmytroMitin/macroparadise-scala3"))
ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/DmytroMitin/macroparadise-scala3"),
    "scm:git:https://github.com/DmytroMitin/macroparadise-scala3.git",
    Some("scm:git:ssh://git@github.com:DmytroMitin/macroparadise-scala3.git")
  )
)
ThisBuild / developers := List(
  Developer(
    "DmytroMitin",
    "Dmytro Mitin",
    "dmitin3@gmail.com",
    url("https://github.com/DmytroMitin")
  )
)
ThisBuild / pomIncludeRepository := (_ => false)

Global / onLoad ~= { previous =>
  state =>
    JdkVersionEnforcement.enforceCurrent()
    previous(state)
}

lazy val commonSettings = Seq(
  libraryDependencies += "org.scalameta" %% "munit" % "1.2.4" % Test
)

lazy val selectedPublicationSettings = Seq(
  publish / skip := false,
  Compile / packageBin / mappings += file("LICENSE") -> "META-INF/LICENSE",
  Compile / packageSrc / mappings += file("LICENSE") -> "META-INF/LICENSE",
  Compile / packageDoc / mappings += file("LICENSE") -> "META-INF/LICENSE"
)

lazy val verifyJdkVersionEnforcement =
  taskKey[Unit]("Verify the JDK 25 detector and fail-fast message contract")

lazy val verifyPublicProductBoundaryModel =
  taskKey[Unit]("Verify the public-product path ownership and forbidden dependency model")

lazy val verifyPublicProductTestsNonzero =
  taskKey[Unit]("Verify the intended product test projects discover nonzero suites")

lazy val verifyPublicDocumentationPolicy =
  taskKey[Unit]("Verify the compact public documentation allowlist, private-residue policy, and relative links")

lazy val verifyApache2LicensePolicy =
  taskKey[Unit]("Verify the complete Apache-2.0 text, public wording, and POM license metadata")

lazy val verifyFreshPublicProductCopyScript =
  taskKey[Unit]("Verify the product-owned fresh-copy isolation script without recursively running sbt")

lazy val verifyPublicProductPublicationPolicy =
  taskKey[Unit]("Verify only the selected user artifacts are locally publishable and remote publishing remains fail-closed")

lazy val verifyConsumerReleaseConfiguration =
  taskKey[Unit]("Verify selected coordinates, plugin identity, local publication, and public installation documentation")

lazy val verifyPublicProductBoundary =
  taskKey[Unit]("Run the canonical self-contained public-product build boundary")

verifyJdkVersionEnforcement := {
  JdkVersionEnforcementSpec.run()
  val detected = JdkVersionEnforcement.currentDetectedVersion()
  JdkVersionEnforcement.enforce(detected)
  streams.value.log.info(
    s"JDK 25 enforcement verified: detected `${detected.display}` (feature ${detected.feature.get})"
  )
}

verifyPublicProductBoundaryModel := {
  PublicProductBoundarySpec.run()
  val result = PublicProductBoundary.verifySelectedSource(baseDirectory.value)
  require(
    result.errors.isEmpty,
    s"public-product source boundary failed:\n${result.errors.mkString("\n")}"
  )
  streams.value.log.info(
    s"public-product boundary model verified: files=${result.includedPaths.size} manifestSha256=${result.manifestSha256} focusedCases=${PublicProductBoundarySpec.CaseCount}/${PublicProductBoundarySpec.CaseCount}"
  )
}

verifyPublicDocumentationPolicy := {
  PublicDocumentationPolicySpec.run()
  val included = PublicProductBoundary.selectedFiles(baseDirectory.value).map(_._1).toSet
  val result = PublicDocumentationPolicy.verify(baseDirectory.value, included)
  require(
    result.errors.isEmpty,
    s"public documentation policy failed:\n${result.errors.mkString("\n")}"
  )
  streams.value.log.info(
    s"public documentation policy verified: files=${result.checkedPaths.size} focusedCases=${PublicDocumentationPolicySpec.CaseCount}/${PublicDocumentationPolicySpec.CaseCount}"
  )
}

verifyApache2LicensePolicy := {
  Apache2LicensePolicySpec.run()
  val result = Apache2LicensePolicy.verify(baseDirectory.value)
  require(result.errors.isEmpty, s"Apache-2.0 policy failed:\n${result.errors.mkString("\n")}")
  streams.value.log.info(
    s"Apache-2.0 source and metadata policy verified: digest=${result.licenseSha256} focusedCases=${Apache2LicensePolicySpec.CaseCount}/${Apache2LicensePolicySpec.CaseCount}"
  )
}

verifyFreshPublicProductCopyScript := {
  val script = baseDirectory.value / "scripts" / "test-verify-public-product-fresh-copy.sh"
  val exit = scala.sys.process.Process(Seq(script.getAbsolutePath), baseDirectory.value).!
  require(exit == 0, s"fresh public-product copy script model failed with exit $exit")
  streams.value.log.info("fresh public-product copy script model verified")
}

verifyPublicProductTestsNonzero := {
  val discovered = Vector(
    "plugin" -> (plugin / Test / definedTests).value.size,
    "pluginTests" -> (pluginTests / Test / definedTests).value.size
  )
  require(
    discovered.forall(_._2 > 0),
    s"intended public-product test project discovered zero suites: ${discovered.mkString(", ")}"
  )
  streams.value.log.info(
    s"public-product nonzero test inventory verified: ${discovered.map { case (id, count) => s"$id=$count" }.mkString(" ")} total=${discovered.map(_._2).sum}"
  )
}

verifyConsumerReleaseConfiguration := {
  val script = baseDirectory.value / "scripts" / "test-release-configuration.py"
  val exit = scala.sys.process.Process(Seq("python3", script.getAbsolutePath), baseDirectory.value).!
  require(exit == 0, s"consumer/release configuration checks failed with exit $exit")
  streams.value.log.info("consumer/release configuration verified")
}

verifyPublicProductPublicationPolicy := {
  val internalSkips = Vector(
    "root" -> (root / publish / skip).value,
    "legacyMetadataMarkerFixture" -> (legacyMetadataMarkerFixture / publish / skip).value,
    "legacyMetadataProducer384" -> (legacyMetadataProducer384 / publish / skip).value,
    "legacyMetadataProducer338" -> (legacyMetadataProducer338 / publish / skip).value,
    "legacyMetadataConsumer384" -> (legacyMetadataConsumer384 / publish / skip).value,
    "legacyMetadataConsumer338" -> (legacyMetadataConsumer338 / publish / skip).value,
    "packagedStructuredTastyConsumerPinned" -> (packagedStructuredTastyConsumerPinned / publish / skip).value,
    "packagedStructuredTastyConsumer384" -> (packagedStructuredTastyConsumer384 / publish / skip).value,
    "packagedStructuredTastyConsumer338" -> (packagedStructuredTastyConsumer338 / publish / skip).value,
    "macroSuspensionSpike" -> (macroSuspensionSpike / publish / skip).value,
    "sameModuleHandlerSpike" -> (sameModuleHandlerSpike / publish / skip).value,
    "sameModuleHandlerSameFileSpike" -> (sameModuleHandlerSameFileSpike / publish / skip).value,
    "sameModuleHandlerCycleSpike" -> (sameModuleHandlerCycleSpike / publish / skip).value,
    "pluginTestMarkers" -> (pluginTestMarkers / publish / skip).value,
    "pluginTestHandlers" -> (pluginTestHandlers / publish / skip).value,
    "pluginTests" -> (pluginTests / publish / skip).value
  )
  val publishable = Vector(
    "pluginApi" -> (pluginApi / publish / skip).value,
    "plugin" -> (plugin / publish / skip).value
  )
  require(internalSkips.forall(_._2), s"internal publication enabled: ${internalSkips.filterNot(_._2).map(_._1).mkString(", ")}")
  require(publishable.forall(!_._2), s"selected user artifact remains skipped: ${publishable.filter(_._2).map(_._1).mkString(", ")}")
  require((plugin / publishTo).value.isEmpty && (pluginApi / publishTo).value.isEmpty, "product publication destination is configured")
  require((plugin / credentials).value.isEmpty && (pluginApi / credentials).value.isEmpty, "product publication credentials are configured")
  require((plugin / Compile / packageSrc / publishArtifact).value && (pluginApi / Compile / packageSrc / publishArtifact).value, "source artifacts are disabled")
  require((plugin / Compile / packageDoc / publishArtifact).value && (pluginApi / Compile / packageDoc / publishArtifact).value, "documentation artifacts are disabled")
  streams.value.log.info(s"public-product publication policy verified: publishable=${publishable.map(_._1).mkString(",")} internalSkipped=${internalSkips.size} publishTo=none credentials=none")
}

verifyPublicProductBoundary := Def
  .sequential(
    verifyJdkVersionEnforcement,
    verifyPublicProductBoundaryModel,
    verifyPublicDocumentationPolicy,
    verifyApache2LicensePolicy,
    verifyFreshPublicProductCopyScript,
    verifyConsumerReleaseConfiguration,
    verifyBuildDependencyCoordinatePolicy,
    verifyPublicProductTestsNonzero,
    plugin / Test / test,
    pluginTests / Test / test,
    verifyExperimentalStructuredMetadataDistributionContract,
    verifyLegacyMetadataCompatibilityMatrix,
    verifyPluginApiSourceProjectSplit,
    pluginApi / Compile / packageBin,
    plugin / Compile / packageBin,
    verifyExperimentalPluginApiSurfaceBaseline,
    verifyExperimentalHandlerContractArtifact,
    verifyIndependentPrecompiledHandlerPackagedConsumer,
    verifyExternalHandlerAuthoringStarter,
    verifyIndependentExternalSbtConsumerFromLocalRepository,
    verifyPublicProductPublicationPolicy
  )
  .value


lazy val verifyLegacyMetadataMarkerArtifact =
  taskKey[File]("Verify the filtered pre-migration marker artifact boundary")

lazy val verifyLegacyMetadataMatrixArtifact =
  taskKey[File]("Verify one filtered legacy metadata producer artifact")

lazy val verifyLegacyMetadataCompatibilityMatrix =
  taskKey[Unit]("Verify the isolated legacy metadata producer compatibility matrix")


lazy val verifyPackagedStructuredTastyLane =
  taskKey[Unit]("Verify one isolated packaged structured TASTy consumer lane")

lazy val verifyPackagedStructuredTastyFeasibility =
  taskKey[Unit]("Verify all isolated packaged structured TASTy consumer lanes")

lazy val verifyExperimentalStructuredMetadataPositiveLanes =
  taskKey[Unit]("Verify the experimental structured metadata option in all supported positive lanes")

lazy val verifyExperimentalStructuredMetadataNegativeLanes =
  taskKey[Unit]("Verify packaged failures and controlled fallback for invalid structured metadata distributions")

lazy val verifyExperimentalStructuredMetadataDistributionContract =
  taskKey[Unit]("Verify the complete experimental structured metadata distribution contract")


lazy val renderExperimentalPluginApiSurfaceBaseline =
  taskKey[File]("Render the current experimental pluginApi surface candidate under target")

lazy val verifyExperimentalPluginApiSurfaceBaseline =
  taskKey[Unit]("Verify the exact-build pluginApi surface and isolated handler linkage contract")

lazy val renderExperimentalHandlerContractArtifact =
  taskKey[File]("Render the ignored manifest-filtered experimental handler-contract candidate JAR")

lazy val verifyExperimentalHandlerContractArtifact =
  taskKey[Unit]("Verify deterministic candidate rendering and all-current-handler isolated linkage")

lazy val verifyBuildDependencyCoordinatePolicy =
  taskKey[Unit]("Verify the exact pluginApi dependency coordinate and retained build shape")

lazy val verifyPluginApiCleanResolution =
  taskKey[Unit]("Verify pluginApi resolution and packaging from a target-free fresh dependency cache")

lazy val verifyPluginApiSourceProjectSplit =
  taskKey[Unit]("Verify experimental API category ownership across the source-built contract and marker projects")

lazy val verifyIndependentPrecompiledHandlerPackagedConsumer =
  taskKey[Unit]("Verify the independent precompiled handler packaged consumer end to end")

lazy val verifyExternalHandlerAuthoringStarter =
  taskKey[Unit]("Verify the fixture-independent external handler starter and preconsumer diagnostic matrix")


lazy val verifyIndependentExternalSbtConsumerFromLocalRepository =
  taskKey[Unit]("Verify independent external sbt producer and consumer resolution from a task-owned local repository")



lazy val root = (project in file("."))
  .aggregate(
    legacyMetadataMarkerFixture,
    pluginApi,
    plugin,
    pluginTestMarkers,
    pluginTestHandlers,
    pluginTests
  )
  .settings(
    name := "macroparadise-scala3",
    publish / skip := true
  )

lazy val legacyMetadataMarkerFixture =
  (project in file("legacy-metadata-marker-fixture"))
    .settings(
      name := "macroparadise-scala3-legacy-metadata-marker-fixture",
      publish / skip := true,
      Compile / packageBin / mappings ~= {
        _.filterNot {
          case (_, path) =>
            path == "paradise3/api/expander.class" ||
              path == "paradise3/api/expander.tasty"
        }
      },
      verifyLegacyMetadataMarkerArtifact := {
        val artifact = (Compile / packageBin).value
        val jar = new java.util.jar.JarFile(artifact)
        try {
          val names = scala.collection.mutable.Set.empty[String]
          val entries = jar.entries()
          while (entries.hasMoreElements) {
            names += entries.nextElement().getName
          }

          val required = Set(
            "paradise3/legacyExternalDebug.class",
            "paradise3/legacyExternalDebug.tasty"
          )
          val forbidden = Set(
            "paradise3/api/expander.class",
            "paradise3/api/expander.tasty"
          )

          require(
            required.subsetOf(names.toSet),
            s"legacy marker artifact is missing: ${(required -- names).toList.sorted.mkString(", ")}"
          )
          require(
            forbidden.intersect(names.toSet).isEmpty,
            s"legacy marker artifact exposes obsolete carrier: ${forbidden.intersect(names.toSet).toList.sorted.mkString(", ")}"
          )
        } finally jar.close()
        artifact
      }
    )

def legacyMetadataProducerProject(
    id: String,
    directory: String,
    compilerVersion: String
): Project =
  Project(id, file(directory))
    .settings(
      name := s"macroparadise-scala3-legacy-metadata-producer-$compilerVersion",
      scalaVersion := compilerVersion,
      Compile / unmanagedSourceDirectories := Seq(
        file("legacy-metadata-marker-fixture/src/main/scala").getAbsoluteFile
      ),
      Compile / packageBin / mappings ~= {
        _.filterNot {
          case (_, path) =>
            path == "paradise3/api/expander.class" ||
              path == "paradise3/api/expander.tasty"
        }
      },
      verifyLegacyMetadataMatrixArtifact := {
        val artifact = (Compile / packageBin).value
        val evidence =
          LegacyMetadataMatrixArtifact.verify(compilerVersion, artifact)
        streams.value.log.info(evidence.render)
        artifact
      },
      publish / skip := true
    )

lazy val legacyMetadataProducer384 =
  legacyMetadataProducerProject(
    "legacyMetadataProducer384",
    "legacy-metadata-producers/scala-3.8.4",
    "3.8.4"
  )

lazy val legacyMetadataProducer338 =
  legacyMetadataProducerProject(
    "legacyMetadataProducer338",
    "legacy-metadata-producers/scala-3.3.8",
    "3.3.8"
  )

lazy val macroSuspensionSpike = (project in file("macro-suspension-spike"))
  .settings(
    name := "macroparadise-scala3-macro-suspension-spike",
    Compile / scalacOptions += "-Xprint-suspension"
  )

lazy val sameModuleHandlerSpike = (project in file("same-module-handler-spike"))
  .dependsOn(plugin, pluginApi)
  .settings(
    name := "macroparadise-scala3-same-module-handler-spike",
    Compile / compile := (Compile / compile)
      .dependsOn(plugin / Compile / packageBin)
      .dependsOn(pluginApi / Compile / packageBin)
      .value,
    Compile / scalacOptions ++= {
      val pluginJar = (plugin / Compile / packageBin).value.getAbsolutePath
      val pluginApiJar = (pluginApi / Compile / packageBin).value.getAbsolutePath
      val currentOutput = (Compile / classDirectory).value.getAbsolutePath

      Seq(
        s"-Xplugin:$pluginJar",
        "-Xplugin-require:macroparadise",
        s"-P:macroparadise:handlerClasspath=$currentOutput",
        "-P:macroparadise:sameModuleHandler=sameModuleDebug:demo.SameModuleDebugExpander:demo/SameModuleDebugExpander.scala",
        "-Xprint-suspension"
      )
    }
  )

lazy val sameModuleHandlerSameFileSpike =
  (project in file("same-module-handler-same-file-spike"))
    .dependsOn(plugin, pluginApi)
    .settings(
      name := "macroparadise-scala3-same-module-handler-same-file-spike",
      Compile / compile := (Compile / compile)
        .dependsOn(plugin / Compile / packageBin)
        .dependsOn(pluginApi / Compile / packageBin)
        .value,
      Compile / scalacOptions ++= {
        val pluginJar = (plugin / Compile / packageBin).value.getAbsolutePath
        val pluginApiJar = (pluginApi / Compile / packageBin).value.getAbsolutePath
        val currentOutput = (Compile / classDirectory).value.getAbsolutePath

        Seq(
          s"-Xplugin:$pluginJar",
          "-Xplugin-require:macroparadise",
          s"-P:macroparadise:handlerClasspath=$currentOutput",
          "-P:macroparadise:sameModuleHandler=sameFileDebug:demo.SameFileDebugExpander:demo/SameFileDebug.scala",
          "-Xprint-suspension"
        )
      }
    )

lazy val sameModuleHandlerCycleSpike =
  (project in file("same-module-handler-cycle-spike"))
    .dependsOn(plugin, pluginApi)
    .settings(
      name := "macroparadise-scala3-same-module-handler-cycle-spike",
      Compile / compile := (Compile / compile)
        .dependsOn(plugin / Compile / packageBin)
        .dependsOn(pluginApi / Compile / packageBin)
        .value,
      Compile / scalacOptions ++= {
        val pluginJar = (plugin / Compile / packageBin).value.getAbsolutePath
        val pluginApiJar = (pluginApi / Compile / packageBin).value.getAbsolutePath
        val currentOutput = (Compile / classDirectory).value.getAbsolutePath

        Seq(
          s"-Xplugin:$pluginJar",
          "-Xplugin-require:macroparadise",
          s"-P:macroparadise:handlerClasspath=$currentOutput",
          "-P:macroparadise:sameModuleHandler=impossibleDebug:demo.DoesNotExistExpander:demo/MissingHandler.scala",
          "-Xprint-suspension"
        )
      }
    )

lazy val pluginApi = (project in file("plugin-api"))
  .settings(selectedPublicationSettings)
  .settings(
    name := "Macro Paradise Scala 3 Experimental Plugin API",
    moduleName := "macroparadise-scala3-plugin-api",
    crossVersion := CrossVersion.full,
    description := "Exact-build experimental external-handler contract for Macro Paradise Scala 3; exposes compiler-internal Dotty types and requires the matching compiler build.",
    makePomConfiguration ~= (_.withConfigurations(Vector(Compile, Runtime, Provided, Optional))),
    libraryDependencies += "org.scala-lang" %% "scala3-compiler" % scalaVersion.value
  )

lazy val pluginTestMarkers = (project in file("plugin-test-markers"))
  .dependsOn(pluginApi)
  .settings(
    name := "macroparadise-scala3-plugin-test-markers",
    publish / skip := true
  )

verifyPluginApiSourceProjectSplit := {
  val result = PluginApiSourceProjectSplitPolicy.verify(
    baseDirectory.value,
    (pluginApi / Compile / packageBin).value,
    (pluginTestMarkers / Compile / packageBin).value,
    (pluginApi / Compile / dependencyClasspath).value.files,
    baseDirectory.value / "project" / "experimental-plugin-api-surface-baseline.txt"
  )
  streams.value.log.info(s"plugin API source-project split verified: ${result.render}")
}

verifyBuildDependencyCoordinatePolicy := {
  BuildDependencyCoordinatePolicySpec.run()
  JdkVersionEnforcement.enforceCurrent()

  def dependency(module: ModuleID): BuildDependencyCoordinatePolicy.Dependency =
    BuildDependencyCoordinatePolicy.Dependency(
      module.organization,
      module.name,
      module.revision,
      module.configurations.getOrElse("compile"),
      module.explicitArtifacts.flatMap(_.classifier).toList
    )

  val structure = buildStructure.value
  val rootRef = thisProjectRef.value
  val rootBuildRefs = structure.allProjectRefs.filter(_.build == rootRef.build)
  val extracted = Project.extract(state.value)
  val allDependencies = rootBuildRefs.flatMap { reference =>
    extracted.getOpt(reference / libraryDependencies).getOrElse(Seq.empty)
  }
  val rootProject =
    structure.allProjectPairs.find(_._2 == rootRef).map(_._1).getOrElse {
      sys.error(s"root project ${rootRef.project} is missing from the loaded build")
    }
  val pluginApiRef = rootBuildRefs.find(_.project == "pluginApi").getOrElse {
    sys.error("pluginApi project is missing from the loaded build")
  }
  val pluginApiProject =
    structure.allProjectPairs.find(_._2 == pluginApiRef).map(_._1).getOrElse {
      sys.error("pluginApi project definition is missing from the loaded build")
    }
  val pluginTestMarkersRef = rootBuildRefs.find(_.project == "pluginTestMarkers").getOrElse {
    sys.error("pluginTestMarkers project is missing from the loaded build")
  }
  val pluginTestMarkersProject =
    structure.allProjectPairs.find(_._2 == pluginTestMarkersRef).map(_._1).getOrElse {
      sys.error("pluginTestMarkers project definition is missing from the loaded build")
    }
  val expectedPluginApiBase = (baseDirectory.value / "plugin-api").getCanonicalFile
  val expectedPluginTestMarkersBase =
    (baseDirectory.value / "plugin-test-markers").getCanonicalFile
  val shape = BuildDependencyCoordinatePolicy.BuildShape(
    scalaVersion.value,
    sbtVersion.value,
    JdkVersionEnforcement.currentDetectedVersion().feature.getOrElse(-1),
    pluginApiProject.id,
    pluginApiRef != rootRef && pluginApiProject.base.getCanonicalFile == expectedPluginApiBase,
    pluginTestMarkersProject.id,
    pluginTestMarkersRef != rootRef &&
      pluginTestMarkersProject.base.getCanonicalFile == expectedPluginTestMarkersBase,
    pluginTestMarkersProject.dependencies.exists(_.project == pluginApiRef),
    pluginApiProject.dependencies.exists(_.project == pluginTestMarkersRef),
    rootProject.aggregate.map(_.project).toSet,
    (baseDirectory.value / "project" / "experimental-plugin-api-surface-baseline.txt").isFile,
    Set(
      renderExperimentalPluginApiSurfaceBaseline.key.label,
      verifyExperimentalPluginApiSurfaceBaseline.key.label
    )
  )
  val result = BuildDependencyCoordinatePolicy.verify(
    (pluginApi / libraryDependencies).value.map(dependency),
    allDependencies.map(dependency),
    shape
  )
  require(
    result.errors.isEmpty,
    s"build dependency-coordinate policy failed: ${result.errors.mkString("; ")}"
  )
  streams.value.log.info(
    s"build dependency-coordinate policy verified: ${result.render} syntheticCases=${BuildDependencyCoordinatePolicySpec.CaseCount}/${BuildDependencyCoordinatePolicySpec.CaseCount}"
  )
}

verifyPluginApiCleanResolution := {
  def dependency(module: ModuleID): BuildDependencyCoordinatePolicy.Dependency =
    BuildDependencyCoordinatePolicy.Dependency(
      module.organization,
      module.name,
      module.revision,
      module.configurations.getOrElse("compile"),
      module.explicitArtifacts.flatMap(_.classifier).toList
    )

  val structure = buildStructure.value
  val rootRef = thisProjectRef.value
  val rootBuildRefs = structure.allProjectRefs.filter(_.build == rootRef.build)
  val extracted = Project.extract(state.value)
  val allDependencies = rootBuildRefs.flatMap { reference =>
    extracted.getOpt(reference / libraryDependencies).getOrElse(Seq.empty)
  }
  val rootProject = structure.allProjectPairs.find(_._2 == rootRef).map(_._1).get
  val pluginApiRef = rootBuildRefs.find(_.project == "pluginApi").get
  val pluginApiProject = structure.allProjectPairs.find(_._2 == pluginApiRef).map(_._1).get
  val pluginTestMarkersRef = rootBuildRefs.find(_.project == "pluginTestMarkers").get
  val pluginTestMarkersProject =
    structure.allProjectPairs.find(_._2 == pluginTestMarkersRef).map(_._1).get
  val shape = BuildDependencyCoordinatePolicy.BuildShape(
    scalaVersion.value,
    sbtVersion.value,
    JdkVersionEnforcement.currentDetectedVersion().feature.getOrElse(-1),
    pluginApiProject.id,
    pluginApiProject.base.getCanonicalFile == (baseDirectory.value / "plugin-api").getCanonicalFile,
    pluginTestMarkersProject.id,
    pluginTestMarkersProject.base.getCanonicalFile ==
      (baseDirectory.value / "plugin-test-markers").getCanonicalFile,
    pluginTestMarkersProject.dependencies.exists(_.project == pluginApiRef),
    pluginApiProject.dependencies.exists(_.project == pluginTestMarkersRef),
    rootProject.aggregate.map(_.project).toSet,
    (baseDirectory.value / "project" / "experimental-plugin-api-surface-baseline.txt").isFile,
    Set(
      renderExperimentalPluginApiSurfaceBaseline.key.label,
      verifyExperimentalPluginApiSurfaceBaseline.key.label
    )
  )
  val result = PluginApiCleanResolution.run(
    baseDirectory.value,
    target.value / "plugin-api-clean-resolution",
    (pluginApi / libraryDependencies).value.map(dependency),
    allDependencies.map(dependency),
    shape
  )
  require(
    result.classification != PluginApiCleanResolution.FailedClassification,
    s"pluginApi clean resolution proof failed: ${result.render}"
  )
  require(
    result.disposableRepositoryDeleted && result.taskOwnedCacheDeleted,
    s"pluginApi clean resolution proof did not delete disposable state: ${result.render}"
  )
  val log = streams.value.log
  if (result.isBlocked)
    log.warn(s"pluginApi clean resolution proof environmentally blocked: ${result.render}")
  else
    log.info(s"pluginApi clean resolution verified: ${result.render}")
}

renderExperimentalPluginApiSurfaceBaseline := {
  val evidence = target.value / "experimental-plugin-api-surface-baseline-render"
  val candidate = evidence / "experimental-plugin-api-surface-baseline.txt"
  val surface = ExperimentalPluginApiSurface.renderBaselineCandidate(
    (pluginApi / Compile / packageBin).value,
    (pluginTestMarkers / Compile / packageBin).value,
    candidate,
    ExperimentalPluginApiSurface.Config(
      scalaVersion.value,
      sbtVersion.value,
      version.value
    ),
    evidence
  )
  streams.value.log.info(
    s"rendered experimental pluginApi surface candidate: sha256=${surface.normalizedSha256} path=${candidate.getAbsolutePath}"
  )
  candidate
}

verifyExperimentalPluginApiSurfaceBaseline := {
  ExperimentalPluginApiSurfaceSpec.run()
  val result = ExperimentalPluginApiSurface.verify(
    baseDirectory.value,
    (pluginApi / Compile / packageBin).value,
    (pluginTestMarkers / Compile / packageBin).value,
    (pluginApi / Compile / dependencyClasspath).value.files,
    baseDirectory.value / "project" / "experimental-plugin-api-surface-baseline.txt",
    baseDirectory.value / "plugin-api-surface-probe" / "positive" / "IsolatedPluginApiSurfaceProbe.scala",
    baseDirectory.value / "plugin-api-surface-probe" / "negative" / "ForbiddenImplementationProbe.scala",
    ExperimentalPluginApiSurface.Config(
      scalaVersion.value,
      sbtVersion.value,
      version.value
    ),
    target.value / "experimental-plugin-api-surface-baseline"
  )
  streams.value.log.info(
    s"experimental pluginApi surface baseline verified: ${result.render} verifierSpec=${ExperimentalPluginApiSurfaceSpec.CaseCount}/${ExperimentalPluginApiSurfaceSpec.CaseCount} evidence=${result.evidenceDirectory.getAbsolutePath}"
  )
}

renderExperimentalHandlerContractArtifact := {
  ExperimentalHandlerContractArtifactSpec.run()
  val destination =
    target.value / "experimental-plugin-api-handler-contract" /
      ExperimentalHandlerContractArtifact.CandidateBasename
  val identity = ExperimentalHandlerContractArtifact.render(
    (pluginApi / Compile / packageBin).value,
    (pluginTestMarkers / Compile / packageBin).value,
    baseDirectory.value / "project" / "experimental-plugin-api-surface-baseline.txt",
    destination,
    ExperimentalHandlerContractArtifact.Config(
      scalaVersion.value,
      sbtVersion.value,
      version.value
    )
  )
  streams.value.log.info(
    s"rendered experimental handler-contract candidate: ${identity.render} verifierSpec=${ExperimentalHandlerContractArtifactSpec.CaseCount}/${ExperimentalHandlerContractArtifactSpec.CaseCount}"
  )
  destination
}

verifyExperimentalHandlerContractArtifact := {
  ExperimentalHandlerContractArtifactSpec.run()
  val destination =
    target.value / "experimental-plugin-api-handler-contract" /
      ExperimentalHandlerContractArtifact.CandidateBasename
  val result = ExperimentalHandlerContractArtifact.verify(
    baseDirectory.value,
    (pluginApi / Compile / packageBin).value,
    (pluginTestMarkers / Compile / packageBin).value,
    (pluginApi / Compile / dependencyClasspath).value.files,
    baseDirectory.value / "project" / "experimental-plugin-api-surface-baseline.txt",
    baseDirectory.value / "plugin-test-handlers" / "src" / "main" / "scala",
    baseDirectory.value / "plugin-api-handler-contract-probe" / "positive" /
      "IndependentMarkerAndHandler.scala",
    Vector(
      (
        "fixture-marker",
        baseDirectory.value / "plugin-api-handler-contract-probe" / "negative" /
          "ExternalDebugMarkerUnavailable.scala",
        "externalDebug"
      ),
      (
        "fixture-support",
        baseDirectory.value / "plugin-api-handler-contract-probe" / "negative" /
          "MetadataInitializationProbeUnavailable.scala",
        "MetadataInitializationProbe"
      ),
      (
        "plugin-implementation",
        baseDirectory.value / "plugin-api-handler-contract-probe" / "negative" /
          "PluginImplementationUnavailable.scala",
        "macroparadise"
      )
    ),
    destination,
    target.value / "experimental-plugin-api-handler-contract-verification",
    ExperimentalHandlerContractArtifact.Config(
      scalaVersion.value,
      sbtVersion.value,
      version.value
    ),
    ExperimentalHandlerContractArtifactSpec.CaseCount
  )
  streams.value.log.info(
    s"experimental handler-contract artifact verified: ${result.render} evidence=${result.evidenceDirectory.getAbsolutePath}"
  )
}

verifyIndependentPrecompiledHandlerPackagedConsumer := {
  IndependentPrecompiledHandlerPackagedConsumerSpec.run(baseDirectory.value)
  val result = IndependentPrecompiledHandlerPackagedConsumer.verify(
    baseDirectory.value,
    (pluginApi / Compile / packageBin).value,
    (plugin / Compile / packageBin).value,
    (pluginApi / Compile / dependencyClasspath).value.files,
    (plugin / Compile / dependencyClasspath).value.files,
    baseDirectory.value / "plugin-api-handler-contract-probe" / "positive" /
      "IndependentMarkerAndHandler.scala",
    baseDirectory.value / "plugin-api-handler-contract-probe" / "e2e" /
      "IndependentPackagedConsumer.scala",
    target.value / "independent-precompiled-handler-packaged-consumer",
    IndependentPrecompiledHandlerPackagedConsumer.Config(
      scalaVersion.value,
      sbtVersion.value,
      version.value
    ),
    IndependentPrecompiledHandlerPackagedConsumerSpec.CaseCount
  )
  streams.value.log.info(
    s"independent precompiled handler packaged consumer verified: ${result.render} evidence=${result.evidenceDirectory.getAbsolutePath}"
  )
}

verifyExternalHandlerAuthoringStarter := {
  ExternalHandlerAuthoringStarterSpec.run()
  val result = ExternalHandlerAuthoringStarter.verify(
    baseDirectory.value,
    (plugin / Compile / packageBin).value,
    (pluginApi / Compile / packageBin).value,
    target.value / "external-handler-authoring-starter",
    ExternalHandlerAuthoringStarter.Config(
      scalaVersion.value,
      sbtVersion.value,
      java.lang.Runtime.version().feature()
    )
  )
  streams.value.log.info(
    s"external handler authoring starter verified: ${result.render} evidence=${result.evidenceDirectory.getAbsolutePath} " +
      s"verifierSpec=${ExternalHandlerAuthoringStarterSpec.CaseCount}/${ExternalHandlerAuthoringStarterSpec.CaseCount}"
  )
}


verifyIndependentExternalSbtConsumerFromLocalRepository := {
  IndependentExternalSbtConsumerSpec.run()
  val result = IndependentExternalSbtConsumer.verify(
    baseDirectory.value,
    target.value / "independent-external-sbt-consumer-local-repository",
    IndependentExternalSbtConsumer.Config(
      scalaVersion.value,
      sbtVersion.value,
      version.value
    ),
    IndependentExternalSbtConsumerSpec.CaseCount
  )
  streams.value.log.info(
    s"independent external sbt consumer from task-owned local repository verified: ${result.render} evidence=${result.evidenceDirectory.getAbsolutePath}"
  )
}


lazy val plugin = (project in file("plugin"))
  .dependsOn(pluginApi % "compile-internal", pluginTestMarkers % "test->compile")
  .settings(commonSettings)
  .settings(selectedPublicationSettings)
  .settings(
    name := "Macro Paradise Scala 3 Experimental Compiler Plugin",
    moduleName := "macroparadise-scala3-plugin",
    crossVersion := CrossVersion.full,
    description := "Exact-build experimental Scala 3 compiler plugin for pre-typer annotation expansion; embeds its runtime API classes and requires the matching Scala compiler build.",
    makePomConfiguration ~= (_.withConfigurations(Vector(Compile, Runtime, Provided, Optional))),
    Compile / packageBin / mappings ++=
      (pluginApi / Compile / packageBin / mappings).value.filter {
        case (_, path) => path.startsWith("paradise3/api/")
      },
    libraryDependencies ++= Seq(
      "org.scala-lang" %% "scala3-compiler" % scalaVersion.value,
      "org.scala-lang" %% "scala3-tasty-inspector" % scalaVersion.value
    ),
    Test / test := (Test / test)
      .dependsOn(legacyMetadataMarkerFixture / Compile / packageBin)
      .dependsOn(pluginTestMarkers / Compile / packageBin)
      .value,
    Test / classLoaderLayeringStrategy := ClassLoaderLayeringStrategy.Flat
  )

lazy val pluginTestHandlers = (project in file("plugin-test-handlers"))
  .dependsOn(pluginApi)
  .settings(
    name := "macroparadise-scala3-plugin-test-handlers",
    libraryDependencies += "org.scala-lang" %% "scala3-compiler" % scalaVersion.value,
    publish / skip := true
  )

def legacyMetadataConsumerProject(
    id: String,
    directory: String,
    producer: ProjectReference
): Project =
  Project(id, file(directory))
    .dependsOn(plugin, pluginApi, pluginTestHandlers)
    .settings(
      name := s"macroparadise-scala3-$id",
      Compile / unmanagedSourceDirectories := Seq(
        file("legacy-metadata-matrix-consumer/src/main/scala").getAbsoluteFile
      ),
      Compile / unmanagedJars +=
        Attributed.blank((producer / Compile / packageBin).value),
      Compile / compile := (Compile / compile)
        .dependsOn(producer / verifyLegacyMetadataMatrixArtifact)
        .dependsOn(plugin / Compile / packageBin)
        .dependsOn(pluginApi / Compile / packageBin)
        .dependsOn(pluginTestHandlers / Compile / packageBin)
        .value,
      Compile / scalacOptions ++= {
        val pluginJar = (plugin / Compile / packageBin).value.getAbsolutePath
        val pluginApiJar =
          (pluginApi / Compile / packageBin).value.getAbsolutePath
        val legacyMarkerJar =
          (producer / Compile / packageBin).value.getAbsolutePath
        val handlerJar =
          (pluginTestHandlers / Compile / packageBin).value.getAbsolutePath

        Seq(
          s"-Xplugin:${Seq(pluginJar, legacyMarkerJar).mkString(java.io.File.pathSeparator)}",
          "-Xplugin-require:macroparadise",
          s"-P:macroparadise:handlerClasspath=$handlerJar"
        )
      },
      publish / skip := true
    )

lazy val legacyMetadataConsumer384 =
  legacyMetadataConsumerProject(
    "legacyMetadataConsumer384",
    "legacy-metadata-consumers/scala-3.8.4",
    legacyMetadataProducer384
  )

lazy val legacyMetadataConsumer338 =
  legacyMetadataConsumerProject(
    "legacyMetadataConsumer338",
    "legacy-metadata-consumers/scala-3.3.8",
    legacyMetadataProducer338
  )

def packagedStructuredTastyConsumerProject(
    id: String,
    directory: String,
    producer: ProjectReference
): Project =
  Project(id, file(directory))
    .dependsOn(plugin, pluginApi, pluginTestMarkers, pluginTestHandlers)
    .settings(
      name := s"macroparadise-scala3-$id",
      Compile / unmanagedSourceDirectories := Seq(
        file("legacy-metadata-matrix-consumer/src/main/scala").getAbsoluteFile,
        file("experimental-structured-metadata-consumer/src/main/scala/current").getAbsoluteFile
      ),
      Compile / unmanagedJars +=
        Attributed.blank((producer / Compile / packageBin).value),
      Compile / compile := (Compile / compile)
        .dependsOn(producer / Compile / packageBin)
        .dependsOn(plugin / Compile / packageBin)
        .dependsOn(pluginApi / Compile / packageBin)
        .dependsOn(pluginTestMarkers / Compile / packageBin)
        .dependsOn(pluginTestHandlers / Compile / packageBin)
        .value,
      Compile / scalacOptions ++= {
        val pluginJar = (plugin / Compile / packageBin).value.getAbsolutePath
        val pluginApiJar =
          (pluginApi / Compile / packageBin).value.getAbsolutePath
        val currentMarkerJar =
          (pluginTestMarkers / Compile / packageBin).value.getAbsolutePath
        val legacyMarkerJar =
          (producer / Compile / packageBin).value.getAbsolutePath
        val handlerJar =
          (pluginTestHandlers / Compile / packageBin).value.getAbsolutePath
        val inspectorCandidates =
          (plugin / Compile / dependencyClasspath).value.files.filter { file =>
            file.getName ==
              s"scala3-tasty-inspector_3-${scalaVersion.value}.jar"
          }
        require(
          inspectorCandidates.size == 1,
          s"expected exactly one pinned scala3-tasty-inspector artifact, found: ${inspectorCandidates.mkString(", ")}"
        )
        val inspectorJar = inspectorCandidates.head.getAbsolutePath
        val tracePath =
          (target.value / "packaged-structured-tasty.trace").getAbsolutePath
        IO.delete(file(tracePath))

        Seq(
          s"-Xplugin:${Seq(pluginJar, currentMarkerJar, legacyMarkerJar, inspectorJar).mkString(java.io.File.pathSeparator)}",
          "-Xplugin-require:macroparadise",
          s"-P:macroparadise:handlerClasspath=$handlerJar",
          s"-P:macroparadise:metadataReaderTrace=$tracePath",
          s"-P:macroparadise:structuredMetadataPath=$legacyMarkerJar"
        )
      },
      verifyPackagedStructuredTastyLane := {
        (Compile / runMain)
          .toTask(" LegacyMetadataMatrixConsumer")
          .value
        (Compile / runMain)
          .toTask(" CurrentRuntimeStructuredMetadataConsumer")
          .value
        val traceFile = target.value / "packaged-structured-tasty.trace"
        require(traceFile.isFile, s"missing structured trace: $traceFile")

        val legacyTraceLines =
          IO.readLines(traceFile).filter(_.contains("paradise3.legacyExternalDebug"))
        val expectedLegacy =
          List(
            "runtime paradise3.legacyExternalDebug NotFound",
            "structured paradise3.legacyExternalDebug Found(demo.LegacyExternalDebugExpander)"
          )
        require(
          legacyTraceLines == expectedLegacy,
          s"unexpected packaged structured TASTy trace for $id: ${legacyTraceLines.mkString(" | ")}"
        )
        val currentTraceLines =
          IO.readLines(traceFile).filter(_.contains("paradise3.externalDebug"))
        val expectedCurrent =
          List(
            "runtime paradise3.externalDebug Found(demo.ExternalDebugExpander)"
          )
        require(
          currentTraceLines == expectedCurrent,
          s"unexpected current-marker runtime trace for $id: ${currentTraceLines.mkString(" | ")}"
        )

        streams.value.log.info(
          s"$id: legacy runtime=NotFound -> structured=Found -> string=not-attempted; current runtime=Found -> compatibility=not-attempted; both consumers ran"
        )
      },
      publish / skip := true
    )

lazy val packagedStructuredTastyConsumerPinned =
  packagedStructuredTastyConsumerProject(
    "packagedStructuredTastyConsumerPinned",
    "packaged-structured-tasty-consumers/pinned",
    legacyMetadataMarkerFixture
  )

lazy val packagedStructuredTastyConsumer384 =
  packagedStructuredTastyConsumerProject(
    "packagedStructuredTastyConsumer384",
    "packaged-structured-tasty-consumers/scala-3.8.4",
    legacyMetadataProducer384
  )

lazy val packagedStructuredTastyConsumer338 =
  packagedStructuredTastyConsumerProject(
    "packagedStructuredTastyConsumer338",
    "packaged-structured-tasty-consumers/scala-3.3.8",
    legacyMetadataProducer338
  )

verifyPackagedStructuredTastyFeasibility := Def
  .sequential(
    packagedStructuredTastyConsumerPinned / clean,
    packagedStructuredTastyConsumerPinned / verifyPackagedStructuredTastyLane,
    packagedStructuredTastyConsumer384 / clean,
    packagedStructuredTastyConsumer384 / verifyPackagedStructuredTastyLane,
    packagedStructuredTastyConsumer338 / clean,
    packagedStructuredTastyConsumer338 / verifyPackagedStructuredTastyLane
  )
  .value

verifyExperimentalStructuredMetadataPositiveLanes :=
  verifyPackagedStructuredTastyFeasibility.value

verifyExperimentalStructuredMetadataNegativeLanes := {
  import scala.sys.process.Process

  val pluginJar = (plugin / Compile / packageBin).value
  val pluginApiJar = (pluginApi / Compile / packageBin).value
  val handlerJar = (pluginTestHandlers / Compile / packageBin).value
  val markerJar =
    (legacyMetadataMarkerFixture / verifyLegacyMetadataMarkerArtifact).value
  val dependencyFiles =
    (plugin / Compile / dependencyClasspath).value.files.filter(_.isFile)
  val inspectorCandidates = dependencyFiles.filter(
    _.getName == s"scala3-tasty-inspector_3-${scalaVersion.value}.jar"
  )
  require(
    inspectorCandidates.size == 1,
    s"expected exactly one pinned inspector artifact, found ${inspectorCandidates.mkString(", ")}"
  )
  val inspectorJar = inspectorCandidates.head
  val compilerCandidates = dependencyFiles.filter(
    _.getName == s"scala3-compiler_3-${scalaVersion.value}.jar"
  )
  require(
    compilerCandidates.size == 1,
    s"expected exactly one pinned compiler artifact, found ${compilerCandidates.mkString(", ")}"
  )
  val compilerJar = compilerCandidates.head
  val compilerClasspath =
    dependencyFiles.filterNot(_ == inspectorJar).map(_.getAbsolutePath)
  val consumerClasspath =
    (compilerClasspath ++
      Seq(
        pluginApiJar.getAbsolutePath,
        markerJar.getAbsolutePath,
        handlerJar.getAbsolutePath
      )).distinct

  val evidenceDirectory =
    target.value / "experimental-structured-metadata-negative-lanes"
  IO.delete(evidenceDirectory)
  IO.createDirectory(evidenceDirectory)

  val wrongInspector =
    evidenceDirectory / "scala3-tasty-inspector_3-0.0.0.jar"
  IO.copyFile(inspectorJar, wrongInspector)
  val malformedJar = evidenceDirectory / "malformed-marker.jar"
  IO.write(malformedJar, "not a jar")
  val nonJar = evidenceDirectory / "marker.txt"
  IO.write(nonJar, "not a jar path")
  val unreadableJar = evidenceDirectory / "unreadable-marker.jar"
  IO.copyFile(markerJar, unreadableJar)
  java.nio.file.Files.setPosixFilePermissions(
    unreadableJar.toPath,
    java.util.Collections.emptySet[java.nio.file.attribute.PosixFilePermission]()
  )
  val emptyJar = evidenceDirectory / "empty-marker.jar"
  val emptyJarStream =
    new java.util.jar.JarOutputStream(new java.io.FileOutputStream(emptyJar))
  emptyJarStream.close()

  val arguments =
    Seq(
      (file(sys.props("java.home")) / "bin" / "java").getAbsolutePath,
      compilerClasspath.mkString(java.io.File.pathSeparator),
      consumerClasspath.mkString(java.io.File.pathSeparator),
      pluginJar.getAbsolutePath,
      pluginApiJar.getAbsolutePath,
      handlerJar.getAbsolutePath,
      markerJar.getAbsolutePath,
      inspectorJar.getAbsolutePath,
      wrongInspector.getAbsolutePath,
      compilerJar.getAbsolutePath,
      malformedJar.getAbsolutePath,
      nonJar.getAbsolutePath,
      unreadableJar.getAbsolutePath,
      emptyJar.getAbsolutePath,
      file("legacy-metadata-matrix-consumer/src/main/scala/LegacyMetadataMatrixConsumer.scala").getAbsolutePath,
      file("experimental-structured-metadata-consumer/src/main/scala/unrelated/UnrelatedMarker.scala").getAbsolutePath,
      file("experimental-structured-metadata-consumer/src/main/scala/unrelated/UnrelatedStructuredMetadataConsumer.scala").getAbsolutePath,
      evidenceDirectory.getAbsolutePath
    )
  val exitCode =
    Process(file("scripts/verify-structured-metadata-distribution-negatives.sh").getAbsolutePath +: arguments).!
  require(exitCode == 0, s"experimental structured metadata negative lanes failed with exit code $exitCode")
}

verifyExperimentalStructuredMetadataDistributionContract := Def
  .sequential(
    verifyExperimentalStructuredMetadataPositiveLanes,
    verifyExperimentalStructuredMetadataNegativeLanes
  )
  .value

verifyLegacyMetadataCompatibilityMatrix := {
  val artifact384 =
    (legacyMetadataProducer384 / verifyLegacyMetadataMatrixArtifact).value
  val artifact338 =
    (legacyMetadataProducer338 / verifyLegacyMetadataMatrixArtifact).value
  val pluginApiJar = (pluginApi / Compile / packageBin).value
  val classpath = (plugin / Test / fullClasspath).value.files
  val scalaRunner = (plugin / Test / runner).value

  scalaRunner
    .run(
      "macroparadise.LegacyMetadataCompatibilityMatrixProbe",
      classpath,
      Seq(
        "3.8.4",
        artifact384.getAbsolutePath,
        "3.3.8",
        artifact338.getAbsolutePath,
        pluginApiJar.getAbsolutePath
      ),
      streams.value.log
    )
    .get
}


lazy val pluginTests = (project in file("plugin-tests"))
  .dependsOn(plugin, pluginApi, pluginTestMarkers, pluginTestHandlers)
  .settings(commonSettings)
  .settings(
    name := "macroparadise-scala3-plugin-tests",
    publish / skip := true,
    libraryDependencies += "org.scala-lang" %% "scala3-compiler" % scalaVersion.value % Test,
    Compile / unmanagedJars +=
      Attributed.blank((legacyMetadataMarkerFixture / Compile / packageBin).value),
    Compile / compile := (Compile / compile)
      .dependsOn(
        legacyMetadataMarkerFixture / verifyLegacyMetadataMarkerArtifact
      )
      .dependsOn(plugin / Compile / packageBin)
      .dependsOn(pluginApi / Compile / packageBin)
      .dependsOn(pluginTestMarkers / Compile / packageBin)
      .dependsOn(pluginTestHandlers / Compile / packageBin)
      .value,
    Compile / scalacOptions ++= {
      val pluginJar = (plugin / Compile / packageBin).value.getAbsolutePath
      val pluginApiJar = (pluginApi / Compile / packageBin).value.getAbsolutePath
      val markerJar =
        (pluginTestMarkers / Compile / packageBin).value.getAbsolutePath
      val legacyMarkerJar =
        (legacyMetadataMarkerFixture / Compile / packageBin).value.getAbsolutePath
      val handlerJar = (pluginTestHandlers / Compile / packageBin).value.getAbsolutePath

      Seq(
        s"-Xplugin:${Seq(pluginJar, markerJar, legacyMarkerJar).mkString(java.io.File.pathSeparator)}",
        "-Xplugin-require:macroparadise",
        s"-P:macroparadise:handlerClasspath=$handlerJar",
        "-P:macroparadise:handler=demo.ExternalMarkerExpander"
      )
    },
    Test / test := (Test / test)
      .dependsOn(legacyMetadataMarkerFixture / Compile / packageBin)
      .dependsOn(plugin / Compile / packageBin)
      .dependsOn(pluginApi / Compile / packageBin)
      .dependsOn(pluginTestMarkers / Compile / packageBin)
      .dependsOn(pluginTestHandlers / Compile / packageBin)
      .value
  )
