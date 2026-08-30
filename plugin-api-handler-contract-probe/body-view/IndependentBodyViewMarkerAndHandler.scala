package contractprobebody

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.{AnnotatedClassBodyView, AnnotatedClassTypeStructureView, ExpansionDiagnostic, ExpansionInput, ExpansionOutcome, ExpansionTargetProfile, ParadiseAnnotationExpander, expander}
import paradise3.api.AnnotatedClassBodyView.*
import paradise3.api.AnnotatedClassTypeStructureView.*
import paradise3.api.AnnotatedClassView.Variance
import paradise3.api.helpers.ExpansionHelpers
import scala.annotation.StaticAnnotation

@expander("contractprobebody.IndependentBodyViewHandler")
final class IndependentBodyViewMarker extends StaticAnnotation

final class IndependentBodyViewHandler extends ParadiseAnnotationExpander:
  val annotationName: String = "IndependentBodyViewMarker"
  override val targetProfile: ExpansionTargetProfile =
    ExpansionTargetProfile.TwoUpperBoundedGenericTrait

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    (input.annotatedClassTypeStructureView, input.annotatedClassBodyView) match
      case (Left(diagnostic), _) =>
        ExpansionOutcome.Rejected(List(diagnostic), input.annotatedClass)
      case (_, Left(diagnostic)) =>
        ExpansionOutcome.Rejected(List(diagnostic), input.annotatedClass)
      case (Right(structure), Right(body)) if isRepresentativeAdd(structure, body) =>
        ExpansionHelpers.addStringMethodToCompanion(
          input,
          methodName = "independentBodyView",
          value = (structure.typeParameters.map(_.name) ::: structure.directTypeMembers.map(_.name)).mkString(",")
        )
      case (Right(structure), Right(_)) =>
        ExpansionOutcome.Rejected(
          List(ExpansionDiagnostic("unsupported normalized type structure for IndependentBodyViewMarker", structure.pos)),
          input.annotatedClass
        )

  private def isRepresentativeAdd(
      structure: AnnotatedClassTypeStructureView,
      body: AnnotatedClassBodyView
  ): Boolean =
    val parametersMatch = structure.typeParameters match
      case n :: m :: Nil => isCanonicalParameter(n, "N") && isCanonicalParameter(m, "M")
      case _ => false
    val memberMatches = structure.directTypeMembers match
      case out :: Nil =>
        out.name == "Out" &&
        out.bodyIndex == 0 &&
        out.kind == DirectTypeMemberKind.AbstractBounds &&
        out.typeParameters.isEmpty &&
        out.lowerBound == Bound.Absent &&
        isNat(out.upperBound) &&
        out.aliasTarget.isEmpty &&
        out.modifiers.visibility == DirectVisibility.Public &&
        !out.modifiers.hasAnnotations &&
        out.modifiers.unsupportedFlags.isEmpty
      case _ => false
    val bodyMatches = body.members match
      case out :: Nil => out.name == "Out" && out.kind == DirectMemberKind.Type
      case _ => false
    parametersMatch && memberMatches && bodyMatches

  private def isCanonicalParameter(parameter: EnclosingTypeParameter, expectedName: String): Boolean =
    parameter.name == expectedName &&
      parameter.variance == Variance.Invariant &&
      parameter.lowerBound == Bound.Absent &&
      isNat(parameter.upperBound) &&
      !parameter.hasContextBounds

  private def isNat(bound: Bound): Boolean = bound match
    case Bound.Present(DirectTypeShape.NamedType("Nat", _)) => true
    case _ => false
