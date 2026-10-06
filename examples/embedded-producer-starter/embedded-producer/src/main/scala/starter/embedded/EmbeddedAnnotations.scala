package starter.embedded

import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Names.{termName, typeName}
import paradise3.api.*
import paradise3.api.embeddedExpander
import paradise3.api.helpers.ExpansionTransforms
import scala.annotation.StaticAnnotation

@embeddedExpander
final class identityEmbedded extends StaticAnnotation

object identityEmbedded:
  def transform(input: ExpansionInput)(using Context): ExpansionOutcome =
    input.primary match
      case ExpansionTarget.Class(_) =>
        ExpansionEdit.finish(ExpansionEdit.start(input))
      case _ =>
        ExpansionOutcome.Rejected(
          List(
            ExpansionDiagnostic(
              "@identityEmbedded requires a class",
              input.currentAnnotation.sourcePos
            )
          )
        )

@embeddedExpander
final class addGreeting(prefix: String) extends StaticAnnotation

object addGreeting:
  private def greetingMethod(value: String)(using Context): untpd.DefDef =
    untpd.DefDef(
      termName("generatedGreeting"),
      Nil,
      untpd.Ident(typeName("String")),
      untpd.Literal(Constant(value + ", Greeter!"))
    )

  def transform(input: ExpansionInput)(using Context): ExpansionOutcome =
    input.primary match
      case ExpansionTarget.Class(_) =>
        val decoded =
          for
            application <- AnnotationApplication.fromInput(input)
            prefix <- application.requireSingleStringLiteralArgument("prefix")
          yield prefix

        decoded match
          case Left(problem) =>
            ExpansionOutcome.Rejected(List(problem))
          case Right(prefix) =>
            ExpansionEdit.finish:
              ExpansionEdit.start(input).flatMap(
                ExpansionTransforms.placeMemberInPrimary(
                  greetingMethod(prefix)
                )
              )
      case _ =>
        ExpansionOutcome.Rejected(
          List(
            ExpansionDiagnostic(
              "@addGreeting requires a class",
              input.currentAnnotation.sourcePos
            )
          )
        )
