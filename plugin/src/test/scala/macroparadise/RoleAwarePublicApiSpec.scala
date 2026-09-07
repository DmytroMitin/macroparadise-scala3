package macroparadise

import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.*

class RoleAwarePublicApiSpec extends munit.FunSuite:
  test("the public admission is a closed ordinary-object value") {
    val admission = RoleAwareTargetAdmission.OrdinaryTopLevelObject

    assertEquals(admission.targetKind, ExpansionTargetKind.Object)
    assertEquals(admission.shapeProfile, RoleAwareShapeProfile.OrdinaryTopLevelObject)
    assertEquals(RoleAwareTargetAdmission.values.toList, List(admission))
  }

  test("role-aware handler defaults are object-only standalone and primary-only") {
    val handler = DefaultHandler()

    assertEquals(
      handler.targetAdmissions,
      List(RoleAwareTargetAdmission.OrdinaryTopLevelObject)
    )
    assertEquals(
      handler.compositionPolicy,
      ExpansionCompositionPolicy.StandaloneOnly
    )
    assertEquals(
      handler.oppositeCapability,
      RoleAwareOppositeCapability.PrimaryOnly
    )
  }

  private final case class DefaultHandler()
      extends RoleAwareParadiseAnnotationExpander:
    val annotationName: String = "roleAwareProbe"

    def expand(
        input: RoleAwareExpansionInput
    )(using Context): RoleAwareExpansionOutcome =
      RoleAwareExpansionOutcome.Expanded(
        RoleAwareExpansionOutput(input.primary, OppositeChange.Preserve)
      )
