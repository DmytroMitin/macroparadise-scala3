ThisBuild / version := "0.2.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.8.4"
ThisBuild / crossScalaVersions := Seq("3.3.8", "3.8.4", "3.9.0")
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

lazy val Legacy011Compatibility = config("legacy011Compatibility").hide
ThisBuild / pomIncludeRepository := (_ => false)

Global / onLoad ~= { previous =>
  state =>
    JdkVersionEnforcement.enforceCurrent()
    previous(state)
}

lazy val commonSettings = Seq(
  libraryDependencies += "org.scalameta" %% "munit" % "1.2.4" % Test,
  Test / testOptions += {
    val activeScalaVersion = scalaVersion.value
    val activeProjectVersion = version.value
    Tests.Setup(() => {
      System.setProperty("macroparadise.testScalaVersion", activeScalaVersion)
      System.setProperty("macroparadise.testProjectVersion", activeProjectVersion)
    })
  }
)

lazy val selectedPublicationSettings = Seq(
  publish / skip := false,
  Compile / packageBin / mappings += file("LICENSE") -> "META-INF/LICENSE",
  Compile / packageSrc / mappings += file("LICENSE") -> "META-INF/LICENSE",
  Compile / packageDoc / mappings += file("LICENSE") -> "META-INF/LICENSE"
)

def experimentalPluginApiSurfaceBaseline(root: File, exactScalaVersion: String): File =
  root / "project" / "experimental-plugin-api-surface-baseline.txt"

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
  val scripts = Vector(
    "test-hosted-ci-matrix.py",
    "test-release-configuration.py",
    "test-check-release-repository.py",
    "test-rehearse-release-signing.py"
  )
  scripts.foreach { name =>
    val script = baseDirectory.value / "scripts" / name
    val exit = scala.sys.process.Process(Seq("python3", script.getAbsolutePath), baseDirectory.value).!
    require(exit == 0, s"consumer/release configuration check $name failed with exit $exit")
  }
  streams.value.log.info(s"consumer/release configuration verified: scripts=${scripts.mkString(",")}")
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
    "pluginTests" -> (pluginTests / publish / skip).value,
    "embeddedProducerFixture" -> (embeddedProducerFixture / publish / skip).value,
    "embeddedProducerOrdinaryMarker" -> (embeddedProducerOrdinaryMarker / publish / skip).value,
    "embeddedProducerOrdinaryHandler" -> (embeddedProducerOrdinaryHandler / publish / skip).value,
    "embeddedProducerPackaging" -> (embeddedProducerPackaging / publish / skip).value,
    "embeddedProducerConsumer" -> (embeddedProducerConsumer / publish / skip).value,
    "embeddedProducerNegativeTarget" -> (embeddedProducerNegativeTarget / publish / skip).value
  )
  val publishable = Vector(
    "pluginApi" -> (pluginApi / publish / skip).value,
    "embeddedProducerPlugin" -> (embeddedProducerPlugin / publish / skip).value,
    "plugin" -> (plugin / publish / skip).value
  )
  require(internalSkips.forall(_._2), s"internal publication enabled: ${internalSkips.filterNot(_._2).map(_._1).mkString(", ")}")
  require(publishable.forall(!_._2), s"selected user artifact remains skipped: ${publishable.filter(_._2).map(_._1).mkString(", ")}")
  require(
    (plugin / publishTo).value.isEmpty &&
      (pluginApi / publishTo).value.isEmpty &&
      (embeddedProducerPlugin / publishTo).value.isEmpty,
    "product publication destination is configured"
  )
  require(
    (plugin / credentials).value.isEmpty &&
      (pluginApi / credentials).value.isEmpty &&
      (embeddedProducerPlugin / credentials).value.isEmpty,
    "product publication credentials are configured"
  )
  require(
    (plugin / Compile / packageSrc / publishArtifact).value &&
      (pluginApi / Compile / packageSrc / publishArtifact).value &&
      (embeddedProducerPlugin / Compile / packageSrc / publishArtifact).value,
    "source artifacts are disabled"
  )
  require(
    (plugin / Compile / packageDoc / publishArtifact).value &&
      (pluginApi / Compile / packageDoc / publishArtifact).value &&
      (embeddedProducerPlugin / Compile / packageDoc / publishArtifact).value,
    "documentation artifacts are disabled"
  )
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
    embeddedProducerPlugin / Test / test,
    pluginTests / Test / test,
    verifyExperimentalStructuredMetadataDistributionContract,
    verifyLegacyMetadataCompatibilityMatrix,
    verifyPluginApiSourceProjectSplit,
    Def.sequential(
      pluginApi / Compile / packageBin,
      embeddedProducerPlugin / Compile / packageBin,
      verifyEmbeddedProducerContract,
      verifyEmbeddedProducerBodyEditProtocol,
      verifyEmbeddedProducerNegativeMatrix,
      plugin / Compile / packageBin
    ),
    verifyExperimentalPluginApiSurfaceBaseline,
    verifyExperimentalHandlerContractArtifact,
    verifyIndependentPrecompiledHandlerPackagedConsumer,
    verifyExternalHandlerAuthoringStarter,
    verifyIndependentExternalSbtConsumerFromLocalRepository,
    Def.sequential(
      verifySbtPrecompiledIntegrationModule,
      verifyPublicEmbeddedProducerStarter,
      verifyEmbeddedProducerSbtIntegrationMatrix
    ),
    Def.sequential(
      verifySbtPrecompiledIntegrationExternalMatrix,
      verifyUserOnboardingThreeModeSetup
    ),
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

lazy val verifySbtPrecompiledIntegrationExternalMatrix =
  taskKey[Unit]("Verify the sbt integration dependency-only invalidation matrix")

lazy val verifyUserOnboardingThreeModeSetup =
  taskKey[Unit]("Verify the exact manual, local-project, and published-module onboarding fixture")

lazy val verifySbtPrecompiledIntegrationModule =
  taskKey[Unit]("Verify the source-built sbt integration module in its sbt 1.x / Scala 2.12 universe")


lazy val verifyPublicEmbeddedProducerStarter =
  taskKey[Unit]("Verify the public embedded producer starter on the selected exact Scala line")


lazy val verifyEmbeddedProducerSbtIntegrationMatrix =
  taskKey[Unit]("Verify embedded producer derived roles through the four supported build quadrants")


lazy val embeddedMarkerRoleJar =
  taskKey[File]("Package generated embedded marker classes without handler-role classes")

lazy val embeddedHandlerRoleJar =
  taskKey[File]("Package generated embedded companions and adapters without marker-role classes")

lazy val embeddedCombinedProducerJar =
  taskKey[File]("Package the unsplit embedded producer output for role-collision rejection")

