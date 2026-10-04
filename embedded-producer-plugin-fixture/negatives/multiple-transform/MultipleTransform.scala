package p218.negative

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.{ExpansionChanges, ExpansionInput, ExpansionOutcome}
import scala.annotation.StaticAnnotation

@paradise3.api.embeddedExpander
final class multipleTransform extends StaticAnnotation

object multipleTransform:
  def transform(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.Structured(ExpansionChanges())

  def transform(input: ExpansionInput, extra: Int)(using Context): ExpansionOutcome =
    ExpansionOutcome.Structured(ExpansionChanges())
