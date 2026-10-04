package p218.negative

import paradise3.api.{ExpansionChanges, ExpansionInput, ExpansionOutcome}
import scala.annotation.StaticAnnotation

@paradise3.api.embeddedExpander
final class wrongClauses extends StaticAnnotation

object wrongClauses:
  def transform(input: ExpansionInput): ExpansionOutcome =
    ExpansionOutcome.Structured(ExpansionChanges())