lazy val verifyEmbeddedProducerContract =
  taskKey[Unit]("Verify embedded generation, split roles, unchanged consumer loading, and runtime behavior")

lazy val verifyEmbeddedProducerBodyEditProtocol =
  taskKey[Unit]("Verify clean no-op and transform-body-only producer rebuild semantics")

lazy val verifyEmbeddedProducerNegativeMatrix =
  taskKey[Unit]("Verify the 15-category embedded producer diagnostic matrix in isolated outputs")


lazy val root = (project in file("."))
  .configs(Legacy011Compatibility)
  .settings(inConfig(Legacy011Compatibility)(Defaults.configSettings))
  .aggregate(
    legacyMetadataMarkerFixture,
    pluginApi,
    embeddedProducerPlugin,
    plugin,
    pluginTestMarkers,
    pluginTestHandlers,
    pluginTests
  )
  .settings(
    name := "macroparadise-scala3",
    publish / skip := true,
    libraryDependencies +=
      ("com.github.dmytromitin" % "macroparadise-scala3-plugin-api" % "0.1.1")
        .cross(CrossVersion.full) % Legacy011Compatibility
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
        "-P:macroparadise:sameModuleHandler=sameModuleDebug:demo.SameModuleDebugExpander:demo/SameModuleDebugAnnotation.scala:demo/SameModuleDebugExpander.scala",
        "-P:macroparadise:sameModuleSourceIdentity=sha256:0000000000000000000000000000000000000000000000000000000000000000",
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
          "-P:macroparadise:sameModuleHandler=sameFileDebug:demo.SameFileDebugExpander:demo/SameFileDebug.scala:demo/SameFileDebug.scala",
          "-P:macroparadise:sameModuleSourceIdentity=sha256:0000000000000000000000000000000000000000000000000000000000000000",
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
          "-P:macroparadise:sameModuleHandler=impossibleDebug:demo.DoesNotExistExpander:demo/ImpossibleDebugAnnotation.scala:demo/MissingHandler.scala",
          "-P:macroparadise:sameModuleSourceIdentity=sha256:0000000000000000000000000000000000000000000000000000000000000000",
          "-Xprint-suspension"
        )
      }
    )

lazy val embeddedProducerPlugin =
  (project in file("embedded-producer-plugin"))
    .dependsOn(pluginApi % "compile-internal")
    .settings(selectedPublicationSettings)
    .settings(
      name := "Macro Paradise Scala 3 Embedded Producer Plugin",
      moduleName := "macroparadise-scala3-embedded-producer-plugin",
      crossVersion := CrossVersion.full,
      description := "Exact-build producer-only compiler plugin for the experimental embeddedExpander declaration frontend.",
      libraryDependencies += "org.scala-lang" %% "scala3-compiler" % scalaVersion.value,
      Compile / unmanagedSourceDirectories +=
        (Compile / sourceDirectory).value / s"scala-${scalaVersion.value}",
      Compile / packageBin / mappings ++=
        (pluginApi / Compile / packageBin / mappings).value.filter {
          case (_, path) => path.startsWith("paradise3/api/")
        },
      Test / test := (Test / runMain)
        .toTask(" macroparadise.embedded.EmbeddedSameModuleConfigurationSpec")
        .value
    )

lazy val embeddedProducerFixture =
  (project in file("embedded-producer-plugin-fixture/producer"))
    .dependsOn(pluginApi)
    .settings(
      name := "macroparadise-scala3-embedded-producer-fixture",
      publish / skip := true,
      libraryDependencies += "org.scala-lang" %% "scala3-compiler" % scalaVersion.value,
      Compile / compile := (Compile / compile)
        .dependsOn(embeddedProducerPlugin / Compile / packageBin)
        .value,
      Compile / scalacOptions += "-Xno-forwarders",
      Compile / scalacOptions +=
        s"-Xplugin:${(embeddedProducerPlugin / Compile / packageBin).value.getAbsolutePath}",
      Compile / scalacOptions +=
        "-Xplugin-require:macroparadise-embedded-producer"
    )

lazy val embeddedProducerOrdinaryMarker =
  (project in file("embedded-producer-plugin-fixture/ordinary-marker"))
    .dependsOn(pluginApi)
    .settings(
      name := "macroparadise-scala3-embedded-producer-ordinary-marker-fixture",
      publish / skip := true
    )

lazy val embeddedProducerOrdinaryHandler =
  (project in file("embedded-producer-plugin-fixture/ordinary-handler"))
    .dependsOn(pluginApi)
    .settings(
      name := "macroparadise-scala3-embedded-producer-ordinary-handler-fixture",
      publish / skip := true,
      libraryDependencies += "org.scala-lang" %% "scala3-compiler" % scalaVersion.value
    )

def embeddedProducerZip(classes: File, names: Seq[String], output: File): File = {
  val mappings = names.distinct.sorted.map { name =>
    val source = classes / name
    require(source.isFile, s"missing generated producer entry $name in $classes")
    source -> name
  }
  IO.delete(output)
  IO.createDirectory(output.getParentFile)
  IO.zip(mappings, output, Some(0L))
  output.getCanonicalFile
}

def embeddedProducerSha256(file: File): String = {
  val digest = java.security.MessageDigest.getInstance("SHA-256")
  val input = java.nio.file.Files.newInputStream(file.toPath)
  try {
    val buffer = new Array[Byte](8192)
    var read = input.read(buffer)
    while (read >= 0) {
      if (read > 0) digest.update(buffer, 0, read)
      read = input.read(buffer)
    }
  } finally input.close()
  digest.digest().map(value => f"${value & 0xff}%02x").mkString
}

def embeddedProducerSha256(value: String): String =
  java.security.MessageDigest.getInstance("SHA-256")
    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    .map(byte => f"${byte & 0xff}%02x").mkString

