package paradise3.api

import dotty.tools.dotc.ast.{Trees, untpd}
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags.{Enum, Trait}

/** Immutable proposal inside one legacy invocation. Raw trees remain expert values:
  * callers must not mutate them. Only start can establish invocation context.
  */
final class LegacyExpansionEdit private[api] (
    private[api] val original: ExpansionInput,
    val primary: untpd.TypeDef,
    val companion: Option[untpd.ModuleDef]
)

object LegacyExpansionEdit:
  def start(input: ExpansionInput)(using Context): Either[ExpansionDiagnostic, LegacyExpansionEdit] =
    val primary = Option(input).map(_.annotatedClass).orNull
    def reject(message: String) = Left(ExpansionDiagnostic(message,
      if primary == null then untpd.EmptyTree.sourcePos else primary.sourcePos))
    if input == null || primary == null then reject("legacy edit requires a non-null input and mandatory primary")
    else if !primary.rhs.isInstanceOf[untpd.Template] || Trees.mods(primary).is(Enum) then
      reject("legacy edit requires a class or trait Template primary")
    else if input.existingCompanion == null || input.topLevelNames == null || input.currentAnnotation == null || input.currentAnnotation.exists(_ == null) then
      reject("legacy edit requires non-null invocation context containers and annotation values")
    else if input.existingCompanion.exists(c => c == null || c.name.toString != primary.name.toString || c.impl == null) then
      reject("legacy edit requires an optional same-name object companion with a Template")
    else Right(new LegacyExpansionEdit(input, primary, input.existingCompanion))

  /** Finalize once at the handler boundary; helpers never consume annotations. */
  def finish(edit: LegacyExpansionEdit)(using Context): ExpansionOutcome =
    val mods = Trees.mods(edit.primary)
    val annotations = edit.original.currentAnnotation match
      case Some(current) => mods.annotations.filterNot(_ eq current)
      case None => Nil // Historical direct-input annotation cleanup fallback.
    val primary = edit.primary.withMods(mods.withAnnotations(annotations)).asInstanceOf[untpd.TypeDef]
    ExpansionOutcome.Structured(StructuredExpansionOutput(primary, edit.companion, Nil))

  /** Original input supplies the mandatory fallback when a program short-circuits. */
  def finish(
      input: ExpansionInput,
      edited: Either[ExpansionDiagnostic, LegacyExpansionEdit]
  )(using Context): ExpansionOutcome =
    require(input != null && input.annotatedClass != null, "legacy finalization requires the original primary fallback")
    edited match
      case Left(diagnostic) => ExpansionOutcome.Rejected(List(diagnostic), input.annotatedClass)
      case Right(edit) =>
        require(edit.original eq input, "legacy finalization requires the same original invocation input")
        finish(edit)

/** Immutable object-primary proposal with explicit opposite provenance. */
final class RoleAwareExpansionEdit private[api] (
    private[api] val original: RoleAwareExpansionInput,
    val primary: ExpansionPrimaryRole,
    val opposite: Option[ExpansionOppositeRole],
    private[api] val change: OppositeChange
)

object RoleAwareExpansionEdit:
  def start(input: RoleAwareExpansionInput)(using Context): Either[ExpansionDiagnostic, RoleAwareExpansionEdit] =
    def reject(message: String) = Left(ExpansionDiagnostic(message,
      Option(input).flatMap(i => Option(i.currentAnnotation)).getOrElse(untpd.EmptyTree).sourcePos))
    if input == null then reject("role-aware edit requires a non-null input")
    else if input.leasedOpposite == null || input.topLevelNames == null || input.currentAnnotation == null || input.admission != RoleAwareTargetAdmission.OrdinaryTopLevelObject then
      reject("role-aware edit requires ordinary-object admission and non-null invocation context")
    else input.primary match
      case ExpansionPrimaryRole.Object(primary) if primary != null && primary.impl != null =>
        val validOpposite = input.leasedOpposite.forall:
          case ExpansionOppositeRole.Class(tree) =>
            tree != null && tree.name.toString == primary.name.toString && tree.rhs.isInstanceOf[untpd.Template] && !Trees.mods(tree).is(Trait) && !Trees.mods(tree).is(Enum)
          case ExpansionOppositeRole.Trait(tree) =>
            tree != null && tree.name.toString == primary.name.toString && tree.rhs.isInstanceOf[untpd.Template] && Trees.mods(tree).is(Trait) && !Trees.mods(tree).is(Enum)
          case _ => false
        if validOpposite then Right(new RoleAwareExpansionEdit(input, input.primary, input.leasedOpposite, OppositeChange.Preserve))
        else reject("role-aware edit requires an optional same-name same-kind class or trait opposite")
      case _ => reject("role-aware edit requires a mandatory object primary with a Template")

  /** Plugin validation consumes the exact current annotation and commits once. */
  def finish(edit: RoleAwareExpansionEdit): RoleAwareExpansionOutcome =
    RoleAwareExpansionOutcome.Expanded(RoleAwareExpansionOutput(edit.primary, edit.change))

  def finish(edited: Either[ExpansionDiagnostic, RoleAwareExpansionEdit]): RoleAwareExpansionOutcome =
    edited match
      case Left(diagnostic) => RoleAwareExpansionOutcome.Rejected(List(diagnostic))
      case Right(edit) => finish(edit)
