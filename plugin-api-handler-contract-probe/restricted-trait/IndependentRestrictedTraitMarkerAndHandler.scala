package external.traitprobe

import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags.{Given, Param}
import dotty.tools.dotc.core.Names.*
import paradise3.api.{ExpansionInput, ExpansionOutcome, ExpansionTargetProfile, ParadiseAnnotationExpander, expander}
import paradise3.api.helpers.{CompanionMethodConflictPolicy, ExpansionHelpers}

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
      ExpansionHelpers.addMethodToCompanion(
        input,
        makeApply(input, parameterName),
        CompanionMethodConflictPolicy.PreserveExisting
      )

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
