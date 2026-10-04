package p218.negative

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.{ExpansionInput, ExpansionOutcome}
import scala.annotation.StaticAnnotation

@paradise3.api.embeddedExpander
final class wrongTypes extends StaticAnnotation

object wrongTypes:
  def transform(input: String)(using Context): ExpansionOutcome =
    throw new IllegalStateException(input)
