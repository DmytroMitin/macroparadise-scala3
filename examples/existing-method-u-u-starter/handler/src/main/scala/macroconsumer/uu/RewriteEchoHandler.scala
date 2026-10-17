package macroconsumer.uu

import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.*
import quasiquotes.definitions.dotty.ExistingClassUntypedRewrite
import quasiquotes.definitions.dotty.ExistingClassUntypedRewrite.*
import quasiquotes.parser.TermShape
import quasiquotes.types.TypeNormalForm

final class RewriteEchoHandler extends ExpansionHandler:
  val annotationName: String = "starter.marker.rewriteEcho"

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    rewrite(input) match
      case Right(result) =>
        ExpansionOutcome.Structured(
          ExpansionChanges(
            primary = PrimaryChange.Replace(ExpansionTarget.Class(result.tree))
          )
        )
      case Left(problem) =>
        ExpansionOutcome.Rejected(List(
          ExpansionDiagnostic(
            s"EXISTING_METHOD_U_U_${problem.code}: ${problem.detail}",
            input.currentAnnotation.sourcePos
          )
        ))

  private def rewrite(
      input: ExpansionInput
  )(using Context): Either[Failure, Result] =
    for
      original <- input.primary match
        case ExpansionTarget.Class(tree) => Right(tree)
        case other =>
          Left(Failure("UNSUPPORTED_TARGET", s"expected CLASS but found ${other.kind}"))
      capture <- ExistingClassUntypedRewrite.capture(original)
      candidates = capture.members.filter(member =>
        member.kind == MemberKind.Method && member.diagnosticName.contains("echo")
      )
      _ <- Either.cond(
        candidates.size == 1,
        (),
        Failure(
          "ECHO_SELECTION_FAILED",
          s"expected exactly one direct echo method; found ${candidates.size}"
        )
      )
      selected = candidates.head
      method <- capture.method(selected.ref)
      _ <- Either.cond(
        method.parameterClauses.size == 1 &&
          method.parameterClauses.head.kind == ParameterClauseKind.Ordinary &&
          method.parameterClauses.head.parameters.size == 1,
        (),
        Failure("ECHO_SHAPE_UNSUPPORTED", "echo must have one ordinary unary parameter clause")
      )
      parameter = method.parameterClauses.head.parameters.head
      parameterReference <- method.parameterScope.reference(parameter.ref)
      replacementBody = TermShape.Apply(
        TermShape.Select(TermShape.Identifier("Math", false), "abs"),
        List(parameterReference)
      )
      parameterPlan <- capture.emptyPlan.replaceParameterType(
        parameter.ref,
        TypeNormalForm.STypeIdent("Int")
      )
      resultPlan <- parameterPlan.replaceResultType(
        method.ref,
        TypeNormalForm.STypeIdent("Int")
      )
      plan <- resultPlan.replaceBody(method.ref, replacementBody)
      result <- ExistingClassUntypedRewrite(capture, plan)
      _ <- verifyPreservation(original, capture, selected, result)
    yield result

  private def verifyPreservation(
      original: untpd.TypeDef,
      capture: Capture,
      edited: MemberView,
      result: Result
  ): Either[Failure, Unit] =
    val identities = result.directMemberIdentities
    val untouched = capture.members.filterNot(_.index == edited.index)
    val untouchedStable = untouched.forall(member =>
      identities.lift(member.index).exists(_.sameObjectAs(member.identity))
    )
    val editedFresh = identities
      .lift(edited.index)
      .exists(identity => !identity.sameObjectAs(edited.identity))
    val valid =
      result.changed &&
        !result.tree.eq(original) &&
        identities.size == capture.members.size &&
        untouchedStable &&
        editedFresh
    Either.cond(
      valid,
      (),
      Failure(
        "PRESERVATION_CHECK_FAILED",
        s"changed=${result.changed} count=${identities.size}/${capture.members.size} " +
          s"untouchedStable=$untouchedStable editedFresh=$editedFresh"
      )
    )
