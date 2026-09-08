package contractprobeself

import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Names.{termName, typeName}
import paradise3.api.{ExpansionAdmission, ExpansionEdit, ExpansionHandler, ExpansionInput, ExpansionOutcome, ExpansionShapeProfile, ExpansionTarget, ExpansionTargetKind, expander}
import paradise3.api.helpers.ExpansionHelpers
import scala.annotation.StaticAnnotation

@expander("contractprobeself.IndependentSelfTraitHandler")
final class IndependentSelfTraitMarker extends StaticAnnotation

final class IndependentSelfTraitHandler extends ExpansionHandler:
  val annotationName: String = "IndependentSelfTraitMarker"
  val admissions = List(ExpansionAdmission(ExpansionTargetKind.Trait, ExpansionShapeProfile.NoTypeOrValueParameters))

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    input.primary match
      case ExpansionTarget.Trait(tree) =>
        given dotty.tools.dotc.util.SourceFile = tree.source
        val template = tree.rhs.asInstanceOf[Template]
        val self =
          if template.self != EmptyValDef then template.self
          else untpd.ValDef(termName("$macroparadise$self"), untpd.Ident(typeName(input.primary.name)), EmptyTree)
        val generated = untpd.TypeDef(typeName("Self"), untpd.SingletonTypeTree(untpd.Ident(self.name)))
        ExpansionEdit.finish(
          ExpansionEdit.start(input).flatMap(edit => ExpansionHelpers.prepareTraitSelf(edit, self, List(generated)))
        )
      case _ => ExpansionOutcome.Rejected(Nil)
