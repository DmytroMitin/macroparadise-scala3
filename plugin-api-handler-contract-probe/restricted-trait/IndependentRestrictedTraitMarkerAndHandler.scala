package external.traitprobe

import dotty.tools.dotc.ast.Trees
import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags.{Given, Param}
import dotty.tools.dotc.core.Names.*
import paradise3.api.{ExpansionInput, ExpansionOutcome, ExpansionTargetProfile, ParadiseAnnotationExpander, StructuredExpansionOutput, expander}
import paradise3.api.helpers.ExpansionHelpers

import scala.annotation.StaticAnnotation

@expander("external.traitprobe.RestrictedApplyHandler")
final class RestrictedApply extends StaticAnnotation

@expander("external.traitprobe.DefaultClassOnlyHandler")
final class DefaultTraitAttempt extends StaticAnnotation

final class DefaultClassOnlyHandler extends ParadiseAnnotationExpander:
  val annotationName: String = "DefaultTraitAttempt"

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.NotApplicable

final class RestrictedApplyHandler extends ParadiseAnnotationExpander:
  val annotationName: String = "RestrictedApply"
  override val targetProfile: ExpansionTargetProfile =
    ExpansionTargetProfile.RestrictedGenericTraitApply
  override val consumesExistingCompanion: Boolean = true

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionHelpers.withAnnotatedClassView(input): view =>
      val parameterName = view.typeParameters.head.name
      val primary = stripCurrentAnnotation(input)
      val companion =
        input.existingCompanion match
          case Some(existing) => mergeApply(existing, input, parameterName)
          case None => freshCompanion(input, List(makeApply(input, parameterName)))
      ExpansionOutcome.Structured(StructuredExpansionOutput(primary, Some(companion), Nil))

  private def stripCurrentAnnotation(input: ExpansionInput)(using Context): TypeDef =
    val currentMods = Trees.mods(input.annotatedClass)
    val preserved =
      input.currentAnnotation match
        case Some(current) => currentMods.annotations.filterNot(_ eq current)
        case None => Nil
    input.annotatedClass
      .withMods(currentMods.withAnnotations(preserved))
      .asInstanceOf[TypeDef]

  private def mergeApply(
      existing: ModuleDef,
      input: ExpansionInput,
      parameterName: String
  )(using Context): ModuleDef =
    val template = existing.impl
    val existingBody = template.body(using summon[Context])
    val mergedBody =
      if existingBody.exists(directApply) then existingBody
      else existingBody :+ makeApply(input, parameterName)
    val mergedTemplate =
      untpd.cpy.Template(template)(
        template.constr,
        template.parentsOrDerived(using summon[Context]),
        template.derived,
        template.self,
        mergedBody
      )
    untpd.cpy.ModuleDef(existing)(existing.name, mergedTemplate)

  private def directApply(tree: untpd.Tree): Boolean =
    tree match
      case member: MemberDef => member.name.toString == "apply"
      case _ => false

  private def makeApply(
      input: ExpansionInput,
      parameterName: String
  )(using Context): DefDef =
    given dotty.tools.dotc.util.SourceFile = input.annotatedClass.source
    val methodParameter =
      TypeDef(typeName(parameterName), TypeBoundsTree(EmptyTree, EmptyTree))
        .withMods(Modifiers(Param))
        .asInstanceOf[TypeDef]
    def appliedTrait: AppliedTypeTree =
      AppliedTypeTree(
        Ident(typeName(input.className)),
        List(Ident(typeName(parameterName)))
      )
    val instance =
      ValDef(termName("instance"), appliedTrait, EmptyTree)
        .withMods(Modifiers(Param | Given))
        .asInstanceOf[ValDef]
    DefDef(
      termName("apply"),
      List(List(methodParameter), List(instance)),
      appliedTrait,
      Ident(termName("instance"))
    )

  private def freshCompanion(
      input: ExpansionInput,
      body: List[untpd.Tree]
  )(using Context): ModuleDef =
    given dotty.tools.dotc.util.SourceFile = input.annotatedClass.source
    ModuleDef(
      termName(input.className),
      Template(emptyConstructor, Nil, Nil, EmptyValDef, body)
    )
