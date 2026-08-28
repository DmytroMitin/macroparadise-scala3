package contractprobebody

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.{AnnotatedClassBodyView, ExpansionDiagnostic, ExpansionInput, ExpansionOutcome, ExpansionTargetProfile, ParadiseAnnotationExpander, expander}
import paradise3.api.AnnotatedClassBodyView.*
import paradise3.api.helpers.ExpansionHelpers
import scala.annotation.StaticAnnotation

@expander("contractprobebody.IndependentBodyViewHandler")
final class IndependentBodyViewMarker extends StaticAnnotation

final class IndependentBodyViewHandler extends ParadiseAnnotationExpander:
  val annotationName: String = "IndependentBodyViewMarker"
  override val targetProfile: ExpansionTargetProfile =
    ExpansionTargetProfile.RestrictedGenericTraitApply

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    input.annotatedClassBodyView match
      case Left(diagnostic) =>
        ExpansionOutcome.Rejected(List(diagnostic), input.annotatedClass)
      case Right(body) if isRepresentativeMonoid(body) =>
        ExpansionHelpers.addStringMethodToCompanion(
          input,
          methodName = "independentBodyView",
          value = body.members.map(_.name).mkString(",")
        )
      case Right(body) =>
        ExpansionOutcome.Rejected(
          List(ExpansionDiagnostic("unsupported direct body shape for IndependentBodyViewMarker", body.pos)),
          input.annotatedClass
        )

  private def isRepresentativeMonoid(body: AnnotatedClassBodyView): Boolean =
    body.members match
      case emptyMember :: combineMember :: Nil
          if emptyMember.kind == DirectMemberKind.Method && combineMember.kind == DirectMemberKind.Method =>
        (emptyMember.method, combineMember.method) match
          case (Some(empty), Some(combine)) =>
            empty.name == "empty" &&
            empty.typeParameters.isEmpty &&
            empty.parameterClauses.isEmpty &&
            isAbstractPublic(empty) &&
            isEnclosingA(empty.resultType) &&
            combine.name == "combine" &&
            combine.typeParameters.isEmpty &&
            isAbstractPublic(combine) &&
            isEnclosingA(combine.resultType) &&
            (combine.parameterClauses match
              case clause :: Nil =>
                !clause.isContextual &&
                clause.parameters.map(_.name) == List("a", "a1") &&
                clause.parameters.forall(parameter =>
                  !parameter.hasDefault && !parameter.isContextual && !parameter.isVal && !parameter.isVar &&
                    isEnclosingA(parameter.parameterType)
                )
              case _ => false)
          case _ => false
      case _ => false

  private def isAbstractPublic(method: DirectMethod): Boolean =
    method.status == DirectMethodStatus.Abstract &&
      method.modifiers.visibility == DirectVisibility.Public &&
      !method.modifiers.hasAnnotations &&
      method.modifiers.unsupportedFlags.isEmpty

  private def isEnclosingA(shape: DirectTypeShape): Boolean = shape match
    case DirectTypeShape.EnclosingTypeParameter("A", _) => true
    case _ => false
