package demo

import dotty.tools.dotc.ast.Trees
import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags.{Given, Param}
import dotty.tools.dotc.core.Names.*
import paradise3.api.{ExpansionDiagnostic, ExpansionInput, ExpansionOutcome, ExpansionTargetProfile, ParadiseAnnotationExpander, StructuredExpansionOutput}
import paradise3.api.helpers.ExpansionHelpers

/** Repository-only proof fixture for the exact restricted-trait admission path. */
final class ExternalRestrictedTraitApplyExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "externalRestrictedTraitApply"
  override val targetProfile: ExpansionTargetProfile =
    ExpansionTargetProfile.RestrictedGenericTraitApply
  override val consumesExistingCompanion: Boolean = true

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    input.className match
      case "RestrictedTraitHandlerRejected" =>
        ExpansionOutcome.Rejected(
          List(ExpansionDiagnostic("restricted trait handler rejection fixture", input.annotatedClass.sourcePos)),
          input.annotatedClass
        )
      case "RestrictedTraitNonFatal" =>
        throw new IllegalStateException("restricted trait NonFatal fixture")
      case "RestrictedTraitLinkage" =>
        throw new LinkageError("restricted trait LinkageError fixture")
      case "RestrictedTraitNullStructured" =>
        ExpansionOutcome.Structured(null)
      case "RestrictedTraitWrongPrimaryName" =>
        ExpansionOutcome.Structured(
          StructuredExpansionOutput(
            freshClass(input, "WrongRestrictedTraitPrimary"),
            input.existingCompanion,
            Nil
          )
        )
      case "RestrictedTraitWrongPrimaryKind" =>
        ExpansionOutcome.Structured(
          StructuredExpansionOutput(
            freshClass(input, input.className),
            input.existingCompanion,
            Nil
          )
        )
      case "RestrictedTraitWrongCompanion" =>
        ExpansionOutcome.Structured(
          StructuredExpansionOutput(
            input.annotatedClass,
            Some(freshCompanion(input, "WrongRestrictedTraitCompanion", Nil)),
            Nil
          )
        )
      case "RestrictedTraitDuplicateAdditional" =>
        val duplicateName = s"${input.className}Duplicate"
        ExpansionOutcome.Structured(
          StructuredExpansionOutput(
            input.annotatedClass,
            input.existingCompanion,
            List(
              freshClass(input, duplicateName),
              freshClass(input, duplicateName)
            )
          )
        )
      case _ =>
        ExpansionHelpers.withAnnotatedClassView(input): view =>
          val typeParameterName = view.typeParameters.head.name
          val primary = stripCurrentAnnotation(input)
          val companion =
            input.existingCompanion match
              case Some(existing) => mergeApply(existing, input, typeParameterName)
              case None =>
                freshCompanion(
                  input,
                  input.className,
                  List(makeApply(input, typeParameterName))
                )
          ExpansionHelpers.structured(primary, Some(companion))

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
      typeParameterName: String
  )(using Context): ModuleDef =
    val template = existing.impl
    val existingBody = template.body(using summon[Context])
    val mergedBody =
      if existingBody.exists(directMemberNamedApply) then existingBody
      else existingBody :+ makeApply(input, typeParameterName)
    val mergedTemplate =
      untpd.cpy.Template(template)(
        template.constr,
        template.parentsOrDerived(using summon[Context]),
        template.derived,
        template.self,
        mergedBody
      )
    untpd.cpy.ModuleDef(existing)(existing.name, mergedTemplate)

  private def directMemberNamedApply(tree: untpd.Tree): Boolean =
    tree match
      case member: MemberDef => member.name.toString == "apply"
      case _ => false

  private def makeApply(
      input: ExpansionInput,
      typeParameterName: String
  )(using Context): DefDef =
    given dotty.tools.dotc.util.SourceFile = input.annotatedClass.source

    val methodTypeParameter =
      TypeDef(
        typeName(typeParameterName),
        TypeBoundsTree(EmptyTree, EmptyTree)
      ).withMods(Modifiers(Param)).asInstanceOf[TypeDef]
    def appliedTraitType: AppliedTypeTree =
      AppliedTypeTree(
        Ident(typeName(input.className)),
        List(Ident(typeName(typeParameterName)))
      )
    val instance =
      ValDef(termName("instance"), appliedTraitType, EmptyTree)
        .withMods(Modifiers(Param | Given))
        .asInstanceOf[ValDef]

    DefDef(
      termName("apply"),
      List(List(methodTypeParameter), List(instance)),
      appliedTraitType,
      Ident(termName("instance"))
    )

  private def freshClass(input: ExpansionInput, name: String)(using Context): TypeDef =
    given dotty.tools.dotc.util.SourceFile = input.annotatedClass.source
    TypeDef(typeName(name), freshTemplate(input, Nil))

  private def freshCompanion(
      input: ExpansionInput,
      name: String,
      body: List[untpd.Tree]
  )(using Context): ModuleDef =
    given dotty.tools.dotc.util.SourceFile = input.annotatedClass.source
    ModuleDef(termName(name), freshTemplate(input, body))

  private def freshTemplate(
      input: ExpansionInput,
      body: List[untpd.Tree]
  )(using Context): Template =
    given dotty.tools.dotc.util.SourceFile = input.annotatedClass.source
    Template(emptyConstructor, Nil, Nil, EmptyValDef, body)
