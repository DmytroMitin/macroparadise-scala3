package p218.negatives.crossfileshadow

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.{ExpansionInput, ExpansionOutcome, embeddedExpander}
import scala.annotation.StaticAnnotation

@embeddedExpander
final class shadowed extends StaticAnnotation

object shadowed:
  def transform(
      input: ExpansionInput
  )(using Context): ExpansionOutcome =
    ExpansionOutcome.Expanded(List(input.primary.tree))
