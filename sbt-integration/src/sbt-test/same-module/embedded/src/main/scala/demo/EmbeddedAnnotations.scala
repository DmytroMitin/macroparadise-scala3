package demo

import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Names.{termName, typeName}
import paradise3.api.*
import paradise3.api.embeddedExpander
import paradise3.api.helpers.ExpansionTransforms
import scala.annotation.StaticAnnotation

@embeddedExpander
final class sameModuleGreeting(prefix: String) extends StaticAnnotation

object sameModuleGreeting:
  private def greetingMethod(value: String)(using Context): untpd.DefDef =
    untpd.DefDef(
      termName("generatedGreeting"),
      Nil,
      untpd.Ident(typeName("String")),
      untpd.Literal(Constant(value + ":same-module-v1"))
    )

  def transform(input: ExpansionInput)(using Context): ExpansionOutcome =
    val decoded =
      for
        application <- AnnotationApplication.fromInput(input)
        prefix <- application.requireSingleStringLiteralArgument("prefix")
      yield prefix
    decoded match
      case Left(problem) => ExpansionOutcome.Rejected(List(problem))
      case Right(prefix) =>
        ExpansionEdit.finish:
          ExpansionEdit.start(input).flatMap(
            ExpansionTransforms.placeMemberInPrimary(greetingMethod(prefix))
          )
