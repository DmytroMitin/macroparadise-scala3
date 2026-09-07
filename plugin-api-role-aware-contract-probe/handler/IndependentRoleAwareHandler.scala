package roleawareprobe

import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Names.{termName, typeName}
import dotty.tools.dotc.util.SourceFile
import paradise3.api.*
import paradise3.api.helpers.*

private object AuthoredMembers:
  def batch(result: String, source: SourceFile)(using Context): List[untpd.MemberDef] =
    given SourceFile = source
    List(
      ExpansionHelpers.stringReturningMethod("foo", result, source),
      untpd.ValDef(termName("answer"), untpd.Ident(typeName("Int")), untpd.Literal(Constant(42)))
    )

final class IndependentRoleAwareHandler extends RoleAwareParadiseAnnotationExpander:
  val annotationName = "roleawareprobe.IndependentRoleAwareMarker"
  override val oppositeCapability = RoleAwareOppositeCapability.LeaseOrCreateClassOrTrait

  def expand(input: RoleAwareExpansionInput)(using Context): RoleAwareExpansionOutcome =
    val primary = input.primary.asInstanceOf[ExpansionPrimaryRole.Object].tree
    val name = primary.name.toString
    val program = for
      e0 <- RoleAwareExpansionEdit.start(input)
      e1 <- ExpansionHelpers.placeMembersInPrimary(e0, AuthoredMembers.batch("ok", primary.source))
      e2 <- name match
        case "ObjectEdit" => Right(e1)
        case _ =>
          val (result, policy) = name match
            case "ExistingClass" => ("class-replaced", RoleAwareMissingOppositePolicy.Reject)
            case "ExistingTrait" => ("trait-replaced", RoleAwareMissingOppositePolicy.Reject)
            case "CreateClass" => ("class-created", RoleAwareMissingOppositePolicy.CreateClass(OppositePlacement.BeforePrimary))
            case "CreateTrait" => ("trait-created", RoleAwareMissingOppositePolicy.CreateTrait(OppositePlacement.AfterPrimary))
          val batch = AuthoredMembers.batch(result, primary.source)
          // Two transitions also prove a created opposite keeps its creation intent.
          ExpansionHelpers.placeMembersInOpposite(e1, batch.take(1), policy)
            .flatMap(e => ExpansionHelpers.placeMembersInOpposite(e, batch.drop(1), RoleAwareMissingOppositePolicy.Reject))
    yield e2
    RoleAwareExpansionEdit.finish(program)

abstract class IndependentLegacyCompositeHandler extends ParadiseAnnotationExpander:
  override val consumesExistingCompanion = true
  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    val program = for
      e0 <- LegacyExpansionEdit.start(input)
      e1 <- ExpansionHelpers.addStringMethodToClass(e0, "a", "A")
      e2 <- ExpansionHelpers.placeMembersInPrimary(e1, AuthoredMembers.batch("primary", input.annotatedClass.source))
      e3 <- ExpansionHelpers.placeMembersInCompanion(e2, AuthoredMembers.batch("companion", input.annotatedClass.source))
    yield e3
    LegacyExpansionEdit.finish(input, program)

final class IndependentLegacyClassHandler extends IndependentLegacyCompositeHandler:
  val annotationName = "roleawareprobe.LegacyClassMarker"

final class IndependentLegacyTraitHandler extends IndependentLegacyCompositeHandler:
  val annotationName = "roleawareprobe.LegacyTraitMarker"
  override val targetProfile = ExpansionTargetProfile.PlainZeroParameterTrait

/** Regression for the existing complete versus intermediate omission contract. */
final class IndependentOmittingHandler extends ParadiseAnnotationExpander:
  val annotationName = "roleawareprobe.OmitMarker"
  override val consumesExistingCompanion = true
  override val compositionPolicy = ExpansionCompositionPolicy.SourceOrdered
  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionHelpers.addStringMethodToClass(input, "omissionProof", "ok")

final class IndependentRetainingHandler extends ParadiseAnnotationExpander:
  val annotationName = "roleawareprobe.RetainMarker"
  override val consumesExistingCompanion = true
  override val compositionPolicy = ExpansionCompositionPolicy.SourceOrdered
  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    LegacyExpansionEdit.finish(input, LegacyExpansionEdit.start(input))
