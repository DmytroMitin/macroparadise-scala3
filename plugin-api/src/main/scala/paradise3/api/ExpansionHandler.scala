package paradise3.api

import dotty.tools.dotc.ast.{Trees, untpd}
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags.{Enum, Trait as TraitFlag}
import dotty.tools.dotc.util.SrcPos

/** One precompiled handler protocol for every currently supported target kind. */
trait ExpansionHandler:
  def annotationName: String
  def admissions: List[ExpansionAdmission]
  def expand(input: ExpansionInput)(using Context): ExpansionOutcome

/** Syntactic target kind, independent of its relationship to the invocation. */
enum ExpansionTargetKind:
  case Class, Trait, Object

/** Exact-version raw target supplied to and returned by handlers. */
enum ExpansionTarget:
  case Class(override val tree: untpd.TypeDef)
  case Trait(override val tree: untpd.TypeDef)
  case Object(override val tree: untpd.ModuleDef)

  def kind: ExpansionTargetKind = this match
    case Class(_)  => ExpansionTargetKind.Class
    case Trait(_)  => ExpansionTargetKind.Trait
    case Object(_) => ExpansionTargetKind.Object

  def tree: untpd.Tree

  def name: String = this match
    case Class(value)  => value.name.toString
    case Trait(value)  => value.name.toString
    case Object(value) => value.name.toString

object ExpansionTarget:
  def fromTree(tree: untpd.Tree)(using Context): Either[ExpansionDiagnostic, ExpansionTarget] =
    tree match
      case value: untpd.ModuleDef if value.impl != null => Right(Object(value))
      case value: untpd.TypeDef
          if value.isClassDef && value.rhs.isInstanceOf[untpd.Template] && !Trees.mods(value).is(Enum) =>
        if Trees.mods(value).is(TraitFlag) then Right(Trait(value))
        else Right(Class(value))
      case value =>
        Left(
          ExpansionDiagnostic(
            s"unsupported expansion target `${Option(value).fold("null")(_.getClass.getName)}`; expected an ordinary Class, Trait, or Object definition",
            Option(value).fold(untpd.EmptyTree.sourcePos)(_.sourcePos)
          )
        )

/** Closed shape profiles for the currently supported source grammar. */
enum ExpansionShapeProfile:
  case OrdinaryTemplate
  case NonCaseNonGenericTemplate
  case OneInvariantUnboundedTypeParameter
  case TwoInvariantUpperBoundedTypeParameters
  case NoTypeOrValueParameters

final case class ExpansionAdmission(
    targetKind: ExpansionTargetKind,
    shapeProfile: ExpansionShapeProfile
)

/** Bounded information about the invocation's current container. */
final case class ExpansionContainerContext(siblingNames: Set[String])

/** One admitted invocation against the current staged program. */
final case class ExpansionInput(
    annotationName: String,
    primary: ExpansionTarget,
    companion: Option[ExpansionTarget],
    container: ExpansionContainerContext,
    currentAnnotation: untpd.Tree,
    admission: ExpansionAdmission
):
  def targetView(using Context): Either[ExpansionDiagnostic, ExpansionTargetView] =
    primary match
      case ExpansionTarget.Class(tree) => ExpansionTargetView.decode(tree)
      case ExpansionTarget.Trait(tree) => ExpansionTargetView.decode(tree)
      case ExpansionTarget.Object(tree) =>
        Left(ExpansionDiagnostic("target view is unavailable for an object primary", tree.sourcePos))

  def targetBodyView(using Context): Either[ExpansionDiagnostic, ExpansionTargetBodyView] =
    primary match
      case ExpansionTarget.Class(tree) => ExpansionTargetBodyView.decode(tree)
      case ExpansionTarget.Trait(tree) => ExpansionTargetBodyView.decode(tree)
      case ExpansionTarget.Object(tree) =>
        Left(ExpansionDiagnostic("target body view is unavailable for an object primary", tree.sourcePos))

  def targetTypeStructureView(using Context): Either[ExpansionDiagnostic, ExpansionTargetTypeStructureView] =
    primary match
      case ExpansionTarget.Class(tree) => ExpansionTargetTypeStructureView.decode(tree)
      case ExpansionTarget.Trait(tree) => ExpansionTargetTypeStructureView.decode(tree)
      case ExpansionTarget.Object(tree) =>
        Left(ExpansionDiagnostic("target type-structure view is unavailable for an object primary", tree.sourcePos))

enum TargetPatch:
  case AppendMembers(members: List[untpd.Tree])
  case ReplaceAnnotations(annotations: List[untpd.Tree])
  case SetSelf(value: Option[untpd.ValDef])

enum PrimaryChange:
  case Preserve
  case Merge(patches: List[TargetPatch])
  case Replace(value: ExpansionTarget)
  case Delete

enum CompanionChange:
  case Preserve
  case Merge(patches: List[TargetPatch])
  case Replace(value: ExpansionTarget)
  case Create(value: ExpansionTarget, placement: DefinitionPlacement)
  case Delete

final class SiblingRef private[api] (
    val name: String,
    val kind: ExpansionTargetKind
)

enum SiblingChange:
  case Create(value: ExpansionTarget, placement: DefinitionPlacement)
  case Merge(existing: SiblingRef, patches: List[TargetPatch])
  case Replace(existing: SiblingRef, value: ExpansionTarget)
  case Delete(existing: SiblingRef)

enum DefinitionPlacement:
  case BeforePrimary, AfterPrimary

final case class ExpansionChanges(
    primary: PrimaryChange = PrimaryChange.Preserve,
    companion: CompanionChange = CompanionChange.Preserve,
    siblings: List[SiblingChange] = Nil
)

/** Exact raw replacement, sparse structured changes, or controlled rejection. */
enum ExpansionOutcome:
  case Structured(changes: ExpansionChanges)
  case Expanded(trees: List[untpd.Tree])
  case Rejected(diagnostics: List[ExpansionDiagnostic])

final case class ExpansionDiagnostic(message: String, pos: SrcPos)
