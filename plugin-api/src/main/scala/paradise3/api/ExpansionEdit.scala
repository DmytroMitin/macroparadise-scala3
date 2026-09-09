package paradise3.api

import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.core.Contexts.Context

/** Immutable proposal for one admitted expansion invocation. */
final class ExpansionEdit private[api] (
    private[api] val original: ExpansionInput,
    val primary: ExpansionTarget,
    val companion: Option[ExpansionTarget],
    val changes: ExpansionChanges
):
  private[api] def updated(
      nextPrimary: ExpansionTarget = primary,
      nextCompanion: Option[ExpansionTarget] = companion,
      nextChanges: ExpansionChanges = changes
  ): ExpansionEdit =
    new ExpansionEdit(original, nextPrimary, nextCompanion, nextChanges)

object ExpansionEdit:
  def start(input: ExpansionInput)(using Context): Either[ExpansionDiagnostic, ExpansionEdit] =
    def position =
      Option(input)
        .flatMap(value => Option(value.currentAnnotation))
        .fold(untpd.EmptyTree.sourcePos)(_.sourcePos)

    if input == null then
      Left(ExpansionDiagnostic("expansion edit requires a non-null input", position))
    else if input.primary == null || input.primary.tree == null then
      Left(ExpansionDiagnostic("expansion edit requires a non-null primary target", position))
    else if input.companion == null || input.container == null ||
        input.container.occupiedDefinitionNames == null || input.currentAnnotation == null then
      Left(ExpansionDiagnostic("expansion edit requires a complete non-null invocation context", position))
    else
      Right(
        new ExpansionEdit(
          input,
          input.primary,
          input.companion,
          ExpansionChanges()
        )
      )

  def finish(
      edited: Either[ExpansionDiagnostic, ExpansionEdit]
  ): ExpansionOutcome =
    edited match
      case Left(diagnostic) => ExpansionOutcome.Rejected(List(diagnostic))
      case Right(edit)      => ExpansionOutcome.Structured(edit.changes)
