import java.io.File

object BuildDependencyCoordinatePolicy {
  val ExpectedScalaVersion =
    "3.8.5-RC1-bin-20260405-9478256-NIGHTLY"
  val ExpectedSbtVersion = "1.12.8"
  val ExpectedJdkFeature = 25
  val ExpectedPluginApiProjectId = "pluginApi"
  val ExpectedPluginTestMarkersProjectId = "pluginTestMarkers"
  val ExpectedRootAggregate = Set(
    "legacyMetadataMarkerFixture",
    "pluginApi",
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

    allBuildDependencies.foreach { dependency =>
      if (PromptContamination.pattern.matcher(dependency.organization).matches())
        errors +=
          s"dependency organization contains prompt-number contamination: ${dependency.organization}"
    }

    if (shape.scalaVersion != ExpectedScalaVersion)
      errors +=
        s"Scala version drift: expected $ExpectedScalaVersion, found ${shape.scalaVersion}"
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
