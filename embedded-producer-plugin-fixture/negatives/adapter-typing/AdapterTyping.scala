package p218.negative.typing

import scala.annotation.StaticAnnotation

final class ExpansionInput
final class ExpansionOutcome
final class Context

@paradise3.api.embeddedExpander
final class adapterTyping extends StaticAnnotation

object adapterTyping:
  def transform(input: ExpansionInput)(using Context): ExpansionOutcome =
    new ExpansionOutcome