lazy val embeddedProducerPackaging =
  (project in file("embedded-producer-plugin-fixture/packaging"))
    .settings(
      publish / skip := true,
      embeddedMarkerRoleJar := {
        (embeddedProducerFixture / Compile / compile).value
        val classes = (embeddedProducerFixture / Compile / classDirectory).value
        val names = Seq(
          "identityEmbedded", "addGreeting", "companionEmbedded",
          "defaultedEmbedded", "genericEmbedded"
        ).flatMap(name => Seq(s"p218/marker/$name.class", s"p218/marker/$name.tasty")) ++
          Seq(
            "p218/marker/defaultedEmbedded$.class",
            "paradise3/apiary/MarkerConstructorType.class",
            "paradise3/apiary/MarkerConstructorType.tasty"
          )
        embeddedProducerZip(
          classes,
          names,
          target.value / s"embedded-producer-marker-role-${scalaVersion.value}.jar"
        )
      },
      embeddedHandlerRoleJar := {
        (embeddedProducerFixture / Compile / compile).value
        val classes = (embeddedProducerFixture / Compile / classDirectory).value
        val markerEntries = Set(
          "p218/marker/identityEmbedded.class",
          "p218/marker/addGreeting.class",
          "p218/marker/companionEmbedded.class",
          "p218/marker/defaultedEmbedded.class",
          "p218/marker/defaultedEmbedded$.class",
          "paradise3/apiary/MarkerConstructorType.class",
          "paradise3/apiary/MarkerConstructorType.tasty",
          "p218/marker/genericEmbedded.class",
          "p218/marker/identityEmbedded.tasty",
          "p218/marker/addGreeting.tasty",
          "p218/marker/companionEmbedded.tasty",
          "p218/marker/defaultedEmbedded.tasty",
          "p218/marker/genericEmbedded.tasty"
        )
        val names = (classes ** "*").get.filter(_.isFile)
          .flatMap(file => IO.relativize(classes, file))
          .filterNot(markerEntries)
        embeddedProducerZip(
          classes,
          names,
          target.value / s"embedded-producer-handler-role-${scalaVersion.value}.jar"
        )
      },
      embeddedCombinedProducerJar := {
        (embeddedProducerFixture / Compile / compile).value
        val classes = (embeddedProducerFixture / Compile / classDirectory).value
        val names = (classes ** "*").get.filter(_.isFile)
          .flatMap(file => IO.relativize(classes, file))
        embeddedProducerZip(
          classes,
          names,
          target.value / s"embedded-producer-combined-${scalaVersion.value}.jar"
        )
      }
    )

def embeddedProducerMarkerArtifacts: Def.Initialize[Task[Seq[File]]] = Def.task {
  Seq(
    (embeddedProducerPackaging / embeddedMarkerRoleJar).value,
    (embeddedProducerOrdinaryMarker / Compile / packageBin).value.getCanonicalFile
  )
}

def embeddedProducerHandlerClasspath: Def.Initialize[Task[Seq[File]]] = Def.task {
  val embedded = (embeddedProducerPackaging / embeddedHandlerRoleJar).value
  val ordinary = (embeddedProducerOrdinaryHandler / Compile / packageBin).value.getCanonicalFile
  val api = (pluginApi / Compile / packageBin).value.getCanonicalFile
  val ordinaryClasses =
    (embeddedProducerOrdinaryHandler / Compile / classDirectory).value.getCanonicalFile
  val runtime = (embeddedProducerOrdinaryHandler / Runtime / dependencyClasspath).value.files
    .map(_.getCanonicalFile)
    .filterNot(file => file == ordinaryClasses || file == ordinary)
    .filter(_.isFile)
  (Seq(embedded, ordinary, api) ++ runtime).distinct
}

def embeddedProducerExternalIdentity: Def.Initialize[Task[String]] = Def.task {
  val manifest =
    embeddedProducerMarkerArtifacts.value.zipWithIndex.map { case (file, index) =>
      s"marker\tmarker-$index%04d\t${embeddedProducerSha256(file)}\n"
    }.mkString +
      embeddedProducerHandlerClasspath.value.zipWithIndex.map { case (file, index) =>
        s"handler\t$index%04d:handler-$index%04d\t${embeddedProducerSha256(file)}\n"
      }.mkString
  embeddedProducerSha256(manifest)
}

lazy val embeddedProducerConsumer =
  (project in file("embedded-producer-plugin-fixture/consumer"))
    .settings(
      name := "macroparadise-scala3-embedded-producer-consumer-fixture",
      publish / skip := true,
      Compile / unmanagedJars ++=
        embeddedProducerMarkerArtifacts.value.map(Attributed.blank),
      Compile / scalacOptions ++= {
        val handlers = embeddedProducerHandlerClasspath.value
        Seq(
          s"-Xplugin:${(plugin / Compile / packageBin).value.getAbsolutePath}",
          "-Xplugin-require:macroparadise",
          s"-P:macroparadise:handlerClasspath=${handlers.map(_.getAbsolutePath).mkString(java.io.File.pathSeparator)}",
          s"-P:macroparadise:externalArtifactIdentity=sha256:${embeddedProducerExternalIdentity.value}"
        )
      },
      Compile / run / mainClass := Some("p218.consumer.Positive")
    )

lazy val embeddedProducerNegativeTarget =
  (project in file("embedded-producer-plugin-fixture/negative-target"))
    .settings(
      name := "macroparadise-scala3-embedded-producer-negative-target-fixture",
      publish / skip := true,
      Compile / unmanagedJars ++=
        embeddedProducerMarkerArtifacts.value.map(Attributed.blank),
      Compile / scalacOptions ++= {
        val handlers = embeddedProducerHandlerClasspath.value
        Seq(
          s"-Xplugin:${(plugin / Compile / packageBin).value.getAbsolutePath}",
          "-Xplugin-require:macroparadise",
          s"-P:macroparadise:handlerClasspath=${handlers.map(_.getAbsolutePath).mkString(java.io.File.pathSeparator)}",
          s"-P:macroparadise:externalArtifactIdentity=sha256:${embeddedProducerExternalIdentity.value}"
        )
      }
    )

