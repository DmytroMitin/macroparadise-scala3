package paradise3.api.helpers

import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.*

/** Edit-first adapters for composing [[ExpansionHelpers]] operations with Either.flatMap. */
object ExpansionTransforms:
  def placeMemberInPrimary(
      member: untpd.Tree,
      onConflict: MemberConflictPolicy = MemberConflictPolicy.Reject
  )(using Context): ExpansionEdit => Either[ExpansionDiagnostic, ExpansionEdit] =
    edit => ExpansionHelpers.placeMemberInPrimary(edit, member, onConflict)

  def placeMembersInPrimary(
      members: List[untpd.Tree],
      onConflict: MemberConflictPolicy = MemberConflictPolicy.Reject
  )(using Context): ExpansionEdit => Either[ExpansionDiagnostic, ExpansionEdit] =
    edit => ExpansionHelpers.placeMembersInPrimary(edit, members, onConflict)

  def placeMemberInCompanion(
      member: untpd.Tree,
      ifMissing: MissingCompanionPolicy = MissingCompanionPolicy.Reject,
      onConflict: MemberConflictPolicy = MemberConflictPolicy.Reject
  )(using Context): ExpansionEdit => Either[ExpansionDiagnostic, ExpansionEdit] =
    edit => ExpansionHelpers.placeMemberInCompanion(edit, member, ifMissing, onConflict)

  def placeMembersInCompanion(
      members: List[untpd.Tree],
      ifMissing: MissingCompanionPolicy = MissingCompanionPolicy.Reject,
      onConflict: MemberConflictPolicy = MemberConflictPolicy.Reject
  )(using Context): ExpansionEdit => Either[ExpansionDiagnostic, ExpansionEdit] =
    edit => ExpansionHelpers.placeMembersInCompanion(edit, members, ifMissing, onConflict)

  def replacePrimaryAnnotations(
      annotations: List[untpd.Tree]
  )(using Context): ExpansionEdit => Either[ExpansionDiagnostic, ExpansionEdit] =
    edit => ExpansionHelpers.replacePrimaryAnnotations(edit, annotations)

  def replaceCompanionAnnotations(
      annotations: List[untpd.Tree]
  )(using Context): ExpansionEdit => Either[ExpansionDiagnostic, ExpansionEdit] =
    edit => ExpansionHelpers.replaceCompanionAnnotations(edit, annotations)

  def createSibling(
      target: ExpansionTarget,
      placement: DefinitionPlacement
  )(using Context): ExpansionEdit => Either[ExpansionDiagnostic, ExpansionEdit] =
    edit => ExpansionHelpers.createSibling(edit, target, placement)

  def prepareTraitSelf(
      self: untpd.ValDef,
      generatedMembers: List[untpd.Tree]
  )(using Context): ExpansionEdit => Either[ExpansionDiagnostic, ExpansionEdit] =
    edit => ExpansionHelpers.prepareTraitSelf(edit, self, generatedMembers)
