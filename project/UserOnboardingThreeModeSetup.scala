object UserOnboardingThreeModeSetup {
  final case class Mode(id: String)

  val supportedModes: Vector[Mode] = Vector(
    Mode("manual"),
    Mode("local-project"),
    Mode("published-module")
  )

  final case class ProjectBase(
      projectId: String,
      configuredDirectory: String,
      expectedDirectory: String
  )

  def validateProjectBase(projectBase: ProjectBase): Option[String] =
    when(projectBase.configuredDirectory != projectBase.expectedDirectory)(
      s"${projectBase.projectId} resolves to ${projectBase.configuredDirectory}, expected ${projectBase.expectedDirectory}"
    )

  final case class ModeEvidence(
      mode: String,
      fixtureCompiled: Boolean,
      markerPresentAtCompileTime: Boolean,
      handlerPresentAtCompileTime: Boolean,
      handlerAbsentAtRuntime: Boolean,
      handlerBodyInvalidated: Boolean,
      handlerDependencyInvalidated: Boolean,
      markerInvalidated: Boolean,
      noOpStable: Boolean,
      overridePreserved: Boolean
  )

  object ModeEvidence {
    def complete(mode: String): ModeEvidence = ModeEvidence(
      mode = mode,
      fixtureCompiled = true,
      markerPresentAtCompileTime = true,
      handlerPresentAtCompileTime = true,
      handlerAbsentAtRuntime = true,
      handlerBodyInvalidated = true,
      handlerDependencyInvalidated = true,
      markerInvalidated = true,
      noOpStable = true,
      overridePreserved = true
    )
  }

  def validateEvidence(evidence: ModeEvidence): Vector[String] = Vector(
    when(!evidence.fixtureCompiled)(s"${evidence.mode}: exact fixture did not compile"),
    when(!evidence.markerPresentAtCompileTime)(s"${evidence.mode}: marker missing from the consumer compile classpath"),
    when(!evidence.handlerPresentAtCompileTime)(s"${evidence.mode}: handler missing from the macro expansion classpath"),
    when(!evidence.handlerAbsentAtRuntime)(s"${evidence.mode}: handler leaked onto the consumer runtime classpath"),
    when(!evidence.handlerBodyInvalidated)(s"${evidence.mode}: handler body change did not invalidate the consumer"),
    when(!evidence.handlerDependencyInvalidated)(s"${evidence.mode}: handler dependency change did not invalidate the consumer"),
    when(!evidence.markerInvalidated)(s"${evidence.mode}: marker change did not invalidate the consumer"),
    when(!evidence.noOpStable)(s"${evidence.mode}: no-op rebuild changed consumer output"),
    when(!evidence.overridePreserved)(s"${evidence.mode}: explicit user override was not preserved")
  ).flatten

  private def when(condition: Boolean)(message: => String): Option[String] =
    if (condition) Some(message) else None
}
