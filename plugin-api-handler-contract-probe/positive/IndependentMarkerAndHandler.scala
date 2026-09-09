package contractprobe

import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Names.*
import paradise3.api.*
import paradise3.api.helpers.ExpansionHelpers
import scala.annotation.StaticAnnotation

@expander("contractprobe.IndependentHandler")
final class IndependentMarker extends StaticAnnotation

final class IndependentHandler extends ExpansionHandler:
  val annotationName = "IndependentMarker"

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    input.primary match
      case ExpansionTarget.Class(_) | ExpansionTarget.Object(_) =>
        ExpansionEdit.finish:
          for
            start <- ExpansionEdit.start(input)
            edited <- ExpansionHelpers.placeMemberInPrimary(
              start,
              DefDef(
                termName("independentHandlerName"),
                Nil,
                Ident(typeName("String")),
                Literal(Constant(input.primary.name))
              )
            )
          yield edited
      case ExpansionTarget.Trait(_) =>
        ExpansionOutcome.Rejected(
          List(ExpansionDiagnostic("IndependentMarker supports class and object primaries, not traits", input.currentAnnotation.sourcePos))
        )
