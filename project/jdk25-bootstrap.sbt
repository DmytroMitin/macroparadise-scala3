import sbt.MessageOnlyException

val verifyJdk25BeforeMetaBuildCompilation =
  settingKey[Unit]("Reject an unsupported JVM before compiling project/*.scala")

Global / verifyJdk25BeforeMetaBuildCompilation := {
  val requiredFeature = 25
  val testOnlyUnsupportedFeatureProperty =
    "macroparadise.internal.testUnsupportedJdkFeature"
  val rawSpecification =
    Option(System.getProperty("java.specification.version")).getOrElse("")
  val normalizedSpecification =
    if (rawSpecification.startsWith("1.")) rawSpecification.substring(2)
    else rawSpecification
  val actualFeature =
    try Some(normalizedSpecification.takeWhile(_.isDigit).toInt)
    catch { case _: NumberFormatException => None }
  val actualDisplay =
    Option(System.getProperty("java.runtime.version"))
      .filter(_.nonEmpty)
      .getOrElse(rawSpecification)
  val injected =
    Option(System.getProperty(testOnlyUnsupportedFeatureProperty))
      .map(_.trim)
      .filter(_.nonEmpty)
  val (detectedFeature, detectedDisplay) = injected match {
    case None =>
      actualFeature -> actualDisplay
    case Some(raw) =>
      val parsed =
        try Some(raw.toInt)
        catch { case _: NumberFormatException => None }
      parsed match {
        case Some(feature) if feature != requiredFeature =>
          Some(feature) -> s"$feature (test-only synthetic feature)"
        case _ =>
          None -> s"$raw (invalid test-only unsupported-feature injection)"
      }
  }

  if (!detectedFeature.contains(requiredFeature)) {
    val featureText =
      detectedFeature
        .map(feature => s"feature $feature")
        .getOrElse("feature unavailable")
    throw new MessageOnlyException(
      s"Unsupported JVM for macroparadise-scala3: detected JVM version `$detectedDisplay` ($featureText); required major version is 25. " +
        "This project is pinned to JDK 25 because it uses a compiler-internal Scala nightly/ResearchPlugin toolchain. " +
        "Select JDK 25 before rerunning sbt."
    )
  }
}
