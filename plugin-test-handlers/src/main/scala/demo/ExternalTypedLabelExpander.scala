package demo

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.{
  AnnotationApplication,
  ExpansionCompositionPolicy,
  ExpansionInput,
  ExpansionOutcome,
  ParadiseAnnotationExpander
}
import paradise3.api.helpers.ExpansionHelpers

final class ExternalTypedLabelExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "externalTypedLabel"
  override val compositionPolicy: ExpansionCompositionPolicy =
    ExpansionCompositionPolicy.SourceOrdered

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionHelpers.withAnnotatedClassView(input): _ =>
      val validatedValue =
        for
          application <- AnnotationApplication.fromInput(input)
          _ <- application.requireExactlyOneTypeArgument
          value <- application.requireSingleStringLiteralArgument("value")
        yield value

      validatedValue match
        case Right(value) =>
          ExpansionHelpers.addStringMethodToClass(
            input,
            "externalTypedLabel",
            value
          )
        case Left(diagnostic) =>
          ExpansionHelpers.rejected(diagnostic, input.annotatedClass)
