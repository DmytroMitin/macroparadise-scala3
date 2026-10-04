package p218.negatives.crossfileadaptercollision

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.{ExpansionInput, ExpansionOutcome, embeddedExpander}
import scala.annotation.StaticAnnotation

@embeddedExpander
final class collided extends StaticAnnotation

object collided:
  def transform(
      input: ExpansionInput
  )(using Context): ExpansionOutcome =
    ExpansionOutcome.Expanded(List(input.primary.tree))
