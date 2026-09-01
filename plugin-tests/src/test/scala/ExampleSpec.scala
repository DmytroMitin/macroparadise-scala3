class ExampleSpec extends munit.FunSuite:
  test("qualified same-simple annotations select distinct metadata handlers end to end") {
    assertEquals(QualifiedAnnotationIdentityExample.one, "one:QualifiedOneAuditUser")
    assertEquals(QualifiedAnnotationIdentityExample.two, "two:QualifiedTwoAuditUser")
    assertEquals(
      QualifiedAnnotationIdentityExample.handledWithUnknown,
      "one:QualifiedHandledWithUnknownUser"
    )
  }

  test("generated member is visible in the same compilation run") {
    assertEquals(Example.directResult, "hello A")
  }

  test("debug generated member is visible in the same compilation run") {
    assertEquals(DebugExample.directResult, "DebugUser")
  }

  test("debug generated member works through explicitly typed APIs") {
    assertEquals(DebugExample.useDebugUser(new DebugUser("A")), "DebugUser")
  }

  test("external handler generated member is visible in the same compilation run") {
    assertEquals(ExternalDebugExample.directResult, "ExternalUser")
  }

  test("external handler generated member works through explicitly typed APIs") {
    assertEquals(ExternalDebugExample.useExternalUser(new ExternalUser()), "ExternalUser")
  }

  test("legacy TASTy-only marker discovers a current handler in the packaged consumer") {
    assertEquals(
      LegacyMetadataExample.legacyMetadataResult,
      "LegacyMetadataUser"
    )
  }

  test("legacy TASTy-only marker output is visible through ordinary typed APIs") {
    assertEquals(
      LegacyMetadataExample.useLegacyMetadataUser(new LegacyMetadataUser()),
      "LegacyMetadataUser"
    )
  }

  test("existing external handler class method wins over generated method") {
    assertEquals(ExternalDebugConflictExample.result, "user-defined")
  }

  test("second external handler generated member is visible in the same compilation run") {
    assertEquals(ExternalMarkerExample.directResult, "ExternalMarked")
  }

  test("second external handler generated member works through explicitly typed APIs") {
    assertEquals(ExternalMarkerExample.useExternalMarked(new ExternalMarked()), "ExternalMarked")
  }

  test("external companion handler generated method is visible in the same compilation run") {
    assertEquals(ExternalCompanionDebugExample.directResult, "ExternalCompanionUser")
  }

  test("external companion handler generated method works through explicitly typed APIs") {
    assertEquals(ExternalCompanionDebugExample.companionResult, "ExternalCompanionUser")
  }

  test("external sibling handler output is visible in the same compilation run") {
    assertEquals(ExternalSiblingDebugExample.externalSiblingDebugResult, "ExternalSiblingUser")
  }

  test("external sibling expansion preserves the original class in typed positions") {
    assertEquals(
      ExternalSiblingDebugExample.useOriginal(ExternalSiblingDebugExample.originalUser),
      "ExternalSiblingUser"
    )
  }

  test("external sibling then class helper composes with typed class sibling and companion visibility") {
    assertEquals(
      ExternalSiblingCompositionExample.siblingThenDebugClassResult,
      "ExternalSiblingThenDebugUser"
    )
    assertEquals(
      ExternalSiblingCompositionExample.siblingThenDebugSiblingResult,
      "ExternalSiblingThenDebugUser"
    )
    assertEquals(
      ExternalSiblingCompositionExample.siblingThenDebugSibling.getClass.getSimpleName,
      "ExternalSiblingThenDebugUserExternalMeta"
    )
    assertEquals(
      ExternalSiblingCompositionExample.siblingThenDebugCompanionResult,
      42
    )
  }

  test("class helper then external sibling composes with typed class and sibling visibility") {
    assertEquals(
      ExternalSiblingCompositionExample.debugThenSiblingClassResult,
      "ExternalDebugThenSiblingUser"
    )
    assertEquals(
      ExternalSiblingCompositionExample.debugThenSiblingSiblingResult,
      "ExternalDebugThenSiblingUser"
    )
    assertEquals(
      ExternalSiblingCompositionExample.debugThenSiblingSibling.getClass.getSimpleName,
      "ExternalDebugThenSiblingUserExternalMeta"
    )
  }

  test("external sibling then companion helper keeps typed companion and sibling lanes separate") {
    assertEquals(
      ExternalSiblingCompanionCompositionExample.siblingThenCompanionCompanionResult,
      "ExternalSiblingThenCompanionUser"
    )
    assertEquals(
      ExternalSiblingCompanionCompositionExample.siblingThenCompanionExistingResult,
      42
    )
    assertEquals(
      ExternalSiblingCompanionCompositionExample.siblingThenCompanionSiblingResult,
      "ExternalSiblingThenCompanionUser"
    )
    assertEquals(
      ExternalSiblingCompanionCompositionExample.siblingThenCompanionSibling.getClass.getSimpleName,
      "ExternalSiblingThenCompanionUserExternalMeta"
    )
  }

  test("companion helper then external sibling keeps typed companion and sibling lanes separate") {
    assertEquals(
      ExternalSiblingCompanionCompositionExample.companionThenSiblingCompanionResult,
      "ExternalCompanionThenSiblingUser"
    )
    assertEquals(
      ExternalSiblingCompanionCompositionExample.companionThenSiblingExistingResult,
      84
    )
    assertEquals(
      ExternalSiblingCompanionCompositionExample.companionThenSiblingSiblingResult,
      "ExternalCompanionThenSiblingUser"
    )
    assertEquals(
      ExternalSiblingCompanionCompositionExample.companionThenSiblingSibling.getClass.getSimpleName,
      "ExternalCompanionThenSiblingUserExternalMeta"
    )
  }

  test("external companion handler preserves and extends an existing companion") {
    assertEquals(ExternalCompanionExistingExample.existingResult, 42)
    assertEquals(ExternalCompanionExistingExample.generatedResult, "ExternalCompanionExistingUser")
  }

  test("existing external companion handler method wins over generated method") {
    assertEquals(ExternalCompanionConflictExample.result, "user-defined")
  }

  test("external class and companion helper annotations compose in source order") {
    assertEquals(ExternalCompositionExample.directClassResult, "ExternalComposedUser")
    assertEquals(ExternalCompositionExample.directCompanionResult, "ExternalComposedUser")
  }

  test("external companion and class helper annotations compose in reversed source order") {
    assertEquals(ExternalCompositionExample.reversedClassResult, "ExternalComposedReversedUser")
    assertEquals(ExternalCompositionExample.reversedCompanionResult, "ExternalComposedReversedUser")
  }

  test("external helper composition preserves and extends an existing companion") {
    assertEquals(ExternalCompositionExample.existingClassResult, "ExternalComposedExistingUser")
    assertEquals(ExternalCompositionExample.existingCompanionResult, "ExternalComposedExistingUser")
    assertEquals(ExternalCompositionExample.existingPreservedResult, 42)
  }

  test("previously unlisted external label and companion handlers compose in both source orders") {
    assertEquals(ExternalCompositionExample.labelCompanionLabelResult, "ExternalLabelCompanionUser")
    assertEquals(ExternalCompositionExample.labelCompanionCompanionResult, "ExternalLabelCompanionUser")
    assertEquals(
      ExternalCompositionExample.reversedLabelCompanionLabelResult,
      "ExternalLabelCompanionReversedUser"
    )
    assertEquals(
      ExternalCompositionExample.reversedLabelCompanionCompanionResult,
      "ExternalLabelCompanionReversedUser"
    )
  }

  test("three external handlers compose through class and companion lanes") {
    assertEquals(ExternalCompositionExample.threeStepDebugResult, "ExternalThreeStepUser")
    assertEquals(ExternalCompositionExample.threeStepLabelResult, "ExternalThreeStepUser")
    assertEquals(ExternalCompositionExample.threeStepCompanionResult, "ExternalThreeStepUser")
    assertEquals(ExternalCompositionExample.threeStepExistingResult, 42)
  }

  test("external class helper annotations compose on the same class body") {
    assertEquals(ExternalSameTargetCompositionExample.directDebugResult, "ExternalSameTargetUser")
    assertEquals(ExternalSameTargetCompositionExample.directLabelResult, "ExternalSameTargetUser")
    assertEquals(ExternalSameTargetCompositionExample.directExistingResult, 42)
  }

  test("external class helper annotations compose on the same class body in reversed order") {
    assertEquals(ExternalSameTargetCompositionExample.reversedDebugResult, "ExternalSameTargetReversedUser")
    assertEquals(ExternalSameTargetCompositionExample.reversedLabelResult, "ExternalSameTargetReversedUser")
  }

  test("external helper composition preserves unhandled runtime annotations") {
    assertEquals(ExternalSameTargetCompositionExample.unhandledDebugResult, "ExternalSameTargetWithUnhandledUser")
    assertEquals(ExternalSameTargetCompositionExample.unhandledLabelResult, "ExternalSameTargetWithUnhandledUser")
    assertEquals(ExternalSameTargetCompositionExample.unhandledMarkerValue, "kept")
  }

  test("different closed profiles compose union then restricted on their shared trait target") {
    assertEquals(
      MixedClosedProfileCompositionExample.unionThenRestrictedUnionResult,
      "MixedProfileUnionThenRestricted"
    )
    assertEquals(
      MixedClosedProfileCompositionExample.unionThenRestrictedRestrictedResult,
      "MixedProfileUnionThenRestricted"
    )
    assertEquals(
      MixedClosedProfileCompositionExample.unionThenRestrictedPreserved,
      42
    )
  }

  test("different closed profiles compose restricted then union on their shared trait target") {
    assertEquals(
      MixedClosedProfileCompositionExample.restrictedThenUnionRestrictedResult,
      "MixedProfileRestrictedThenUnion"
    )
    assertEquals(
      MixedClosedProfileCompositionExample.restrictedThenUnionUnionResult,
      "MixedProfileRestrictedThenUnion"
    )
    assertEquals(
      MixedClosedProfileCompositionExample.restrictedThenUnionPreserved,
      84
    )
  }

  test("external typed label composition preserves a parameterized later annotation") {
    assertEquals(ExternalTypedLabelCompositionExample.directDebugResult, "ExternalTypedLabelUser")
    assertEquals(ExternalTypedLabelCompositionExample.directTypedLabelResult, "kept")
  }

  test("external typed label composition preserves a parameterized earlier annotation") {
    assertEquals(ExternalTypedLabelCompositionExample.reversedDebugResult, "ExternalTypedLabelReversedUser")
    assertEquals(ExternalTypedLabelCompositionExample.reversedTypedLabelResult, "kept")
  }

  test("external typed label accepts a named value argument") {
    assertEquals(
      ExternalTypedLabelCompositionExample.namedTypedLabelResult,
      "named-label"
    )
  }

  test("external typed label retains legacy simple-import compatibility") {
    assertEquals(
      ExternalTypedLabelCompositionExample.simpleImportedTypedLabelResult,
      "simple-import-label"
    )
  }

  test("external typed label preserves an unhandled runtime annotation") {
    assertEquals(
      ExternalTypedLabelCompositionExample.unhandledTypedLabelResult,
      "preserved-label"
    )
    assertEquals(
      ExternalTypedLabelCompositionExample.unhandledMarkerValue,
      "typed-marker-kept"
    )
  }

  test("@gen and externalDebug compose in source order") {
    assertEquals(GenCompositionExample.directGeneratedResult, "hello A")
    assertEquals(GenCompositionExample.directExternalResult, "GenCompositionUser")
    assertEquals(GenCompositionExample.directCompanionResult, "hello A")
    assertEquals(GenCompositionExample.directSiblingMeta.getClass.getSimpleName, "GenCompositionUserMeta")
  }

  test("externalDebug and @gen compose in reversed source order") {
    assertEquals(GenCompositionExample.reversedGeneratedResult, "hello A")
    assertEquals(GenCompositionExample.reversedExternalResult, "GenCompositionReversedUser")
    assertEquals(GenCompositionExample.reversedCompanionResult, "hello A")
    assertEquals(GenCompositionExample.reversedSiblingMeta.getClass.getSimpleName, "GenCompositionReversedUserMeta")
  }

  test("@gen and externalDebug composition preserves and extends an existing companion") {
    assertEquals(GenCompositionExample.existingGeneratedResult, "hello A")
    assertEquals(GenCompositionExample.existingExternalResult, "GenCompositionExistingUser")
    assertEquals(GenCompositionExample.existingCompanionResult, "hello A")
    assertEquals(GenCompositionExample.existingPreservedResult, 42)
    assertEquals(GenCompositionExample.existingSiblingMeta.getClass.getSimpleName, "GenCompositionExistingUserMeta")
  }

  test("@gen and externalCompanionDebug accumulate companion output in source order") {
    assertEquals(GenCompanionCompositionExample.directHello, "hello A")
    assertEquals(GenCompanionCompositionExample.directFactoryHello, "hello A")
    assertEquals(GenCompanionCompositionExample.directCompanionDebug, "GenCompanionUser")
    assertEquals(GenCompanionCompositionExample.directMeta.getClass.getSimpleName, "GenCompanionUserMeta")
  }

  test("externalCompanionDebug and @gen accumulate companion output in reversed source order") {
    assertEquals(GenCompanionCompositionExample.reversedHello, "hello A")
    assertEquals(GenCompanionCompositionExample.reversedFactoryHello, "hello A")
    assertEquals(GenCompanionCompositionExample.reversedCompanionDebug, "ReversedGenCompanionUser")
    assertEquals(GenCompanionCompositionExample.reversedMeta.getClass.getSimpleName, "ReversedGenCompanionUserMeta")
  }

  test("gen companion composition preserves and extends an existing companion") {
    assertEquals(GenCompanionCompositionExample.existingHello, "hello A")
    assertEquals(GenCompanionCompositionExample.existingFactoryHello, "hello A")
    assertEquals(GenCompanionCompositionExample.existingCompanionDebug, "ExistingGenCompanionUser")
    assertEquals(GenCompanionCompositionExample.existingMember, 42)
    assertEquals(GenCompanionCompositionExample.existingMeta.getClass.getSimpleName, "ExistingGenCompanionUserMeta")
  }

  test("reversed gen companion composition preserves and extends an existing companion") {
    assertEquals(GenCompanionCompositionExample.reversedExistingFactoryHello, "hello A")
    assertEquals(GenCompanionCompositionExample.reversedExistingCompanionDebug, "ReversedExistingGenCompanionUser")
    assertEquals(GenCompanionCompositionExample.reversedExistingMember, 84)
    assertEquals(GenCompanionCompositionExample.reversedExistingMeta.getClass.getSimpleName, "ReversedExistingGenCompanionUserMeta")
  }

  test("gen companion composition preserves user-defined generated-name methods independently") {
    assertEquals(GenCompanionCompositionExample.userFactoryName, "user-A")
    assertEquals(GenCompanionCompositionExample.userFactoryCompanionDebug, "UserFactoryGenCompanionUser")
    assertEquals(GenCompanionCompositionExample.userDebugFactoryHello, "hello A")
    assertEquals(GenCompanionCompositionExample.userDebugCompanionDebug, "user-defined")
  }

  test("generated companion factory is visible in the same compilation run") {
    assertEquals(Example.companionResult, "hello A")
  }

  test("generated methods and companions work through explicitly typed APIs") {
    assertEquals(Example.useUser(new User("A")), "hello A")
    assertEquals(Example.createUser().generatedHello, "hello A")
  }

  test("generated sibling class is visible in the same compilation run") {
    assertEquals(Example.siblingMeta.getClass.getSimpleName, "UserMeta")
  }

  test("generated sibling class can be used in typed positions") {
    assertEquals(Example.createMeta().getClass.getSimpleName, "UserMeta")
  }

  test("unannotated classes do not get generatedHello") {
    assert(!UnannotatedExample.methodNames.contains("generatedHello"))
    assert(!UnannotatedExample.methodNames.contains("debugName"))
    assert(!UnannotatedExample.siblingClassExists)
  }

  test("constructor parameters without val still work for the narrow name-based rewrite") {
    assertEquals(ConstructorParamExample.result, "hello B")
  }

  test("multiple annotated classes in the same file do not interfere") {
    assertEquals(multi.MultipleAnnotatedExample.existingValue, 42)
    assertEquals(multi.MultipleAnnotatedExample.userHello, "hello A")
    assertEquals(multi.MultipleAnnotatedExample.orderHello, "hello B")
    assertEquals(multi.MultipleAnnotatedExample.createUserMeta().getClass.getSimpleName, "UserMeta")
    assertEquals(multi.MultipleAnnotatedExample.createOrderMeta().getClass.getSimpleName, "OrderMeta")
  }

  test("existing companion members are preserved when generatedFactory is merged") {
    assertEquals(multi.MultipleAnnotatedExample.existingValue, 42)
    assertEquals(multi.MultipleAnnotatedExample.createUser().generatedHello, "hello A")
  }

  test("existing generatedFactory wins over the plugin-generated one") {
    assertEquals(factoryconflict.FactoryConflictExample.result, "hello A!")
  }

  test("explicit class-shape admission preserves supported modifier and constructor-independent handlers") {
    assertEquals(ClassShapeAdmissionExample.finalGenResult, "hello final")
    assertEquals(ClassShapeAdmissionExample.sealedGenResult, "hello sealed")
    assertEquals(ClassShapeAdmissionExample.abstractDebugResult, "AbstractDebugUser")
    assertEquals(
      ClassShapeAdmissionExample.abstractExternalDebugResult,
      "AbstractExternalDebugUser"
    )
    assertEquals(
      ClassShapeAdmissionExample.abstractExternalCompanionResult,
      "AbstractExternalCompanionUser"
    )
    assertEquals(
      ClassShapeAdmissionExample.abstractExternalSiblingResult,
      "AbstractExternalSiblingUser"
    )
    assertEquals(
      ClassShapeAdmissionExample.abstractExternalLabelResult,
      "AbstractExternalLabelUser"
    )
    assertEquals(
      ClassShapeAdmissionExample.abstractExternalTypedLabelResult,
      "typed-abstract"
    )
    assertEquals(
      ClassShapeAdmissionExample.abstractExternalMarkerResult,
      "AbstractExternalMarkerUser"
    )
  }

  test("restricted generic trait handler creates a callable companion apply") {
    assert(
      RestrictedGenericTraitApplyExample.returned eq
        RestrictedGenericTraitApplyExample.supplied
    )
    assertEquals(RestrictedGenericTraitApplyExample.returned.parentValue, 7)
    assertEquals(RestrictedGenericTraitApplyExample.returned.existingValue, 8)
    assertEquals(
      RestrictedGenericTraitApplyExample.preservedMarker,
      "restricted-trait-kept"
    )
  }

  test("restricted generic trait handler leases and merges an existing companion in order") {
    assert(
      RestrictedGenericTraitApplyExample.existingReturned eq
        RestrictedGenericTraitApplyExample.existingSupplied
    )
    assertEquals(RestrictedExistingShow.preservedBefore, 41)
    assertEquals(RestrictedExistingShow.Nested.apply(1), 2)
    assertEquals(RestrictedExistingShow.applyLike(1), 3)
    assertEquals(RestrictedExistingShow.preservedAfter, 43)
  }

  test("an existing direct apply wins without duplication") {
    assert(
      RestrictedGenericTraitApplyExample.directReturned eq
        RestrictedGenericTraitApplyExample.directSupplied
    )
    assertEquals(RestrictedDirectApplyShow.directApplyCalls, 1)
  }
