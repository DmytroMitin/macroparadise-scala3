package demo

import dotty.tools.dotc.ast.Trees
import dotty.tools.dotc.ast.Trees.*
import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Names.*
import paradise3.api.{
  ExpansionCompositionPolicy,
  ExpansionInput,
  ExpansionOutcome,
  ExpansionTargetProfile,
  ParadiseAnnotationExpander
}

private object CompositionContractTrees:
  private def rebuilt(annotation: untpd.Tree)(using Context): untpd.Tree =
    annotation match
      case application: untpd.Apply =>
        untpd.Apply(application.fun, application.args)
      case other =>
        throw new IllegalArgumentException(
          s"composition fixture expected an applied annotation, got ${other.getClass.getName}"
        )

  private def renamedParameterlessAnnotation(
      annotation: untpd.Tree,
      annotationName: String
  )(using Context): untpd.Tree =
    annotation match
      case application: untpd.Apply =>
        untpd.Apply(
          renamedParameterlessAnnotation(application.fun, annotationName),
          application.args
        ).withSpan(application.span)
      case selection: untpd.Select if selection.name == termName("<init>") =>
        untpd.Select(
          renamedParameterlessAnnotation(selection.qualifier, annotationName),
          selection.name
        ).withSpan(selection.span)
      case creation: untpd.New =>
        untpd.New(
          renamedParameterlessAnnotation(creation.tpt, annotationName)
        ).withSpan(creation.span)
      case identifier: untpd.Ident =>
        untpd.Ident(typeName(annotationName)).withSpan(identifier.span)
      case other =>
        throw new IllegalArgumentException(
          s"composition fixture cannot rename annotation node ${other.getClass.getName}"
        )

  def withoutAllAnnotations(input: ExpansionInput)(using Context): untpd.TypeDef =
    val mods = Trees.mods(input.annotatedClass)
    input.annotatedClass
      .withMods(mods.withAnnotations(Nil))
      .asInstanceOf[untpd.TypeDef]

  def withoutCurrent(input: ExpansionInput)(using Context): untpd.TypeDef =
    val mods = Trees.mods(input.annotatedClass)
    input.annotatedClass
      .withMods(
        mods.withAnnotations(
          mods.annotations.filterNot(annotation =>
            input.currentAnnotation.exists(_ eq annotation)
          )
        )
      )
      .asInstanceOf[untpd.TypeDef]

  def externalSiblingDuplicate(input: ExpansionInput)(using Context): untpd.TypeDef =
    untpd.cpy.TypeDef(input.annotatedClass)(
      typeName(s"${input.annotatedClass.name}ExternalMeta"),
      input.annotatedClass.rhs
    )

  def withoutCurrentAndWithRebuiltLater(input: ExpansionInput)(using Context): untpd.TypeDef =
    given dotty.tools.dotc.util.SourceFile = input.annotatedClass.source
    val mods = Trees.mods(input.annotatedClass)
    val rebuiltAnnotations =
      mods.annotations
        .filterNot(annotation => input.currentAnnotation.exists(_ eq annotation))
        .map:
          case annotation => rebuilt(annotation)
    input.annotatedClass
      .withMods(mods.withAnnotations(rebuiltAnnotations))
      .asInstanceOf[untpd.TypeDef]

  def withoutCurrentAndWithRebuiltCurrent(input: ExpansionInput)(using Context): untpd.TypeDef =
    given dotty.tools.dotc.util.SourceFile = input.annotatedClass.source
    val mods = Trees.mods(input.annotatedClass)
    val preserved =
      mods.annotations.filterNot(annotation => input.currentAnnotation.exists(_ eq annotation))
    val reconstructed = input.currentAnnotation.toList.map(rebuilt)
    input.annotatedClass
      .withMods(mods.withAnnotations(reconstructed ++ preserved))
      .asInstanceOf[untpd.TypeDef]

  def withoutCurrentAndWithDuplicateLater(input: ExpansionInput)(using Context): untpd.TypeDef =
    given dotty.tools.dotc.util.SourceFile = input.annotatedClass.source
    val mods = Trees.mods(input.annotatedClass)
    val preserved =
      mods.annotations.filterNot(annotation => input.currentAnnotation.exists(_ eq annotation))
    val duplicate = preserved.headOption.toList.map(rebuilt)
    input.annotatedClass
      .withMods(mods.withAnnotations(preserved ++ duplicate))
      .asInstanceOf[untpd.TypeDef]

  def withoutCurrentAndWithFreshHandled(
      input: ExpansionInput,
      annotationName: String
  )(using Context): untpd.TypeDef =
    given dotty.tools.dotc.util.SourceFile = input.annotatedClass.source
    val mods = Trees.mods(input.annotatedClass)
    val preserved =
      mods.annotations.filterNot(annotation => input.currentAnnotation.exists(_ eq annotation))
    val introduced =
      input.currentAnnotation.toList.map(
        renamedParameterlessAnnotation(_, annotationName)
      )
    input.annotatedClass
      .withMods(mods.withAnnotations(introduced ++ preserved))
      .asInstanceOf[untpd.TypeDef]