verifyEmbeddedProducerContract := {
  import scala.collection.JavaConverters._
  import scala.sys.process.{Process, ProcessLogger}

  val marker = (embeddedProducerPackaging / embeddedMarkerRoleJar).value
  val handler = (embeddedProducerPackaging / embeddedHandlerRoleJar).value
  val combined = (embeddedProducerPackaging / embeddedCombinedProducerJar).value
  val pluginJar = (plugin / Compile / packageBin).value.getCanonicalFile
  val apiJar = (pluginApi / Compile / packageBin).value.getCanonicalFile
  val producerClasses = (embeddedProducerFixture / Compile / classDirectory).value
  (embeddedProducerConsumer / Compile / compile).value

  val consumerDependencies =
    (embeddedProducerConsumer / Compile / dependencyClasspath).value.files
      .filter(_.isFile).map(_.getName).toVector
  require(
    !consumerDependencies.exists(name =>
      name.startsWith("macroparadise-scala3-plugin-api") || name.startsWith("scala3-compiler")
    ),
    s"embedded marker consumer leaks handler/compiler dependencies: ${consumerDependencies.sorted.mkString(",")}"
  )

  def entries(file: File): Vector[String] = {
    val jar = new java.util.jar.JarFile(file)
    try jar.entries().asScala.map(_.getName).filterNot(_.endsWith("/")).toVector
    finally jar.close()
  }

  val markerEntries = entries(marker)
  val handlerEntries = entries(handler)
  require(
    markerEntries.toSet.intersect(handlerEntries.toSet).isEmpty,
    "derived embedded marker/handler role JARs are not disjoint"
  )
  require(
    markerEntries.toSet == Set(
      "p218/marker/identityEmbedded.class",
      "p218/marker/identityEmbedded.tasty",
      "p218/marker/addGreeting.class",
      "p218/marker/addGreeting.tasty",
      "p218/marker/companionEmbedded.class",
      "p218/marker/companionEmbedded.tasty",
      "p218/marker/defaultedEmbedded.class",
      "p218/marker/defaultedEmbedded$.class",
      "p218/marker/defaultedEmbedded.tasty",
      "p218/marker/genericEmbedded.class",
      "paradise3/apiary/MarkerConstructorType.class",
      "paradise3/apiary/MarkerConstructorType.tasty",
      "p218/marker/genericEmbedded.tasty"
    ),
    s"unexpected embedded marker-role inventory: ${markerEntries.mkString(",")}"
  )

  def entryBytes(file: File, name: String): Array[Byte] = {
    val jar = new java.util.jar.JarFile(file)
    try {
      val entry = Option(jar.getJarEntry(name)).getOrElse(
        sys.error(s"missing role entry $name in ${file.getAbsolutePath}")
      )
      val stream = jar.getInputStream(entry)
      try {
        val output = new java.io.ByteArrayOutputStream
        val buffer = new Array[Byte](8192)
        var read = stream.read(buffer)
        while (read >= 0) {
          if (read > 0) output.write(buffer, 0, read)
          read = stream.read(buffer)
        }
        output.toByteArray
      } finally stream.close()
    } finally jar.close()
  }
  val forbiddenMarkerTokens = Vector(
    "transform", "ExpansionInput", "ExpansionOutcome", "Contexts$Context", "EmbeddedEvidence",
    "embeddedExpander", "dotty.tools.dotc", "Contexts", "ExpansionTransforms",
    "MissingCompanionPolicy", "paradise3.api.Expansion"
  )
  markerEntries.foreach { name =>
    val raw = new String(entryBytes(marker, name), java.nio.charset.StandardCharsets.ISO_8859_1)
    val leaked = forbiddenMarkerTokens.filter(raw.contains)
    require(leaked.isEmpty, s"marker role entry $name leaks handler/compiler tokens: ${leaked.mkString(",")}")
  }
  require(
    handlerEntries.contains(
      "p218/marker/identityEmbedded__MacroParadiseEmbeddedExpansionHandler.class"
    ),
    "embedded handler role lacks generated identity adapter"
  )
  require(
    handlerEntries.contains(
      "p218/marker/identityEmbedded__MacroParadiseEmbeddedTransform$.class"
    ) &&
      !handlerEntries.contains("p218/marker/identityEmbedded.class") &&
      !handlerEntries.contains("p218/marker/defaultedEmbedded$.class"),
    "embedded handler role companion/marker split changed"
  )

  val markerNames = markerEntries
  val handlerNames = handlerEntries
  val markerSecond = target.value / "embedded-producer-contract" / "marker-second.jar"
  val handlerSecond = target.value / "embedded-producer-contract" / "handler-second.jar"
  embeddedProducerZip(producerClasses, markerNames, markerSecond)
  embeddedProducerZip(producerClasses, handlerNames, handlerSecond)
  require(
    java.util.Arrays.equals(
      java.nio.file.Files.readAllBytes(marker.toPath),
      java.nio.file.Files.readAllBytes(markerSecond.toPath)
    ),
    "embedded marker role is not byte-deterministic"
  )
  require(
    java.util.Arrays.equals(
      java.nio.file.Files.readAllBytes(handler.toPath),
      java.nio.file.Files.readAllBytes(handlerSecond.toPath)
    ),
    "embedded handler role is not byte-deterministic"
  )

  val runtimeClasspath =
    (pluginJar +: (plugin / Runtime / dependencyClasspath).value.files.filter(_.isFile))
      .map(_.getCanonicalPath).distinct
  val handlerCompileClasspath =
    (apiJar +: (embeddedProducerOrdinaryHandler / Compile / dependencyClasspath).value.files
      .filter(_.isFile))
      .map(_.getCanonicalPath).distinct
  val javaCommand = java.nio.file.Path.of(
    System.getProperty("java.home"),
    "bin",
    if (scala.util.Properties.isWin) "java.exe" else "java"
  ).toString

  def precheck(markerArtifact: File, handlerArtifact: File): (Int, String) = {
    val output = new StringBuilder
    val command = Seq(
      javaCommand,
      "-cp",
      runtimeClasspath.mkString(java.io.File.pathSeparator),
      "macroparadise.ExternalHandlerPrecheckMain",
      s"--plugin=${pluginJar.getAbsolutePath}",
      s"--plugin-api=${apiJar.getAbsolutePath}",
      s"--marker=${markerArtifact.getAbsolutePath}",
      s"--handler=${handlerArtifact.getAbsolutePath}",
      s"--handler-compile-classpath=${handlerCompileClasspath.mkString(java.io.File.pathSeparator)}",
      "--marker-class=p218.marker.identityEmbedded",
      "--expected-handler-class=p218.marker.identityEmbedded__MacroParadiseEmbeddedExpansionHandler",
      "--expected-annotation=p218.marker.identityEmbedded",
      s"--expected-scala-version=${scalaVersion.value}",
      s"--expected-jdk-major=${java.lang.Runtime.version().feature()}"
    )
    val exit = Process(command, baseDirectory.value).!(
      ProcessLogger(line => output.append(line).append('\n'), line => output.append(line).append('\n'))
    )
    exit -> output.result()
  }

  val (splitExit, splitOutput) = precheck(marker, handler)
  require(splitExit == 0, s"derived split roles failed current precheck: $splitOutput")
  val (combinedExit, combinedOutput) = precheck(combined, combined)
  require(
    combinedExit != 0 && combinedOutput.contains("WRONG_ARTIFACT_ROLE"),
    s"combined producer artifact was not rejected by current precheck: $combinedOutput"
  )

  val markerArtifacts = embeddedProducerMarkerArtifacts.value
  val negativeCompilerClasspath =
    (Seq(apiJar) ++ markerArtifacts ++
      (embeddedProducerFixture / Compile / dependencyClasspath).value.files.filter(_.isFile))
      .map(_.getCanonicalFile).distinct
  val handlers = embeddedProducerHandlerClasspath.value
  def compileConsumerNegative(sourceName: String): (Int, String, File) = {
    val negativeOutput = target.value / "embedded-producer-contract" /
      scalaVersion.value / s"negative-${sourceName.stripSuffix(".scala")}-classes"
    IO.delete(negativeOutput)
    IO.createDirectory(negativeOutput)
    val negativeLog = new StringBuilder
    val negativeCommand = Seq(
      javaCommand,
      "-cp",
      negativeCompilerClasspath.map(_.getAbsolutePath).mkString(java.io.File.pathSeparator),
      "dotty.tools.dotc.Main",
      "-color:never",
      "-classpath",
      negativeCompilerClasspath.map(_.getAbsolutePath).mkString(java.io.File.pathSeparator),
      "-d",
      negativeOutput.getAbsolutePath,
      s"-Xplugin:${pluginJar.getAbsolutePath}",
      "-Xplugin-require:macroparadise",
      s"-P:macroparadise:handlerClasspath=${handlers.map(_.getAbsolutePath).mkString(java.io.File.pathSeparator)}",
      s"-P:macroparadise:externalArtifactIdentity=sha256:${embeddedProducerExternalIdentity.value}",
      (baseDirectory.value / "embedded-producer-plugin-fixture" / "negative-target" /
        "src" / "main" / "scala" / "p218" / "consumer" / sourceName).getAbsolutePath
    )
    val negativeExit = Process(negativeCommand, baseDirectory.value).!(
      ProcessLogger(
        line => negativeLog.append(line).append('\n'),
        line => negativeLog.append(line).append('\n')
      )
    )
    (negativeExit, negativeLog.result(), negativeOutput)
  }

  val (objectExit, objectLog, objectOutput) =
    compileConsumerNegative("NegativeTarget.scala")
  require(
    objectExit != 0 && objectLog.contains("P218_ADD_GREETING_CLASS_REQUIRED"),
    s"object applicability negative lacked handler-owned rejection: $objectLog"
  )
  require(
    (objectOutput ** "*").get.forall(!_.isFile),
    "object applicability negative emitted partial consumer output"
  )

  val (argumentExit, argumentLog, argumentOutput) =
    compileConsumerNegative("UnsupportedArgument.scala")
  require(
    argumentExit != 0 && argumentLog.contains("requires a string literal"),
    s"unsupported annotation syntax lacked handler-owned rejection: $argumentLog"
  )
  require(
    (argumentOutput ** "*").get.forall(!_.isFile),
    "unsupported annotation syntax emitted partial consumer output"
  )

  val runResult = (embeddedProducerConsumer / Compile / runner).value.run(
    "p218.consumer.Positive",
    (embeddedProducerConsumer / Runtime / fullClasspath).value.files,
    Seq.empty,
    streams.value.log
  )
  runResult.get
  streams.value.log.info(
    s"embedded producer contract verified: markerSha256=${embeddedProducerSha256(marker)} " +
      s"handlerSha256=${embeddedProducerSha256(handler)} splitPrecheck=PASS combinedRole=REJECTED"
  )
}

