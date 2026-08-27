object SbtPrecompiledIntegrationExternalMatrixSpec {
  val CaseCount = 8

  def run(): Unit = {
    import SbtPrecompiledIntegrationExternalMatrix._

    val valid = Transition(
      baselineValue = "dependency-v1",
      editedValue = "dependency-v2",
      markerBefore = "marker",
      markerAfter = "marker",
      handlerBefore = "handler",
      handlerAfter = "handler",
      dependencyBefore = "dependency-v1-bytes",
      dependencyAfter = "dependency-v2-bytes",
      supportedIdentityBefore = "identity-v1",
      supportedIdentityAfter = "identity-v2",
      oldPrimaryOnlyBefore = "old",
      oldPrimaryOnlyAfter = "old",
      consumerBefore = "consumer-v1",
      consumerAfter = "consumer-v2",
      consumerNoOp = "consumer-v2",
      noOpMtimeStable = true
    )
    assert(validateTransition(valid).isEmpty)
    assert(validateTransition(valid.copy(markerAfter = "changed")).contains("marker bytes changed"))
    assert(validateTransition(valid.copy(handlerAfter = "changed")).contains("primary handler bytes changed"))
    assert(validateTransition(valid.copy(dependencyAfter = valid.dependencyBefore)).contains("dependency bytes did not change"))
    assert(validateTransition(valid.copy(supportedIdentityAfter = valid.supportedIdentityBefore)).contains("supported identity did not change"))
    assert(validateTransition(valid.copy(oldPrimaryOnlyAfter = "changed")).contains("old primary-only control changed"))
    assert(validateTransition(valid.copy(consumerAfter = valid.consumerBefore)).contains("consumer output did not regenerate"))
    assert(validateTransition(valid.copy(noOpMtimeStable = false)).contains("no-op consumer output churned"))
  }
}
