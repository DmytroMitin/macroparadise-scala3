package contractprobe

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.{ExpansionInput, ExpansionOutcome, ParadiseAnnotationExpander, expander}
import paradise3.api.helpers.ExpansionHelpers
import scala.annotation.StaticAnnotation

@expander("contractprobe.IndependentHandler")
final class IndependentMarker extends StaticAnnotation

final class IndependentHandler extends ParadiseAnnotationExpander:
  val annotationName: String = "IndependentMarker"

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionHelpers.withAnnotatedClassView(input): view =>
      ExpansionHelpers.addStringMethodToClass(
        input,
        methodName = "independentHandlerName",
        value = view.className
      )
