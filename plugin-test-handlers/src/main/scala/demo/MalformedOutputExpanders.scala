package demo

import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Names.*
import paradise3.api.{ExpansionCompositionPolicy, ExpansionInput, ExpansionOutcome, ParadiseAnnotationExpander}

private object MalformedOutputTrees:
  def renamedPrimary(input: ExpansionInput, name: String)(using Context): untpd.TypeDef =
    untpd.cpy.TypeDef(input.annotatedClass)(
      typeName(name),
      input.annotatedClass.rhs
    )

final class MalformedEmptyOutputExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "malformedEmptyOutput"

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.Expanded(Nil)

final class MalformedMissingPrimaryExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "malformedMissingPrimary"
  override val compositionPolicy: ExpansionCompositionPolicy =
    ExpansionCompositionPolicy.SourceOrdered

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.Expanded(
      List(MalformedOutputTrees.renamedPrimary(input, "WrongPrimary"))
    )

final class MalformedDuplicatePrimaryExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "malformedDuplicatePrimary"

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.Expanded(
      List(input.annotatedClass, input.annotatedClass)
    )

final class MalformedConflictingAdditionalExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "malformedConflictingAdditional"

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.Expanded(
      List(
        input.annotatedClass,
        MalformedOutputTrees.renamedPrimary(input, "KnownConflict")
      )
    )

final class MalformedLateCompanionExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "malformedLateCompanion"
  override val consumesExistingCompanion: Boolean = true

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    input.existingCompanion match
      case Some(companion) =>
        ExpansionOutcome.Expanded(
          List(
            input.annotatedClass,
            MalformedOutputTrees.renamedPrimary(input, "LateAdditional"),
            companion
          )
        )
      case None =>
        ExpansionOutcome.NotApplicable
