package contractprobebody

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.{DefinitionPlacement, ExpansionDiagnostic, ExpansionEdit, ExpansionHandler, ExpansionInput, ExpansionOutcome, ExpansionTargetBodyView, ExpansionTargetKind, ExpansionTargetTypeStructureView, expander}
import paradise3.api.ExpansionTargetBodyView.*
import paradise3.api.ExpansionTargetTypeStructureView.*
import paradise3.api.ExpansionTargetView.Variance
import paradise3.api.helpers.{ExpansionHelpers, MissingCompanionPolicy}
import scala.annotation.StaticAnnotation

@expander("contractprobebody.IndependentBodyViewHandler")
final class IndependentBodyViewMarker extends StaticAnnotation

final class IndependentBodyViewHandler extends ExpansionHandler:
  val annotationName: String = "IndependentBodyViewMarker"

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    (input.targetTypeStructureView, input.targetBodyView) match
      case (Left(diagnostic), _) =>
        ExpansionOutcome.Rejected(List(diagnostic))
      case (_, Left(diagnostic)) =>
        ExpansionOutcome.Rejected(List(diagnostic))
      case (Right(structure), Right(body)) =>
        firstRejectedModifier(structure, body) match
          case Some((modifier, pos)) =>
            ExpansionOutcome.Rejected(
              List(ExpansionDiagnostic(s"unsupported normalized modifier `$modifier` for IndependentBodyViewMarker", pos))
            )
          case None if isRepresentativeAdd(structure, body) =>
            val method = dotty.tools.dotc.ast.untpd.DefDef(
              dotty.tools.dotc.core.Names.termName("independentBodyView"),
              Nil,
              dotty.tools.dotc.ast.untpd.Ident(dotty.tools.dotc.core.Names.typeName("String")),
              dotty.tools.dotc.ast.untpd.Literal(dotty.tools.dotc.core.Constants.Constant((structure.typeParameters.map(_.name) ::: structure.directTypeMembers.map(_.name)).mkString(",")))
            )
            ExpansionEdit.finish(
              ExpansionEdit.start(input).flatMap(edit => ExpansionHelpers.placeMemberInCompanion(edit, method, MissingCompanionPolicy.Create(ExpansionTargetKind.Object, DefinitionPlacement.AfterPrimary)))
            )
          case None =>
            ExpansionOutcome.Rejected(
              List(ExpansionDiagnostic("unsupported normalized type structure for IndependentBodyViewMarker", structure.pos))
            )

  private val rejectedModifierFlags = Set("infix", "erased")

  private def firstRejectedModifier(
      structure: ExpansionTargetTypeStructureView,
      body: ExpansionTargetBodyView
  ) =
    structure.directTypeMembers
      .flatMap(member => member.modifiers.unsupportedFlags.filter(rejectedModifierFlags).map(_ -> member.pos))
      .headOption
      .orElse(
        body.members
          .flatMap(member => member.method.toList)
          .flatMap(method => method.modifiers.unsupportedFlags.filter(rejectedModifierFlags).map(_ -> method.pos))
          .headOption
      )

  private def isRepresentativeAdd(
      structure: ExpansionTargetTypeStructureView,
      body: ExpansionTargetBodyView
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
