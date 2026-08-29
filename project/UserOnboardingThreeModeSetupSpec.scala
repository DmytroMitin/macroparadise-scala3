object UserOnboardingThreeModeSetupSpec {
  val CaseCount = 10

  def run(): Unit = {
    import UserOnboardingThreeModeSetup._

    assert(supportedModes.map(_.id) == Vector("manual", "local-project", "published-module"))

    val implicitMarker = ProjectBase("macroAnnotations", "macroAnnotations", "macro-annotations")
    val implicitHandler = ProjectBase("macroHandlers", "macroHandlers", "macro-handlers")
    val explicitMarker = implicitMarker.copy(configuredDirectory = "macro-annotations")
    val explicitHandler = implicitHandler.copy(configuredDirectory = "macro-handlers")

    assert(validateProjectBase(implicitMarker).contains("macroAnnotations resolves to macroAnnotations, expected macro-annotations"))
    assert(validateProjectBase(implicitHandler).contains("macroHandlers resolves to macroHandlers, expected macro-handlers"))
    assert(validateProjectBase(explicitMarker).isEmpty)
    assert(validateProjectBase(explicitHandler).isEmpty)

    val complete = ModeEvidence.complete("manual")
    assert(validateEvidence(complete).isEmpty)
    assert(validateEvidence(complete.copy(handlerAbsentAtRuntime = false)).contains("manual: handler leaked onto the consumer runtime classpath"))
    assert(validateEvidence(complete.copy(handlerDependencyInvalidated = false)).contains("manual: handler dependency change did not invalidate the consumer"))
    assert(validateEvidence(complete.copy(markerInvalidated = false)).contains("manual: marker change did not invalidate the consumer"))
    assert(validateEvidence(complete.copy(overridePreserved = false)).contains("manual: explicit user override was not preserved"))
  }
}
