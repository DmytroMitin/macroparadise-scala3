package p218.negative

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.{ExpansionChanges, ExpansionInput, ExpansionOutcome, expander}
import scala.annotation.StaticAnnotation

@paradise3.api.embeddedExpander
@expander("p218.negative.Other")
final class metadataCollision extends StaticAnnotation

object metadataCollision:
  def transform(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.Structured(ExpansionChanges())
