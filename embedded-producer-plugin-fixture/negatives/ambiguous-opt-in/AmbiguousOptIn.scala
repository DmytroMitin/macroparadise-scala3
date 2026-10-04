package p218.negative

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.{embeddedExpander => renamed}
import paradise3.api.{ExpansionChanges, ExpansionInput, ExpansionOutcome}
import scala.annotation.StaticAnnotation

@renamed
final class ambiguousOptIn extends StaticAnnotation

object ambiguousOptIn:
  def transform(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.Structured(ExpansionChanges())
