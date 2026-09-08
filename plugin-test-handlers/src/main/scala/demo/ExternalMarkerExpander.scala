package demo

import dotty.tools.dotc.ast.Trees
import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Names.*
import paradise3.api.{ExpansionAdmission, ExpansionChanges, ExpansionHandler, ExpansionInput, ExpansionOutcome, ExpansionShapeProfile, ExpansionTargetKind}

final class ExternalMarkerExpander extends ExpansionHandler:
  val annotationName: String = "externalMarker"
  val admissions = List(ExpansionAdmission(ExpansionTargetKind.Class, ExpansionShapeProfile.OrdinaryTemplate))

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    input.primary.tree.asInstanceOf[TypeDef].rhs match
      case template: Template =>
        val strippedClass = stripAnnotations(input.primary.tree.asInstanceOf[TypeDef])
        val generatedMethod = makeExternalMarkerName(input.primary.tree.asInstanceOf[TypeDef].name, input.primary.tree.asInstanceOf[TypeDef].source)
        val rewrittenTemplate =
          untpd.cpy.Template(template)(
            template.constr,
            template.parentsOrDerived(using summon[Context]),
            template.derived,
            template.self,
            template.body(using summon[Context]) :+ generatedMethod
          )

        ExpansionOutcome.Expanded(List(untpd.cpy.TypeDef(strippedClass)(strippedClass.name, rewrittenTemplate)))
      case _ =>
        ExpansionOutcome.Structured(ExpansionChanges())

  private def stripAnnotations(typeDef: TypeDef)(using Context): TypeDef =
    val currentMods = Trees.mods(typeDef)
    typeDef.withMods(currentMods.withAnnotations(Nil)).asInstanceOf[TypeDef]

  private def makeExternalMarkerName(
      className: TypeName,
      source: dotty.tools.dotc.util.SourceFile
  )(using Context): DefDef =
    given dotty.tools.dotc.util.SourceFile = source

    untpd.DefDef(
      termName("externalMarkerName"),
      Nil,
      untpd.Ident(typeName("String")),
      untpd.Literal(Constant(className.toString))
    )
