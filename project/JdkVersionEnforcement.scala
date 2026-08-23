import sbt.MessageOnlyException

import scala.util.Try

object JdkVersionEnforcement {
  val RequiredFeature: Int = 25

  final case class DetectedVersion(
      feature: Option[Int],
      display: String
  )

  private val TestOnlyUnsupportedFeatureProperty =
    "macroparadise.internal.testUnsupportedJdkFeature"

  def currentDetectedVersion(): DetectedVersion = {
    val actual = DetectedVersion(
      Some(Runtime.version().feature()),
      Runtime.version().toString
    )
    val injected =
      Option(System.getProperty(TestOnlyUnsupportedFeatureProperty))
        .map(_.trim)
        .filter(_.nonEmpty)

    selectDetectedVersion(actual, injected)
  }

  def selectDetectedVersion(
      actual: DetectedVersion,
      testOnlyUnsupportedFeature: Option[String]
  ): DetectedVersion =
    testOnlyUnsupportedFeature match {
      case None =>
        actual
      case Some(raw) =>
        Try(raw.toInt).toOption match {
          case Some(feature) if feature != RequiredFeature =>
            DetectedVersion(
              Some(feature),
              s"$feature (test-only synthetic feature)"
            )
          case _ =>
            DetectedVersion(
              None,
              s"$raw (invalid test-only unsupported-feature injection)"
            )
        }
    }

  def validationError(detected: DetectedVersion): Option[String] =
    detected.feature match {
      case Some(RequiredFeature) =>
        None
      case _ =>
        Some(failureMessage(detected))
    }

  def failureMessage(detected: DetectedVersion): String = {
    val featureText =
      detected.feature
        .map(feature => s"feature $feature")
        .getOrElse("feature unavailable")

    s"Unsupported JVM for macroparadise-scala3: detected JVM version `${detected.display}` ($featureText); required major version is 25. " +
      "This project is pinned to JDK 25 because it uses compiler-internal exact Scala 3.3.8/3.8.4 pre-typer plugin toolchains. " +
      "Select JDK 25 before rerunning sbt."
  }

  def enforce(detected: DetectedVersion): Unit =
    validationError(detected).foreach(message =>
      throw new MessageOnlyException(message)
    )

  def enforceCurrent(): Unit =
    enforce(currentDetectedVersion())
}
