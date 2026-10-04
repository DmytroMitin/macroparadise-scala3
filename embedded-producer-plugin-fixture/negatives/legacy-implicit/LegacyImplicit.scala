package p218.negatives.legacyimplicit

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.{ExpansionInput, ExpansionOutcome, embeddedExpander}
import scala.annotation.StaticAnnotation

@embeddedExpander
final class legacyImplicit extends StaticAnnotation

object legacyImplicit:
  def transform(
      input: ExpansionInput
  )(implicit context: Context): ExpansionOutcome =
    ExpansionOutcome.Expanded(List(input.primary.tree))