final class CompositionDropsLaterExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "compositionDropsLater"
  override val compositionPolicy: ExpansionCompositionPolicy =
    ExpansionCompositionPolicy.SourceOrdered

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.Expanded(List(CompositionContractTrees.withoutAllAnnotations(input)))

final class CompositionRetainsCurrentExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "compositionRetainsCurrent"
  override val compositionPolicy: ExpansionCompositionPolicy =
    ExpansionCompositionPolicy.SourceOrdered

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.Expanded(List(input.annotatedClass))

final class CompositionReconstructsLaterExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "compositionReconstructsLater"
  override val compositionPolicy: ExpansionCompositionPolicy =
    ExpansionCompositionPolicy.SourceOrdered

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.Expanded(
      List(CompositionContractTrees.withoutCurrentAndWithRebuiltLater(input))
    )

final class CompositionReconstructsCurrentExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "compositionReconstructsCurrent"
  override val compositionPolicy: ExpansionCompositionPolicy =
    ExpansionCompositionPolicy.SourceOrdered

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.Expanded(
      List(CompositionContractTrees.withoutCurrentAndWithRebuiltCurrent(input))
    )

final class CompositionDuplicatesLaterExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "compositionDuplicatesLater"
  override val compositionPolicy: ExpansionCompositionPolicy =
    ExpansionCompositionPolicy.SourceOrdered

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.Expanded(
      List(CompositionContractTrees.withoutCurrentAndWithDuplicateLater(input))
    )

final class CompositionReintroducesConsumedExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "compositionReintroducesConsumed"
  override val compositionPolicy: ExpansionCompositionPolicy =
    ExpansionCompositionPolicy.SourceOrdered

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.Expanded(
      List(CompositionContractTrees.withoutCurrentAndWithFreshHandled(input, "gen"))
    )

final class CompositionIntroducesDifferentHandledExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "compositionIntroducesDifferentHandled"
  override val compositionPolicy: ExpansionCompositionPolicy =
    ExpansionCompositionPolicy.SourceOrdered

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.Expanded(
      List(CompositionContractTrees.withoutCurrentAndWithFreshHandled(input, "debug"))
    )

final class CompositionDuplicatesKnownAdditionalExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "compositionDuplicatesKnownAdditional"
  override val compositionPolicy: ExpansionCompositionPolicy =
    ExpansionCompositionPolicy.SourceOrdered

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.Expanded(
      List(
        CompositionContractTrees.withoutCurrent(input),
        CompositionContractTrees.externalSiblingDuplicate(input)
      )
    )

final class CompositionFailsAfterCompanionAndSiblingExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "compositionFailsAfterCompanionAndSibling"
  override val compositionPolicy: ExpansionCompositionPolicy =
    ExpansionCompositionPolicy.SourceOrdered

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    throw new IllegalStateException(
      "late companion and sibling composition fixture failure"
    )

final class CompositionRestrictedProfileExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "compositionRestrictedProfile"
  override val compositionPolicy: ExpansionCompositionPolicy =
    ExpansionCompositionPolicy.SourceOrdered
  override val targetProfile: ExpansionTargetProfile =
    ExpansionTargetProfile.RestrictedGenericTraitApply

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.NotApplicable
