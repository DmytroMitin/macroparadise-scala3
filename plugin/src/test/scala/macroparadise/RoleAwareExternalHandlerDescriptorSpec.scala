package macroparadise

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.*

class RoleAwareExternalHandlerDescriptorSpec extends munit.FunSuite:
  test("class classification distinguishes legacy role-aware neither and both") {
    assertEquals(
      ExternalHandlerClassClassification.classify(classOf[LegacyOnly]),
      ExternalHandlerClassClassification.LegacyHandler
    )
    assertEquals(
      ExternalHandlerClassClassification.classify(classOf[RoleAwareOnly]),
      ExternalHandlerClassClassification.RoleAwareHandler
    )
    assertEquals(
      ExternalHandlerClassClassification.classify(classOf[Neither]),
      ExternalHandlerClassClassification.InvalidNeither
    )
    assertEquals(
      ExternalHandlerClassClassification.classify(classOf[Both]),
      ExternalHandlerClassClassification.InvalidAmbiguousBoth
    )
  }

  test("role-aware defaults are snapshotted exactly once") {
    val handler = RoleAwareOnly()
    val loaded = captured(handler)

    assertEquals(loaded.descriptor.annotationName, "roleAwareDescriptor")
    assertEquals(
      loaded.descriptor.targetAdmissions,
      List(RoleAwareTargetAdmission.OrdinaryTopLevelObject)
    )
    assertEquals(
      loaded.descriptor.compositionPolicy,
      ExpansionCompositionPolicy.StandaloneOnly
    )
    assertEquals(
      loaded.descriptor.oppositeCapability,
      RoleAwareOppositeCapability.PrimaryOnly
    )
    assertEquals(handler.annotationReads, 1)
    assertEquals(handler.admissionReads, 1)
    assertEquals(handler.compositionReads, 1)
    assertEquals(handler.capabilityReads, 1)
  }

  test("empty admissions fail before expansion") {
    val handler = RoleAwareOnly(admissions = Nil)

    val failure = RoleAwareExternalHandlerDescriptor
      .capture(handler, getClass.getClassLoader)
      .left.toOption.getOrElse(fail("expected failure"))

    assert(failure.diagnostic.contains("category=EMPTY_TARGET_ADMISSIONS"))
    assertEquals(handler.expandCalls, 0)
  }

  test("duplicate admissions fail before expansion") {
    val admission = RoleAwareTargetAdmission.OrdinaryTopLevelObject
    val handler = RoleAwareOnly(admissions = List(admission, admission))

    val failure = RoleAwareExternalHandlerDescriptor
      .capture(handler, getClass.getClassLoader)
      .left.toOption.getOrElse(fail("expected failure"))

    assert(failure.diagnostic.contains("category=DUPLICATE_TARGET_ADMISSION"))
    assertEquals(handler.expandCalls, 0)
  }

  test("null admissions and null admission entries fail before expansion") {
    val nullList = RoleAwareOnly(admissions = null)
    val nullListFailure = RoleAwareExternalHandlerDescriptor
      .capture(nullList, getClass.getClassLoader)
      .left.toOption.getOrElse(fail("expected null-list failure"))
    assert(nullListFailure.diagnostic.contains("category=NULL_TARGET_ADMISSIONS"))
    assertEquals(nullList.expandCalls, 0)

    val nullEntry = RoleAwareOnly(admissions = List(null))
    val nullEntryFailure = RoleAwareExternalHandlerDescriptor
      .capture(nullEntry, getClass.getClassLoader)
      .left.toOption.getOrElse(fail("expected null-entry failure"))
    assert(nullEntryFailure.diagnostic.contains("category=NULL_TARGET_ADMISSION"))
    assertEquals(nullEntry.expandCalls, 0)
  }

  private def captured(
      handler: RoleAwareOnly
  ): LoadedRoleAwareExternalHandler =
    RoleAwareExternalHandlerDescriptor.capture(handler, getClass.getClassLoader) match
      case Right(loaded) => loaded
      case Left(failure) => fail(failure.diagnostic)

  private final class LegacyOnly extends ParadiseAnnotationExpander:
    val annotationName = "legacyDescriptor"
    def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
      ExpansionOutcome.NotApplicable

  private final class RoleAwareOnly(
      admissions: List[RoleAwareTargetAdmission] =
        List(RoleAwareTargetAdmission.OrdinaryTopLevelObject)
  ) extends RoleAwareParadiseAnnotationExpander:
    var annotationReads = 0
    var admissionReads = 0
    var compositionReads = 0
    var capabilityReads = 0
    var expandCalls = 0

    def annotationName: String =
      annotationReads += 1
      "roleAwareDescriptor"

    override def targetAdmissions: List[RoleAwareTargetAdmission] =
      admissionReads += 1
      admissions

    override def compositionPolicy: ExpansionCompositionPolicy =
      compositionReads += 1
      ExpansionCompositionPolicy.StandaloneOnly

    override def oppositeCapability: RoleAwareOppositeCapability =
      capabilityReads += 1
      RoleAwareOppositeCapability.PrimaryOnly

    def expand(
        input: RoleAwareExpansionInput
    )(using Context): RoleAwareExpansionOutcome =
      expandCalls += 1
      RoleAwareExpansionOutcome.Rejected(Nil)

  private final class Neither

  private final class Both
      extends ParadiseAnnotationExpander,
        RoleAwareParadiseAnnotationExpander:
    val annotationName = "bothDescriptor"
    override val compositionPolicy =
      ExpansionCompositionPolicy.StandaloneOnly
    def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
      ExpansionOutcome.NotApplicable
    def expand(
        input: RoleAwareExpansionInput
    )(using Context): RoleAwareExpansionOutcome =
      RoleAwareExpansionOutcome.Rejected(Nil)
