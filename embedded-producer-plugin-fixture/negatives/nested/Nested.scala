package p218.negative

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.{ExpansionChanges, ExpansionInput, ExpansionOutcome}
import scala.annotation.StaticAnnotation

object NestedOwner:
  @paradise3.api.embeddedExpander
  final class nested extends StaticAnnotation

  object nested:
    def transform(input: ExpansionInput)(using Context): ExpansionOutcome =
      ExpansionOutcome.Structured(ExpansionChanges())