verifyEmbeddedProducerBodyEditProtocol := {
  import scala.collection.JavaConverters._
  import scala.sys.process.{Process, ProcessLogger}

  final case class Variant(marker: File, handler: File, markerHash: String, handlerHash: String)

  val protocolRoot = target.value / "embedded-producer-body-edit" / scalaVersion.value
  IO.delete(protocolRoot)
  IO.createDirectory(protocolRoot)
  val producerSource = baseDirectory.value / "embedded-producer-plugin-fixture" /
    "producer" / "src" / "main" / "scala" / "p218" / "marker" / "EmbeddedAnnotations.scala"
  val baselineSource = IO.read(producerSource)
  val probeSource = baseDirectory.value / "embedded-producer-plugin-fixture" /
    "producer" / "src" / "main" / "scala" / "p218" / "marker" /
    "CompanionReferenceProbes.scala"
  val probeSourceText = IO.read(probeSource)
  val importedProbeSource = baseDirectory.value / "embedded-producer-plugin-fixture" /
    "producer" / "src" / "main" / "scala" / "p218" / "probe" /
    "ImportedCompanionReferenceProbe.scala"
  val importedProbeSourceText = IO.read(importedProbeSource)
  val importScopeProbeSource = baseDirectory.value / "embedded-producer-plugin-fixture" /
    "producer" / "src" / "main" / "scala" / "p218" / "probe" /
    "ImportScopeProbes.scala"
  val importScopeProbeSourceText = IO.read(importScopeProbeSource)
  val unrelatedSource = baseDirectory.value / "embedded-producer-plugin-fixture" /
    "producer" / "src" / "main" / "scala" / "p218" / "unrelated" /
    "Unrelated.scala"
  val unrelatedSourceText = IO.read(unrelatedSource)
  val markerConstructorTypeSource = baseDirectory.value /
    "embedded-producer-plugin-fixture" / "producer" / "src" / "main" / "scala" /
    "paradise3" / "apiary" / "MarkerConstructorType.scala"
  val markerConstructorTypeSourceText = IO.read(markerConstructorTypeSource)
  require(
    baselineSource.sliding("P218_EDIT_V1".length).count(_ == "P218_EDIT_V1") == 1,
    "body-edit protocol requires exactly one P218_EDIT_V1 transform literal"
  )
  val editedSource = baselineSource.replace("P218_EDIT_V1", "P218_EDIT_V2")
  val producerPluginJar =
    (embeddedProducerPlugin / Compile / packageBin).value.getCanonicalFile
  val consumerPluginJar = (plugin / Compile / packageBin).value.getCanonicalFile
  val apiJar = (pluginApi / Compile / packageBin).value.getCanonicalFile
  val producerCompilerClasspath =
    (apiJar +: (embeddedProducerFixture / Compile / dependencyClasspath).value.files
      .filter(_.isFile)).map(_.getCanonicalFile).distinct
  val javaCommand = java.nio.file.Path.of(
    System.getProperty("java.home"),
    "bin",
    if (scala.util.Properties.isWin) "java.exe" else "java"
  ).toString
  val markerEntryNames = Seq(
    "identityEmbedded", "addGreeting", "companionEmbedded",
    "defaultedEmbedded", "genericEmbedded"
  ).flatMap(name => Seq(s"p218/marker/$name.class", s"p218/marker/$name.tasty")) ++
    Seq(
      "p218/marker/defaultedEmbedded$.class",
      "paradise3/apiary/MarkerConstructorType.class",
      "paradise3/apiary/MarkerConstructorType.tasty"
    )

  def execute(command: Seq[String], label: String): String = {
    val output = new StringBuilder
    val exit = Process(command, baseDirectory.value).!(
      ProcessLogger(
        line => output.append(line).append('\n'),
        line => output.append(line).append('\n')
      )
    )
    require(exit == 0, s"$label failed with exit=$exit:\n$output")
    output.result()
  }

  def compileVariant(label: String, sourceText: String): Variant = {
    val variantRoot = protocolRoot / label
    val sourceCopy = protocolRoot / "src" / "p218" / "marker" / "EmbeddedAnnotations.scala"
    val probeCopy = protocolRoot / "src" / "p218" / "marker" /
      "CompanionReferenceProbes.scala"
    val importedProbeCopy = protocolRoot / "src" / "p218" / "probe" /
      "ImportedCompanionReferenceProbe.scala"
    val importScopeProbeCopy = protocolRoot / "src" / "p218" / "probe" /
      "ImportScopeProbes.scala"
    val unrelatedCopy = protocolRoot / "src" / "p218" / "unrelated" /
      "Unrelated.scala"
    val markerConstructorTypeCopy = protocolRoot / "src" / "paradise3" / "apiary" /
      "MarkerConstructorType.scala"
    val classes = variantRoot / "classes"
    IO.createDirectory(sourceCopy.getParentFile)
    IO.createDirectory(classes)
    IO.write(sourceCopy, sourceText)
    IO.write(probeCopy, probeSourceText)
    IO.createDirectory(importedProbeCopy.getParentFile)
    IO.write(importedProbeCopy, importedProbeSourceText)
    IO.write(importScopeProbeCopy, importScopeProbeSourceText)
    IO.createDirectory(unrelatedCopy.getParentFile)
    IO.write(unrelatedCopy, unrelatedSourceText)
    IO.createDirectory(markerConstructorTypeCopy.getParentFile)
    IO.write(markerConstructorTypeCopy, markerConstructorTypeSourceText)
    execute(
      Seq(
        javaCommand,
        "-cp",
        producerCompilerClasspath.map(_.getAbsolutePath).mkString(java.io.File.pathSeparator),
        "dotty.tools.dotc.Main",
        "-color:never",
        "-classpath",
        producerCompilerClasspath.map(_.getAbsolutePath).mkString(java.io.File.pathSeparator),
        "-d",
        classes.getAbsolutePath,
        "-Xno-forwarders",
        s"-Xplugin:${producerPluginJar.getAbsolutePath}",
        "-Xplugin-require:macroparadise-embedded-producer",
        sourceCopy.getAbsolutePath,
        probeCopy.getAbsolutePath,
        importedProbeCopy.getAbsolutePath,
        importScopeProbeCopy.getAbsolutePath,
        unrelatedCopy.getAbsolutePath,
        markerConstructorTypeCopy.getAbsolutePath
      ),
      s"$label producer compilation"
    )
    val markerEntries = markerEntryNames
    val markerSet = markerEntries.toSet
    val handlerEntries = (classes ** "*").get.filter(_.isFile)
      .flatMap(file => IO.relativize(classes, file))
      .filterNot(markerSet)
    val marker = embeddedProducerZip(classes, markerEntries, variantRoot / "marker.jar")
    val handler = embeddedProducerZip(classes, handlerEntries, variantRoot / "handler.jar")
    Variant(
      marker,
      handler,
      embeddedProducerSha256(marker),
      embeddedProducerSha256(handler)
    )
  }

  val ordinaryMarker =
    (embeddedProducerOrdinaryMarker / Compile / packageBin).value.getCanonicalFile
  val ordinaryHandler =
    (embeddedProducerOrdinaryHandler / Compile / packageBin).value.getCanonicalFile
  val ordinaryHandlerClasses =
    (embeddedProducerOrdinaryHandler / Compile / classDirectory).value.getCanonicalFile
  val handlerRuntime =
    (embeddedProducerOrdinaryHandler / Runtime / dependencyClasspath).value.files
      .map(_.getCanonicalFile)
      .filterNot(file => file == ordinaryHandlerClasses || file == ordinaryHandler)
      .filter(_.isFile)
  val consumerCompilerClasspath = producerCompilerClasspath.filter { file =>
    val name = file.getName
    name.startsWith("scala3-library_3-") || name.startsWith("scala-library-")
  }
  val consumerRuntimeClasspath = consumerCompilerClasspath
  val consumerSource = baseDirectory.value / "embedded-producer-plugin-fixture" /
    "consumer" / "src" / "main" / "scala" / "p218" / "consumer" / "Positive.scala"

  def verifyBehavior(label: String, variant: Variant, expectedEdit: String): Unit = {
    val output = protocolRoot / label / "consumer-classes"
    IO.createDirectory(output)
    val markerArtifacts = Seq(variant.marker, ordinaryMarker)
    val handlerClasspath =
      (Seq(variant.handler, ordinaryHandler, apiJar) ++ handlerRuntime).distinct
    val identityManifest =
      markerArtifacts.zipWithIndex.map { case (file, index) =>
        s"marker\tmarker-$index%04d\t${embeddedProducerSha256(file)}\n"
      }.mkString +
        handlerClasspath.zipWithIndex.map { case (file, index) =>
          s"handler\t$index%04d:handler-$index%04d\t${embeddedProducerSha256(file)}\n"
        }.mkString
    val externalIdentity = embeddedProducerSha256(identityManifest)
    val compileClasspath = (markerArtifacts ++ consumerCompilerClasspath).distinct
    execute(
      Seq(
        javaCommand,
        "-cp",
        producerCompilerClasspath.map(_.getAbsolutePath).mkString(java.io.File.pathSeparator),
        "dotty.tools.dotc.Main",
        "-color:never",
        "-classpath",
        compileClasspath.map(_.getAbsolutePath).mkString(java.io.File.pathSeparator),
        "-d",
        output.getAbsolutePath,
        s"-Xplugin:${consumerPluginJar.getAbsolutePath}",
        "-Xplugin-require:macroparadise",
        s"-P:macroparadise:handlerClasspath=${handlerClasspath.map(_.getAbsolutePath).mkString(java.io.File.pathSeparator)}",
        s"-P:macroparadise:externalArtifactIdentity=sha256:$externalIdentity",
        consumerSource.getAbsolutePath
      ),
      s"$label consumer compilation"
    )
    val runtimeOutput = execute(
      Seq(
        javaCommand,
        s"-Dp218.expectedEdit=$expectedEdit",
        "-cp",
        (output +: consumerRuntimeClasspath).map(_.getAbsolutePath)
          .mkString(java.io.File.pathSeparator),
        "p218.consumer.Positive"
      ),
      s"$label consumer runtime"
    )
    require(runtimeOutput.contains("P218_POSITIVE_RUNTIME_PASS"), s"$label runtime proof missing")
  }

  val baseline = compileVariant("baseline", baselineSource)
  val noOp = compileVariant("no-op", baselineSource)
  require(baseline.markerHash == noOp.markerHash, "no-op rebuild changed marker role")
  require(baseline.handlerHash == noOp.handlerHash, "no-op rebuild changed handler role")
  verifyBehavior("baseline", baseline, "P218_EDIT_V1")

  val edited = compileVariant("edited", editedSource)
  require(baseline.markerHash == edited.markerHash, "transform-only edit changed marker role")
  require(baseline.handlerHash != edited.handlerHash, "transform-only edit did not change handler role")
  verifyBehavior("edited", edited, "P218_EDIT_V2")

  val restored = compileVariant("restored", baselineSource)
  require(baseline.markerHash == restored.markerHash, "restored marker role missed baseline")
  require(baseline.handlerHash == restored.handlerHash, "restored handler role missed baseline")
  streams.value.log.info(
    s"embedded producer body-edit protocol verified: baselineMarker=${baseline.markerHash} " +
      s"baselineHandler=${baseline.handlerHash} editedMarker=${edited.markerHash} " +
      s"editedHandler=${edited.handlerHash} restored=PASS"
  )
}

