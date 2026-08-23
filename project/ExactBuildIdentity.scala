object ExactBuildIdentity {
  val SupportedScalaVersions = Set("3.3.8", "3.8.4")
  val DefaultScalaVersion = "3.8.4"
  val DevelopmentVersion = "0.1.1-SNAPSHOT"
  val ReleasedVersion = "0.1.0"

  val SelectedScalaVersion =
    sys.props.getOrElse("macroparadise.exactScalaVersion", DefaultScalaVersion)

  require(
    SupportedScalaVersions.contains(SelectedScalaVersion),
    s"macroparadise.exactScalaVersion must be one of ${SupportedScalaVersions.toList.sorted.mkString(", ")}, found $SelectedScalaVersion"
  )
}
