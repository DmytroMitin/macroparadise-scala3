import java.io.File

object BuildDependencyCoordinatePolicy {
  val ExpectedScalaVersion =
    ExactBuildIdentity.SelectedScalaVersion
  val SupportedScalaVersions = ExactBuildIdentity.SupportedScalaVersions
  val ExpectedSbtVersion = "1.12.15"
  val ExpectedJdkFeature = 25
  val ExpectedPluginApiProjectId = "pluginApi"
  val ExpectedPluginTestMarkersProjectId = "pluginTestMarkers"
  val ExpectedEmbeddedProducerProjectId = "embeddedProducerPlugin"
  val ExpectedRootAggregate = Set(
    "legacyMetadataMarkerFixture",
    "pluginApi",
    "embeddedProducerPlugin",
    "plugin",
    "pluginTestMarkers",
    "pluginTestHandlers",
    "pluginTests"
  )
  val RequiredSurfaceTasks = Set(
    "renderExperimentalPluginApiSurfaceBaseline",
    "verifyExperimentalPluginApiSurfaceBaseline"
  )

  private val PromptContamination = "(?i).*orgP[0-9]+.*".r
  private val ScalaCrossSuffix = "_(?:2\\.\\d+|3)$".r

  final case class Dependency(
      organization: String,
      artifact: String,
      version: String,
      configuration: String = "compile",
      classifiers: List[String] = Nil
  ) {
    def artifactBase: String =
      ScalaCrossSuffix.replaceFirstIn(artifact, "")

    def render: String = {
      val classifierText =
        if (classifiers.isEmpty) "none" else classifiers.sorted.mkString(",")
      s"$organization:$artifact:$version configuration=$configuration classifiers=$classifierText"
    }
  }

  final case class BuildShape(
      scalaVersion: String,
      sbtVersion: String,
      jdkFeature: Int,
      pluginApiProjectId: String,
      pluginApiIsSeparate: Boolean,
      pluginTestMarkersProjectId: String,
      pluginTestMarkersIsSeparate: Boolean,
      pluginTestMarkersDependsOnPluginApi: Boolean,
      pluginApiDependsOnPluginTestMarkers: Boolean,
      embeddedProducerProjectId: String,
      embeddedProducerIsSeparate: Boolean,
      embeddedProducerProjectDependencies: Set[String],
      embeddedProducerDependencies: Seq[Dependency],
      pluginApiDependsOnEmbeddedProducer: Boolean,
      consumerPluginDependsOnEmbeddedProducer: Boolean,
      sbtIntegrationMentionsEmbeddedProducer: Boolean,
      rootAggregate: Set[String],
      surfaceBaselineExists: Boolean,
      surfaceTaskLabels: Set[String]
  )

  final case class Result(
      pluginApiDependencies: Seq[Dependency],
      allBuildDependencies: Seq[Dependency],
      shape: BuildShape,
      errors: List[String]
  ) {
    def render: String =
      s"pluginApiDependencies=${pluginApiDependencies.size} " +
        s"allBuildDependencies=${allBuildDependencies.size} " +
        s"rootAggregate=${shape.rootAggregate.toList.sorted.mkString(",")} " +
        s"errors=${errors.size}"
  }

