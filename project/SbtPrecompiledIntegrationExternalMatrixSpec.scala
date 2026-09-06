object SbtPrecompiledIntegrationExternalMatrixSpec {
  val CaseCount = 10

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

    val multiBaseline = MultiLocalSnapshot(
      markerA = "marker-a-v1",
      markerB = "marker-b-v1",
      handlerA = "handler-a-v1",
      handlerB = "handler-b-v1",
      sharedRuntime = "runtime-v1",
      identity = "identity-v1",
      consumerMtime = 1L
    )
    assert(
      changedOnly(multiBaseline, multiBaseline.copy(markerA = "marker-a-v2", identity = "identity-v2", consumerMtime = 2L), "markerA").isEmpty
    )
    assert(
      changedOnly(multiBaseline, multiBaseline.copy(markerA = "marker-a-v2", handlerB = "wrong", identity = "identity-v2", consumerMtime = 2L), "markerA")
        .contains("unexpected input changed: handlerB")
    )
  }
}
