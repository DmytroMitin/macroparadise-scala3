package demo

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.*
import paradise3.api.helpers.ExpansionHelpers

final class sameFileDebug extends scala.annotation.StaticAnnotation

final class SameFileDebugExpander extends ExpansionHandler:
  val annotationName: String = "sameFileDebug"
  val admissions = List(ExpansionAdmission(ExpansionTargetKind.Class, ExpansionShapeProfile.OrdinaryTemplate))

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionEdit.finish:
      ExpansionEdit.start(input).flatMap: edit =>
        ExpansionHelpers.placeMemberInPrimary(
          edit,
          dotty.tools.dotc.ast.untpd.DefDef(
            dotty.tools.dotc.core.Names.termName("sameFileDebugName"),
            Nil,
            dotty.tools.dotc.ast.untpd.Ident(dotty.tools.dotc.core.Names.typeName("String")),
            dotty.tools.dotc.ast.untpd.Literal(dotty.tools.dotc.core.Constants.Constant(input.primary.name))
          )
        )

@sameFileDebug
class SameFileUser

val sameFileResult: String = new SameFileUser().sameFileDebugName