  def verify(
      pluginApiDependencies: Seq[Dependency],
      allBuildDependencies: Seq[Dependency],
      shape: BuildShape
  ): Result = {
    val errors = scala.collection.mutable.ListBuffer.empty[String]
    val compilerDependencies =
      pluginApiDependencies.filter(_.artifactBase == "scala3-compiler")

    if (compilerDependencies.size != 1)
      errors +=
        s"pluginApi must contain exactly one direct scala3-compiler dependency, found ${compilerDependencies.size}"
    compilerDependencies.headOption.foreach { dependency =>
      if (dependency.organization != "org.scala-lang")
        errors +=
          s"pluginApi compiler organization must be org.scala-lang, found ${dependency.organization}"
      if (dependency.artifactBase != "scala3-compiler")
        errors +=
          s"pluginApi compiler artifact must normalize to scala3-compiler, found ${dependency.artifact}"
      if (dependency.version != shape.scalaVersion)
        errors +=
          s"pluginApi compiler version must equal pluginApi scalaVersion ${shape.scalaVersion}, found ${dependency.version}"
      if (dependency.classifiers.nonEmpty)
        errors +=
          s"pluginApi compiler dependency must not declare classifiers, found ${dependency.classifiers.sorted.mkString(",")}"
      if (normalizedConfiguration(dependency.configuration) != "compile")
        errors +=
          s"pluginApi compiler dependency must use ordinary compile scope, found ${dependency.configuration}"
    }

    val producerCompilerDependencies =
      shape.embeddedProducerDependencies.filter(_.artifactBase == "scala3-compiler")
    if (producerCompilerDependencies.size != 1)
      errors +=
        s"embedded producer must contain exactly one direct scala3-compiler dependency, found ${producerCompilerDependencies.size}"
    producerCompilerDependencies.headOption.foreach { dependency =>
      if (dependency.organization != "org.scala-lang")
        errors +=
          s"embedded producer compiler organization must be org.scala-lang, found ${dependency.organization}"
      if (dependency.version != shape.scalaVersion)
        errors +=
          s"embedded producer compiler version must equal scalaVersion ${shape.scalaVersion}, found ${dependency.version}"
      if (dependency.classifiers.nonEmpty)
        errors +=
          s"embedded producer compiler dependency must not declare classifiers, found ${dependency.classifiers.sorted.mkString(",")}"
      if (normalizedConfiguration(dependency.configuration) != "compile")
        errors +=
          s"embedded producer compiler dependency must use ordinary compile scope, found ${dependency.configuration}"
    }
    shape.embeddedProducerDependencies
      .filter(_.artifactBase == "scala3-library")
      .foreach { dependency =>
        if (dependency.organization != "org.scala-lang")
          errors +=
            s"embedded producer Scala library organization must be org.scala-lang, found ${dependency.organization}"
        if (dependency.version != shape.scalaVersion)
          errors +=
            s"embedded producer Scala library version must equal scalaVersion ${shape.scalaVersion}, found ${dependency.version}"
        if (dependency.classifiers.nonEmpty)
          errors +=
            s"embedded producer Scala library dependency must not declare classifiers, found ${dependency.classifiers.sorted.mkString(",")}"
        if (normalizedConfiguration(dependency.configuration) != "compile")
          errors +=
            s"embedded producer Scala library dependency must use ordinary compile scope, found ${dependency.configuration}"
        if (!Set("scala3-library", "scala3-library_3").contains(dependency.artifact))
          errors +=
            s"embedded producer Scala library artifact must be scala3-library or scala3-library_3, found ${dependency.artifact}"
      }
    shape.embeddedProducerDependencies
      .filterNot(dependency => Set("scala3-compiler", "scala3-library").contains(dependency.artifactBase))
      .foreach { dependency =>
        errors += s"embedded producer has unexpected direct library dependency ${dependency.render}"
      }
    shape.embeddedProducerDependencies.foreach { dependency =>
      val identity = s"${dependency.organization}:${dependency.artifact}".toLowerCase
      if (identity.contains("quasiquotes") || identity.contains("auxify"))
        errors +=
          s"embedded producer must not depend on peer product ${dependency.organization}:${dependency.artifact}"
    }

    allBuildDependencies.foreach { dependency =>
      if (PromptContamination.pattern.matcher(dependency.organization).matches())
        errors +=
          s"dependency organization contains prompt-number contamination: ${dependency.organization}"
    }

    if (!SupportedScalaVersions.contains(shape.scalaVersion))
      errors +=
        s"Scala version drift: expected one of ${SupportedScalaVersions.toList.sorted.mkString(", ")}, found ${shape.scalaVersion}"
    if (shape.scalaVersion != ExpectedScalaVersion)
      errors +=
        s"selected exact Scala line mismatch: expected $ExpectedScalaVersion, found ${shape.scalaVersion}"
    if (shape.sbtVersion != ExpectedSbtVersion)
      errors +=
        s"sbt version drift: expected $ExpectedSbtVersion, found ${shape.sbtVersion}"
    if (shape.jdkFeature != ExpectedJdkFeature)
      errors +=
        s"JDK policy drift: expected feature $ExpectedJdkFeature, found ${shape.jdkFeature}"
    if (shape.pluginApiProjectId != ExpectedPluginApiProjectId)
      errors +=
        s"pluginApi project identity drift: expected $ExpectedPluginApiProjectId, found ${shape.pluginApiProjectId}"
    if (!shape.pluginApiIsSeparate)
      errors += "pluginApi must remain a separate project rooted at plugin-api/"
    if (shape.pluginTestMarkersProjectId != ExpectedPluginTestMarkersProjectId)
      errors +=
        s"pluginTestMarkers project identity drift: expected $ExpectedPluginTestMarkersProjectId, found ${shape.pluginTestMarkersProjectId}"
    if (!shape.pluginTestMarkersIsSeparate)
      errors += "pluginTestMarkers must remain a separate project rooted at plugin-test-markers/"
    if (!shape.pluginTestMarkersDependsOnPluginApi)
      errors += "pluginTestMarkers must depend on pluginApi for the metadata carrier"
    if (shape.pluginApiDependsOnPluginTestMarkers)
      errors += "pluginApi must not depend on pluginTestMarkers"
    if (shape.rootAggregate != ExpectedRootAggregate)
      errors +=
        s"root aggregate drift: expected ${ExpectedRootAggregate.toList.sorted.mkString(",")}, found ${shape.rootAggregate.toList.sorted.mkString(",")}"
    if (shape.embeddedProducerProjectId != ExpectedEmbeddedProducerProjectId)
      errors +=
        s"embedded producer project identity drift: expected $ExpectedEmbeddedProducerProjectId, found ${shape.embeddedProducerProjectId}"
    if (!shape.embeddedProducerIsSeparate)
      errors += "embedded producer must remain a separate project rooted at embedded-producer-plugin/"
    if (shape.embeddedProducerProjectDependencies != Set(ExpectedPluginApiProjectId))
      errors +=
        s"embedded producer must depend only on pluginApi; found ${shape.embeddedProducerProjectDependencies.toList.sorted.mkString(",")}"
    if (shape.pluginApiDependsOnEmbeddedProducer)
      errors += "pluginApi must not depend on embedded producer"
    if (shape.consumerPluginDependsOnEmbeddedProducer)
      errors += "consumer plugin must not depend on embedded producer"
    if (shape.sbtIntegrationMentionsEmbeddedProducer)
      errors += "sbt integration must remain unchanged and independent of embedded producer"
    if (!shape.surfaceBaselineExists)
      errors += "experimental API surface baseline file is missing"
    val missingTasks = RequiredSurfaceTasks -- shape.surfaceTaskLabels
    if (missingTasks.nonEmpty)
      errors +=
        s"experimental API surface verifier tasks are missing: ${missingTasks.toList.sorted.mkString(",")}"

    Result(
      pluginApiDependencies,
      allBuildDependencies,
      shape,
      errors.toList.distinct
    )
  }

  def correctedCompiler(version: String): Dependency =
    Dependency("org.scala-lang", "scala3-compiler", version)

  private def normalizedConfiguration(configuration: String): String =
    Option(configuration).map(_.trim.toLowerCase).filter(_.nonEmpty).getOrElse("compile")
}
