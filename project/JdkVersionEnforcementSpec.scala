import sbt.MessageOnlyException

object JdkVersionEnforcementSpec {
  import JdkVersionEnforcement._

  def run(): Unit = {
    val supported = DetectedVersion(Some(25), "25.0.3")
    assert(validationError(supported).isEmpty)
    enforce(supported)

    val jdk21 = DetectedVersion(Some(21), "21.0.8")
    val jdk21Message = validationError(jdk21).get
    assert(jdk21Message.contains("detected JVM version `21.0.8` (feature 21)"))
    assert(jdk21Message.contains("required major version is 25"))
    assert(jdk21Message.contains("exact Scala 3.3.8/3.8.4 pre-typer plugin"))
    assert(jdk21Message.contains("Select JDK 25 before rerunning sbt"))

    val jdk26 = DetectedVersion(Some(26), "26-ea")
    assert(validationError(jdk26).exists(_.contains("feature 26")))

    val unavailable = DetectedVersion(None, "")
    assert(
      validationError(unavailable).exists(_.contains("feature unavailable"))
    )

    val injected21 =
      selectDetectedVersion(supported, Some("21"))
    assert(injected21.feature.contains(21))
    assert(injected21.display.contains("test-only synthetic feature"))

    val injectedRequired =
      selectDetectedVersion(
        DetectedVersion(Some(21), "21.0.8"),
        Some("25")
      )
    assert(injectedRequired.feature.isEmpty)
    assert(validationError(injectedRequired).nonEmpty)

    val injectedMalformed =
      selectDetectedVersion(supported, Some("not-a-version"))
    assert(injectedMalformed.feature.isEmpty)
    assert(validationError(injectedMalformed).nonEmpty)

    try {
      enforce(jdk21)
      sys.error("expected unsupported JDK enforcement to fail")
    } catch {
      case error: MessageOnlyException =>
        assert(error.getMessage == failureMessage(jdk21))
    }
  }
}