verifyEmbeddedProducerNegativeMatrix := {
  import scala.collection.JavaConverters._
  import scala.sys.process.{Process, ProcessLogger}

  val cases = Vector(
    ("missing-companion", "EMBEDDED_COMPANION_REQUIRED"),
    ("missing-transform", "EMBEDDED_TRANSFORM_COUNT"),
    ("multiple-transform", "EMBEDDED_TRANSFORM_COUNT"),
    ("wrong-clauses", "EMBEDDED_TRANSFORM_SIGNATURE"),
    ("wrong-types", "EMBEDDED_TRANSFORM_SIGNATURE"),
    ("inaccessible-transform", "EMBEDDED_TRANSFORM_ACCESS"),
    ("not-static", "EMBEDDED_STATIC_ANNOTATION_PARENT"),
    ("non-final", "EMBEDDED_FINAL_CLASS_REQUIRED"),
    ("nested", "EMBEDDED_TOPOLOGY"),
    ("adapter-collision", "EMBEDDED_ADAPTER_COLLISION"),
    ("metadata-collision", "EMBEDDED_METADATA_COLLISION"),
    ("duplicate-opt-in", "EMBEDDED_DUPLICATE_OPT_IN"),
    ("ambiguous-opt-in", "EMBEDDED_OPT_IN_IDENTITY"),
    ("legacy-implicit", "EMBEDDED_TRANSFORM_SIGNATURE"),
    ("cross-file-adapter-collision", "EMBEDDED_ADAPTER_COLLISION"),
    ("cross-file-shadow", "EMBEDDED_OPT_IN_IDENTITY"),
    ("duplicate-canonical", "EMBEDDED_DUPLICATE_CANONICAL_IDENTITY"),
    ("adapter-typing", "Found:")
  )
  val pluginJar =
    (embeddedProducerPlugin / Compile / packageBin).value.getCanonicalFile
  val apiJar = (pluginApi / Compile / packageBin).value.getCanonicalFile
  val compilerClasspath =
    (apiJar +: (embeddedProducerFixture / Compile / dependencyClasspath).value.files
      .filter(_.isFile))
      .map(_.getCanonicalFile).distinct
  val javaCommand = java.nio.file.Path.of(
    System.getProperty("java.home"),
    "bin",
    if (scala.util.Properties.isWin) "java.exe" else "java"
  ).toString
  val evidence = target.value / "embedded-producer-negative-matrix" / scalaVersion.value
  IO.delete(evidence)
  IO.createDirectory(evidence)

  cases.foreach { case (id, expected) =>
    val sourceRoot = baseDirectory.value / "embedded-producer-plugin-fixture" /
      "negatives" / id
    val sources = (sourceRoot ** "*.scala").get.sorted
    require(sources.nonEmpty, s"negative matrix case $id has no source")
    val output = evidence / id / "classes"
    val logFile = evidence / id / "compile.log"
    IO.createDirectory(output)
    val command = Seq(
      javaCommand,
      "-cp",
      compilerClasspath.map(_.getAbsolutePath).mkString(java.io.File.pathSeparator),
      "dotty.tools.dotc.Main",
      "-color:never",
      "-classpath",
      compilerClasspath.map(_.getAbsolutePath).mkString(java.io.File.pathSeparator),
      "-d",
      output.getAbsolutePath,
      s"-Xplugin:${pluginJar.getAbsolutePath}",
      "-Xplugin-require:macroparadise-embedded-producer"
    ) ++ sources.map(_.getAbsolutePath)
    val captured = new StringBuilder
    val exit = Process(command, baseDirectory.value).!(
      ProcessLogger(
        line => captured.append(line).append('\n'),
        line => captured.append(line).append('\n')
      )
    )
    val logText = captured.result()
    IO.write(logFile, logText)
    require(exit != 0, s"negative matrix case $id unexpectedly compiled")
    require(
      logText.contains(expected),
      s"negative matrix case $id lacked expected $expected evidence:\n$logText"
    )
    val outputs = (output ** "*").get.filter(_.isFile)
    require(
      !outputs.exists(_.getName.contains("__MacroParadiseEmbeddedExpansionHandler")),
      s"negative matrix case $id emitted a consumer-ready adapter: ${outputs.mkString(",")}"
    )
  }
  streams.value.log.info(
    s"embedded producer negative matrix verified: cases=${cases.size}/18 evidence=${evidence.getAbsolutePath}"
  )
}

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
    experimentalPluginApiSurfaceBaseline(baseDirectory.value, scalaVersion.value)
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
  val embeddedProducerRef =
    rootBuildRefs.find(_.project == "embeddedProducerPlugin").getOrElse {
      sys.error("embeddedProducerPlugin project is missing from the loaded build")
    }
  val embeddedProducerProject =
    structure.allProjectPairs.find(_._2 == embeddedProducerRef).map(_._1).getOrElse {
      sys.error("embeddedProducerPlugin project definition is missing from the loaded build")
    }
  val consumerPluginRef = rootBuildRefs.find(_.project == "plugin").get
  val consumerPluginProject =
    structure.allProjectPairs.find(_._2 == consumerPluginRef).map(_._1).get

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
    embeddedProducerProject.id,
    embeddedProducerProject.base.getCanonicalFile ==
      (baseDirectory.value / "embedded-producer-plugin").getCanonicalFile,
    embeddedProducerProject.dependencies.map(_.project.project).toSet,
    (embeddedProducerPlugin / libraryDependencies).value.map(dependency),
    pluginApiProject.dependencies.exists(_.project == embeddedProducerRef),
    consumerPluginProject.dependencies.exists(_.project == embeddedProducerRef),
    IO.read(baseDirectory.value / "sbt-integration" / "build.sbt")
      .contains("embeddedProducerPlugin"),
    rootProject.aggregate.map(_.project).toSet,

    experimentalPluginApiSurfaceBaseline(baseDirectory.value, scalaVersion.value).isFile,
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
  val embeddedProducerRef = rootBuildRefs.find(_.project == "embeddedProducerPlugin").get
  val embeddedProducerProject =
    structure.allProjectPairs.find(_._2 == embeddedProducerRef).map(_._1).get
  val consumerPluginRef = rootBuildRefs.find(_.project == "plugin").get
  val consumerPluginProject =
    structure.allProjectPairs.find(_._2 == consumerPluginRef).map(_._1).get

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
    embeddedProducerProject.id,
    embeddedProducerProject.base.getCanonicalFile ==
      (baseDirectory.value / "embedded-producer-plugin").getCanonicalFile,
    embeddedProducerProject.dependencies.map(_.project.project).toSet,
    (embeddedProducerPlugin / libraryDependencies).value.map(dependency),
    pluginApiProject.dependencies.exists(_.project == embeddedProducerRef),
    consumerPluginProject.dependencies.exists(_.project == embeddedProducerRef),
    IO.read(baseDirectory.value / "sbt-integration" / "build.sbt")
      .contains("embeddedProducerPlugin"),
    rootProject.aggregate.map(_.project).toSet,
    experimentalPluginApiSurfaceBaseline(baseDirectory.value, scalaVersion.value).isFile,
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
      ExperimentalPluginApiSurface.ExpectedProjectVersion
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
    experimentalPluginApiSurfaceBaseline(baseDirectory.value, scalaVersion.value),
    baseDirectory.value / "plugin-api-surface-probe" / "positive" / "IsolatedPluginApiSurfaceProbe.scala",
    baseDirectory.value / "plugin-api-surface-probe" / "negative" / "ForbiddenImplementationProbe.scala",
    baseDirectory.value / "plugin-api-surface-probe" / "negative" / "ForbiddenInvocationConstructionProbe.scala",
    ExperimentalPluginApiSurface.Config(
      scalaVersion.value,
      sbtVersion.value,
      ExperimentalPluginApiSurface.ExpectedProjectVersion
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
    experimentalPluginApiSurfaceBaseline(baseDirectory.value, scalaVersion.value),
    destination,
    ExperimentalHandlerContractArtifact.Config(
      scalaVersion.value,
      sbtVersion.value,
      ExperimentalPluginApiSurface.ExpectedProjectVersion
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
    experimentalPluginApiSurfaceBaseline(baseDirectory.value, scalaVersion.value),
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
      ExperimentalPluginApiSurface.ExpectedProjectVersion
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

verifySbtPrecompiledIntegrationExternalMatrix := {
  SbtPrecompiledIntegrationExternalMatrixSpec.run()
  val config = SbtPrecompiledIntegrationExternalMatrix.Config(
    scalaVersion.value,
    sbtVersion.value,
    version.value
  )
  val result = SbtPrecompiledIntegrationExternalMatrix.verify(
    baseDirectory.value,
    (pluginApi / Compile / packageBin).value,
    (plugin / Compile / packageBin).value,
    (pluginApi / makePom).value,
    (plugin / makePom).value,
    target.value / "sbt-precompiled-integration-external-matrix",
    config
  )
  val multiLocal = SbtPrecompiledIntegrationExternalMatrix.verifyMultiLocal(
    baseDirectory.value,
    (pluginApi / Compile / packageBin).value,
    (plugin / Compile / packageBin).value,
    (pluginApi / makePom).value,
    (plugin / makePom).value,
    target.value / "sbt-precompiled-multi-local-integration",
    config
  )
  streams.value.log.info(
    s"sbt precompiled integration external matrix verified: ${result.render} evidence=${result.evidenceDirectory.getAbsolutePath}"
  )
  streams.value.log.info(
    s"sbt precompiled multi-local integration verified: ${multiLocal.render} evidence=${multiLocal.evidenceDirectory.getAbsolutePath}"
  )
}

verifyUserOnboardingThreeModeSetup := {
  UserOnboardingThreeModeSetupSpec.run()
  val result = UserOnboardingThreeModeVerifier.verify(
    baseDirectory.value,
    (pluginApi / Compile / packageBin).value,
    (plugin / Compile / packageBin).value,
    (pluginApi / makePom).value,
    (plugin / makePom).value,
    target.value / "user-onboarding-three-mode-setup",
    UserOnboardingThreeModeVerifier.Config(
      scalaVersion.value,
      sbtVersion.value,
      version.value
    )
  )
  streams.value.log.info(
    s"user onboarding three-mode setup verified: ${result.render} focusedCases=${UserOnboardingThreeModeSetupSpec.CaseCount}/${UserOnboardingThreeModeSetupSpec.CaseCount} evidence=${result.evidenceDirectory.getAbsolutePath}"
  )
}

verifySbtPrecompiledIntegrationModule := {
  val module = baseDirectory.value / "sbt-integration"
  val command = Seq("sbt", s"-Dtest.scala.version=${scalaVersion.value}", "-batch", "verifyIntegrationPolicy", "test", "scripted", "packageSrc", "packageDoc")
  val exit = scala.sys.process.Process(command, module).!
  require(exit == 0, s"sbt integration module verification failed with exit $exit")
  streams.value.log.info("sbt integration module verified: sbt1.x/scala2.12 unit+scripted+source+doc artifacts")
}


verifyPublicEmbeddedProducerStarter := {
  PublicEmbeddedProducerStarterSpec.run()
  (pluginApi / publishLocal).value
  (embeddedProducerPlugin / publishLocal).value
  (plugin / publishLocal).value
  val result = PublicEmbeddedProducerStarter.verify(
    baseDirectory.value,
    target.value / "public-embedded-producer-starter" / scalaVersion.value,
    scalaVersion.value,
    sbtVersion.value
  )
  streams.value.log.info(
    s"public embedded producer starter verified: ${result.render} focusedCases=${PublicEmbeddedProducerStarterSpec.CaseCount}/${PublicEmbeddedProducerStarterSpec.CaseCount}"
  )
}


verifyEmbeddedProducerSbtIntegrationMatrix := {
  val result = EmbeddedProducerSbtIntegrationMatrix.verify(
    baseDirectory.value,
    (pluginApi / Compile / packageBin).value,
    (plugin / Compile / packageBin).value,
    (embeddedProducerPlugin / Compile / packageBin).value,
    (pluginApi / makePom).value,
    (plugin / makePom).value,
    (embeddedProducerPlugin / makePom).value,
    target.value / "embedded-producer-sbt-integration-matrix" / scalaVersion.value,
    EmbeddedProducerSbtIntegrationMatrix.Config(
      scalaVersion.value,
      sbtVersion.value,
      version.value
    )
  )
  streams.value.log.info(s"embedded producer sbt integration verified: ${result.render}")
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
    Compile / unmanagedSourceDirectories +=
      (Compile / sourceDirectory).value / s"scala-${scalaVersion.value}",
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
