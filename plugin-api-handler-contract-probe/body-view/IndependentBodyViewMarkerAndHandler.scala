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
      case Right(body) if isRepresentativeShow(body) =>
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

  private def isRepresentativeShow(body: AnnotatedClassBodyView): Boolean =
    body.members match
      case showMember :: Nil if showMember.kind == DirectMemberKind.Method =>
        showMember.method match
          case Some(show) =>
            show.name == "show" &&
            show.typeParameters.isEmpty &&
            isAbstractPublic(show) &&
            isNamedString(show.resultType) &&
            (show.parameterClauses match
              case clause :: Nil =>
                !clause.isContextual &&
                clause.parameters.map(_.name) == List("a") &&
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

  private def isNamedString(shape: DirectTypeShape): Boolean = shape match
    case DirectTypeShape.NamedType("String", _) => true
    case _ => false
