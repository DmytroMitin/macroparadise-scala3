package paradise3.api.helpers

import dotty.tools.dotc.ast.Trees
import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags.{Trait as TraitFlag}
import paradise3.api.*

/** Pure helpers for composing one immutable [[ExpansionEdit]]. */
object ExpansionHelpers:
  def placeMemberInPrimary(
      edit: ExpansionEdit,
      member: untpd.Tree,
      onConflict: MemberConflictPolicy = MemberConflictPolicy.Reject
  )(using Context): Either[ExpansionDiagnostic, ExpansionEdit] =
    placeMembersInPrimary(edit, List(member), onConflict)

  def placeMembersInPrimary(
      edit: ExpansionEdit,
      members: List[untpd.Tree],
      onConflict: MemberConflictPolicy = MemberConflictPolicy.Reject
  )(using Context): Either[ExpansionDiagnostic, ExpansionEdit] =
    for
      values <- admittedMembers(edit, members)
      selected <- selectMembers(edit, edit.primary, values, onConflict)
      updated <- appendMembers(edit, edit.primary, selected)
      result <- updatePrimary(edit, updated, selected)
    yield result

  def placeMemberInCompanion(
      edit: ExpansionEdit,
      member: untpd.Tree,
      ifMissing: MissingCompanionPolicy = MissingCompanionPolicy.Reject,
      onConflict: MemberConflictPolicy = MemberConflictPolicy.Reject
  )(using Context): Either[ExpansionDiagnostic, ExpansionEdit] =
    placeMembersInCompanion(edit, List(member), ifMissing, onConflict)

  def placeMembersInCompanion(
      edit: ExpansionEdit,
      members: List[untpd.Tree],
      ifMissing: MissingCompanionPolicy = MissingCompanionPolicy.Reject,
      onConflict: MemberConflictPolicy = MemberConflictPolicy.Reject
  )(using Context): Either[ExpansionDiagnostic, ExpansionEdit] =
    for
      values <- admittedMembers(edit, members)
      result <- edit.companion match
        case Some(current) =>
          for
            selected <- selectMembers(edit, current, values, onConflict)
            updated <- appendMembers(edit, current, selected)
          yield updateExistingCompanion(edit, updated, selected)
        case None =>
          ifMissing match
            case MissingCompanionPolicy.Reject =>
              Left(diagnostic(edit, s"companion for `${edit.primary.name}` is missing"))
            case MissingCompanionPolicy.Create(kind, placement) =>
              for
                empty <- emptyCompanion(edit, kind)
                updated <- appendMembers(edit, empty, values)
              yield edit.updated(
                nextCompanion = Some(updated),
                nextChanges = edit.changes.copy(
                  companion = CompanionChange.Create(updated, placement)
                )
              )
    yield result

  def replacePrimaryAnnotations(
      edit: ExpansionEdit,
      annotations: List[untpd.Tree]
  )(using Context): Either[ExpansionDiagnostic, ExpansionEdit] =
    for
      values <- admittedAnnotations(edit, annotations)
      updated <- replaceAnnotations(edit, edit.primary, values)
      result <- updatePrimaryPatch(
        edit,
        updated,
        TargetPatch.ReplaceAnnotations(values)
      )
    yield result

  def replaceCompanionAnnotations(
      edit: ExpansionEdit,
      annotations: List[untpd.Tree]
  )(using Context): Either[ExpansionDiagnostic, ExpansionEdit] =
    edit.companion match
      case None => Left(diagnostic(edit, s"companion for `${edit.primary.name}` is missing"))
      case Some(current) =>
        for
          values <- admittedAnnotations(edit, annotations)
          updated <- replaceAnnotations(edit, current, values)
        yield updateExistingCompanionPatch(
          edit,
          updated,
          TargetPatch.ReplaceAnnotations(values)
        )

  def createSibling(
      edit: ExpansionEdit,
      target: ExpansionTarget,
      placement: DefinitionPlacement
  )(using Context): Either[ExpansionDiagnostic, ExpansionEdit] =
    if edit == null || target == null || target.tree == null || placement == null then
      Left(diagnostic(edit, "sibling creation requires a non-null edit, target, and placement"))
    else if edit.original.container.occupiedDefinitionNames.contains(target.name) ||
        edit.changes.siblings.exists:
          case SiblingChange.Create(value, _) => value.name == target.name
          case _                              => false
    then
      Left(diagnostic(edit, s"sibling `${target.name}` already exists in the current container"))
    else
      Right(
        edit.updated(
          nextChanges = edit.changes.copy(
            siblings = edit.changes.siblings :+ SiblingChange.Create(target, placement)
          )
        )
      )

  def prepareTraitSelf(
      edit: ExpansionEdit,
      self: untpd.ValDef,
      generatedMembers: List[untpd.Tree]
  )(using Context): Either[ExpansionDiagnostic, ExpansionEdit] =
    edit.primary match
      case ExpansionTarget.Trait(tree) =>
        for
          members <- admittedMembers(edit, generatedMembers)
          _ <-
            if self == null then Left(diagnostic(edit, "trait self preparation requires a non-null self"))
            else Right(())
          template <- targetTemplate(edit, edit.primary)
          _ <-
            if template.body.exists:
                case value: TypeDef => value.name.toString == "Self"
                case _              => false
            then Left(diagnostic(edit, s"trait `${edit.primary.name}` already contains direct type member `Self`"))
            else Right(())
          rewrittenTemplate = cpy.Template(template)(
            template.constr,
            template.parentsOrDerived,
            template.derived,
            self,
            template.body ++ members
          )
          rewritten = ExpansionTarget.Trait(
            cpy.TypeDef(tree)(tree.name, rewrittenTemplate)
          )
          patches = List(
            TargetPatch.SetSelf(Some(self)),
            TargetPatch.AppendMembers(members)
          )
          result <- updatePrimaryPatches(edit, rewritten, patches)
        yield result
      case _ =>
        Left(diagnostic(edit, "trait self preparation requires a Trait primary"))

  private def admittedMembers(
      edit: ExpansionEdit,
      members: List[untpd.Tree]
  )(using Context): Either[ExpansionDiagnostic, List[untpd.Tree]] =
    if members == null then Left(diagnostic(edit, "member batch must be a non-null List"))
    else if members.isEmpty then Left(diagnostic(edit, "member batch must be nonempty"))
    else
      members.zipWithIndex.collectFirst:
        case (null, index) =>
          diagnostic(edit, s"member batch entry $index is null")
        case (value, index) if !value.isInstanceOf[MemberDef] =>
          diagnostic(edit, s"member batch entry $index has unsupported raw kind `${value.getClass.getName}`")
        case (value, index) if !value.source.exists && !value.span.exists =>
          diagnostic(edit, s"member batch entry $index has neither source nor span provenance")
      match
        case Some(value) => Left(value)
        case None        => Right(members)

  private def admittedAnnotations(
      edit: ExpansionEdit,
      annotations: List[untpd.Tree]
  )(using Context): Either[ExpansionDiagnostic, List[untpd.Tree]] =
    if annotations == null then Left(diagnostic(edit, "annotation list must be non-null"))
    else if annotations.exists(_ == null) then
      Left(diagnostic(edit, "annotation list contains a null tree"))
    else Right(annotations)

  private def selectMembers(
      edit: ExpansionEdit,
      target: ExpansionTarget,
      members: List[untpd.Tree],
      policy: MemberConflictPolicy
  )(using Context): Either[ExpansionDiagnostic, List[untpd.Tree]] =
    if policy == null then Left(diagnostic(edit, "member conflict policy is null"))
    else
      targetTemplate(edit, target).flatMap: template =>
        val existingNames = template.body.collect { case value: MemberDef => value.name }.toSet
        policy match
          case MemberConflictPolicy.Reject =>
            val seen = scala.collection.mutable.Set.from(existingNames)
            val conflict = members.collectFirst:
              case value: MemberDef if seen(value.name) => value.name.toString
              case value: MemberDef =>
                seen += value.name
                null
            conflict.filter(_ != null) match
              case Some(name) if name.nonEmpty =>
                Left(
                  diagnostic(
                    edit,
                    s"generated member `$name` conflicts with a direct member of `${target.name}`"
                  )
                )
              case _ => Right(members)
          case MemberConflictPolicy.PreserveExisting =>
            val seen = scala.collection.mutable.Set.from(existingNames)
            Right(
              members.filter:
                case value: MemberDef if !seen(value.name) =>
                  seen += value.name
                  true
                case _ => false
            )

  private def appendMembers(
      edit: ExpansionEdit,
      target: ExpansionTarget,
      members: List[untpd.Tree]
  )(using Context): Either[ExpansionDiagnostic, ExpansionTarget] =
    if members.isEmpty then Right(target)
    else
      targetTemplate(edit, target).map: template =>
        val rewritten = cpy.Template(template)(
          template.constr,
          template.parentsOrDerived,
          template.derived,
          template.self,
          template.body ++ members
        )
        target match
          case ExpansionTarget.Class(tree) =>
            ExpansionTarget.Class(cpy.TypeDef(tree)(tree.name, rewritten))
          case ExpansionTarget.Trait(tree) =>
            ExpansionTarget.Trait(cpy.TypeDef(tree)(tree.name, rewritten))
          case ExpansionTarget.Object(tree) =>
            ExpansionTarget.Object(cpy.ModuleDef(tree)(tree.name, rewritten))

  private def replaceAnnotations(
      edit: ExpansionEdit,
      target: ExpansionTarget,
      annotations: List[untpd.Tree]
  )(using Context): Either[ExpansionDiagnostic, ExpansionTarget] =
    target match
      case ExpansionTarget.Class(tree) =>
        Right(
          ExpansionTarget.Class(
            tree.withMods(Trees.mods(tree).withAnnotations(annotations))
              .asInstanceOf[TypeDef]
          )
        )
      case ExpansionTarget.Trait(tree) =>
        Right(
          ExpansionTarget.Trait(
            tree.withMods(Trees.mods(tree).withAnnotations(annotations))
              .asInstanceOf[TypeDef]
          )
        )
      case ExpansionTarget.Object(tree) =>
        Right(
          ExpansionTarget.Object(
            tree.withMods(Trees.mods(tree).withAnnotations(annotations))
              .asInstanceOf[ModuleDef]
          )
        )

  private def updatePrimary(
      edit: ExpansionEdit,
      updated: ExpansionTarget,
      members: List[untpd.Tree]
  )(using Context): Either[ExpansionDiagnostic, ExpansionEdit] =
    if members.isEmpty then Right(edit)
    else updatePrimaryPatch(edit, updated, TargetPatch.AppendMembers(members))

  private def updatePrimaryPatch(
      edit: ExpansionEdit,
      updated: ExpansionTarget,
      patch: TargetPatch
  )(using Context): Either[ExpansionDiagnostic, ExpansionEdit] =
    updatePrimaryPatches(edit, updated, List(patch))

  private def updatePrimaryPatches(
      edit: ExpansionEdit,
      updated: ExpansionTarget,
      patches: List[TargetPatch]
  )(using Context): Either[ExpansionDiagnostic, ExpansionEdit] =
    edit.changes.primary match
      case PrimaryChange.Delete =>
        Left(diagnostic(edit, "cannot merge a primary after requesting its deletion"))
      case PrimaryChange.Replace(_) =>
        Right(
          edit.updated(
            nextPrimary = updated,
            nextChanges = edit.changes.copy(primary = PrimaryChange.Replace(updated))
          )
        )
      case PrimaryChange.Merge(existing) =>
        Right(
          edit.updated(
            nextPrimary = updated,
            nextChanges = edit.changes.copy(primary = PrimaryChange.Merge(existing ++ patches))
          )
        )
      case PrimaryChange.Preserve =>
        Right(
          edit.updated(
            nextPrimary = updated,
            nextChanges = edit.changes.copy(primary = PrimaryChange.Merge(patches))
          )
        )

  private def updateExistingCompanion(
      edit: ExpansionEdit,
      updated: ExpansionTarget,
      members: List[untpd.Tree]
  ): ExpansionEdit =
    if members.isEmpty then edit
    else updateExistingCompanionPatch(
      edit,
      updated,
      TargetPatch.AppendMembers(members)
    )

  private def updateExistingCompanionPatch(
      edit: ExpansionEdit,
      updated: ExpansionTarget,
      patch: TargetPatch
  ): ExpansionEdit =
    val change = edit.changes.companion match
      case CompanionChange.Create(_, placement) =>
        CompanionChange.Create(updated, placement)
      case CompanionChange.Replace(_) => CompanionChange.Replace(updated)
      case CompanionChange.Merge(existing) => CompanionChange.Merge(existing :+ patch)
      case CompanionChange.Preserve => CompanionChange.Merge(List(patch))
      case CompanionChange.Delete =>
        throw new IllegalStateException("validated helper attempted to merge a deleted companion")
    edit.updated(
      nextCompanion = Some(updated),
      nextChanges = edit.changes.copy(companion = change)
    )

  private def emptyCompanion(
      edit: ExpansionEdit,
      kind: ExpansionTargetKind
  )(using Context): Either[ExpansionDiagnostic, ExpansionTarget] =
    val allowed = (edit.primary.kind, kind) match
      case (ExpansionTargetKind.Class | ExpansionTargetKind.Trait, ExpansionTargetKind.Object) => true
      case (ExpansionTargetKind.Object, ExpansionTargetKind.Class | ExpansionTargetKind.Trait) => true
      case _ => false
    if !allowed then
      Left(
        diagnostic(
          edit,
          s"target kind $kind cannot be a companion of ${edit.primary.kind} `${edit.primary.name}`"
        )
      )
    else
      given dotty.tools.dotc.util.SourceFile = edit.primary.tree.source
      val template = Template(emptyConstructor, Nil, Nil, EmptyValDef, Nil)
      val name = dotty.tools.dotc.core.Names.typeName(edit.primary.name)
      kind match
        case ExpansionTargetKind.Object =>
          Right(ExpansionTarget.Object(ModuleDef(name.toTermName, template)))
        case ExpansionTargetKind.Class =>
          Right(ExpansionTarget.Class(TypeDef(name, template)))
        case ExpansionTargetKind.Trait =>
          val value = TypeDef(name, template)
            .withMods(Modifiers(TraitFlag))
            .asInstanceOf[TypeDef]
          Right(ExpansionTarget.Trait(value))

  private def targetTemplate(
      edit: ExpansionEdit,
      target: ExpansionTarget
  )(using Context): Either[ExpansionDiagnostic, Template] =
    target match
      case ExpansionTarget.Class(tree) =>
        tree.rhs match
          case value: Template => Right(value)
          case _ => Left(diagnostic(edit, s"Class `${target.name}` has no Template"))
      case ExpansionTarget.Trait(tree) =>
        tree.rhs match
          case value: Template => Right(value)
          case _ => Left(diagnostic(edit, s"Trait `${target.name}` has no Template"))
      case ExpansionTarget.Object(tree) =>
        Option(tree.impl).toRight(diagnostic(edit, s"Object `${target.name}` has no Template"))

  private def diagnostic(
      edit: ExpansionEdit | Null,
      message: String
  )(using Context): ExpansionDiagnostic =
    val pos =
      Option(edit)
        .map(_.original.currentAnnotation)
        .flatMap(Option(_))
        .fold(untpd.EmptyTree.sourcePos)(_.sourcePos)
    ExpansionDiagnostic(message, pos)
