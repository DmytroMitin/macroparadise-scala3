package contractprobeunion

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Names.*
import paradise3.api.{DefinitionPlacement, ExpansionDiagnostic, ExpansionEdit, ExpansionHandler, ExpansionInput, ExpansionOutcome, ExpansionTargetKind, ExpansionTargetView, expander}
import paradise3.api.ExpansionTargetView.{DefinitionKind, Variance}
import paradise3.api.helpers.{ExpansionHelpers, MissingCompanionPolicy}
import scala.annotation.StaticAnnotation

@expander("contractprobeunion.IndependentClosedTargetUnionHandler")
final class IndependentClosedTargetUnionMarker extends StaticAnnotation

final class IndependentClosedTargetUnionHandler extends ExpansionHandler:
  val annotationName: String = "IndependentClosedTargetUnionMarker"

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    input.targetView match
      case Left(diagnostic) => ExpansionOutcome.Rejected(List(diagnostic))
      case Right(view) if supports(view) =>
        val method = DefDef(termName("closedTargetUnionInvoked"), Nil, Ident(typeName("String")), Literal(Constant(input.primary.name)))
        ExpansionEdit.finish(
          ExpansionEdit.start(input).flatMap(edit => ExpansionHelpers.placeMemberInCompanion(
            edit,
            method,
            MissingCompanionPolicy.Create(ExpansionTargetKind.Object, DefinitionPlacement.AfterPrimary)
          ))
        )
      case Right(view) =>
        ExpansionOutcome.Rejected(List(ExpansionDiagnostic(
          "IndependentClosedTargetUnionMarker supports either one invariant unbounded trait parameter or two invariant upper-bounded trait parameters",
          view.classPos
        )))

  private def supports(view: ExpansionTargetView): Boolean =
    val ordinaryTrait =
      view.definitionKind == DefinitionKind.Trait &&
      !view.modifiers.isCase &&
      !view.modifiers.isSealed &&
      view.constructorClauses.forall(_.parameters.isEmpty)
    val oneUnbounded = view.typeParameters match
      case parameter :: Nil =>
        parameter.variance == Variance.Invariant &&
        parameter.isOrdinaryUnbounded &&
        !parameter.hasContextBounds
      case _ => false
    val twoUpperBounded = view.typeParameters match
      case first :: second :: Nil =>
        List(first, second).forall(parameter =>
          parameter.variance == Variance.Invariant &&
          parameter.isOrdinaryUpperBounded &&
          !parameter.hasContextBounds
        )
      case _ => false
    ordinaryTrait && (oneUnbounded || twoUpperBounded)
