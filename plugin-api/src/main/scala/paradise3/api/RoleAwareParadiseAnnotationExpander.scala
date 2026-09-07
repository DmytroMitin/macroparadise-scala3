package paradise3.api

import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.core.Contexts.Context

/** Syntactic definition kind, kept separate from the admitted shape profile. */
enum ExpansionTargetKind:
  case Class, Trait, Object

/** Closed plugin-owned role-aware admission profiles. */
enum RoleAwareShapeProfile:
  case OrdinaryTopLevelObject

/** A closed valid pairing of target kind and shape profile. */
enum RoleAwareTargetAdmission(
    val targetKind: ExpansionTargetKind,
    val shapeProfile: RoleAwareShapeProfile
):
  case OrdinaryTopLevelObject
      extends RoleAwareTargetAdmission(
        ExpansionTargetKind.Object,
        RoleAwareShapeProfile.OrdinaryTopLevelObject
      )

/** Primary definition role carried by the exact-version raw-tree contract. */
enum ExpansionPrimaryRole:
  case Class(tree: untpd.TypeDef)
  case Trait(tree: untpd.TypeDef)
  case Object(tree: untpd.ModuleDef)

/** Same-name opposite definition role available to a role-aware handler. */
enum ExpansionOppositeRole:
  case Object(tree: untpd.ModuleDef)
  case Class(tree: untpd.TypeDef)
  case Trait(tree: untpd.TypeDef)

/** Maximum opposite lifecycle authority requested by a handler. */
enum RoleAwareOppositeCapability:
  case PrimaryOnly
  case LeaseExisting
  case LeaseOrCreateClass
  case LeaseOrCreateTrait
  case LeaseOrCreateClassOrTrait

/** Input for one admitted role-aware handler invocation.
  *
  * Transaction identity, package indices, snapshots, and commit/rollback
  * authority remain private to the plugin.
  */
final case class RoleAwareExpansionInput(
    annotationName: String,
    primary: ExpansionPrimaryRole,
    leasedOpposite: Option[ExpansionOppositeRole],
    topLevelNames: Set[String],
    currentAnnotation: untpd.Tree,
    admission: RoleAwareTargetAdmission
)

/** Plugin-validated role-aware expansion result. */
enum RoleAwareExpansionOutcome:
  case Expanded(output: RoleAwareExpansionOutput)
  case Rejected(diagnostics: List[ExpansionDiagnostic])

/** Successful primary and opposite intent; no arbitrary package-stat lane exists. */
final case class RoleAwareExpansionOutput(
    primary: ExpansionPrimaryRole,
    opposite: OppositeChange
)

/** Explicit lifecycle request for the same-name opposite definition. */
enum OppositeChange:
  case Preserve
  case Replace(value: ExpansionOppositeRole)
  case Create(value: ExpansionOppositeRole, placement: OppositePlacement)

/** Exact requested position for a newly created opposite. */
enum OppositePlacement:
  case BeforePrimary, AfterPrimary

/** Experimental exact-version handler contract for role-aware definition pairs.
  *
  * The first routed slice admits one ordinary top-level object participant.
  * All source discovery, validation, annotation consumption, and transactional
  * commit/rollback authority remain plugin-owned.
  */
trait RoleAwareParadiseAnnotationExpander:
  def annotationName: String

  def targetAdmissions: List[RoleAwareTargetAdmission] =
    List(RoleAwareTargetAdmission.OrdinaryTopLevelObject)

  def compositionPolicy: ExpansionCompositionPolicy =
    ExpansionCompositionPolicy.StandaloneOnly

  def oppositeCapability: RoleAwareOppositeCapability =
    RoleAwareOppositeCapability.PrimaryOnly

  def expand(
      input: RoleAwareExpansionInput
  )(using Context): RoleAwareExpansionOutcome
