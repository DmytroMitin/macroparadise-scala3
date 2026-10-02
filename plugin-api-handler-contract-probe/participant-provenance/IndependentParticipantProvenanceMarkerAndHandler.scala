package contractprobeprovenance

import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Names.*
import paradise3.api.*
import paradise3.api.helpers.ExpansionHelpers
import scala.annotation.StaticAnnotation

@expander("contractprobeprovenance.ParticipantInstanceHandler")
final class instance extends StaticAnnotation

@expander("contractprobeprovenance.ParticipantApplyHandler")
final class apply extends StaticAnnotation

final class ParticipantInstanceHandler extends ExpansionHandler:
  val annotationName = "contractprobeprovenance.instance"

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    val participantNames = input.sourceOrderedHandledAnnotationNames
    val context =
      if participantNames == List(annotationName) then "standalone"
      else if participantNames.contains("contractprobeprovenance.apply") then "stacked"
      else "unexpected"
    ExpansionEdit.finish:
      ExpansionHelpers.placeMemberInPrimary(
        ExpansionEdit.start(input).toOption.get,
        DefDef(
          termName("participantContext"),
          Nil,
          Ident(typeName("String")),
          Literal(Constant(context))
        )
      )

final class ParticipantApplyHandler extends ExpansionHandler:
  val annotationName = "contractprobeprovenance.apply"

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.Structured(ExpansionChanges())
