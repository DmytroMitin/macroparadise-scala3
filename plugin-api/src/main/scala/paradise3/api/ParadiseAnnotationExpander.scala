package paradise3.api

import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.util.SrcPos

/** Whether an experimental handler may participate in plugin-owned sequential composition. */
enum ExpansionCompositionPolicy:
  /** Preserve the safe historical behavior: the handler is admitted only by itself. */
  case StandaloneOnly
  /** Opt into source-ordered composition subject to independent plugin-owned
    * target admission for every participant and output verification.
    */
  case SourceOrdered

/** Exact-build target envelope requested by an experimental external handler.
  *
  * The value selects one plugin-owned admission profile. It does not expose or
  * delegate the profile's syntactic rules to the handler, and it is deliberately
  * closed to the envelopes proven by the repository.
  */
enum ExpansionTargetProfile:
  /** Preserve the historical non-case, non-generic top-level class envelope. */
  case CommonClassOnly
  /** Request the restricted one-invariant-unbounded-parameter top-level trait envelope. */
  case RestrictedGenericTraitApply
  /** Request exactly two invariant ordinary upper-bounded parameters on an ordinary top-level trait. */
  case TwoUpperBoundedGenericTrait
  /** Request one ordinary top-level trait with no type or constructor/value parameters. */
  case PlainZeroParameterTrait
  /** Request exactly the closed union of the restricted one-unbounded and two-upper-bounded trait envelopes. */
  case RestrictedOrTwoUpperBoundedGenericTrait

/** Experimental handler-loading spike contract.
  *
  * A `ParadiseAnnotationExpander` is a precompiled JVM class loaded by the
  * compiler plugin from the explicit handler classpath. The one public
  * expansion entrypoint is `expand(input)`: matching, loading, diagnostics, and
  * fallback orchestration are still owned by the plugin.
  *
  * This is not the final public macro-annotation API. Handlers cannot currently
  * be defined in the same source module as the annotated code, and the exposed
  * compiler-tree surface is expected to change as the experiment evolves.
  */
trait ParadiseAnnotationExpander:
  /** Canonical syntactic annotation class name claimed by this precompiled handler.
    *
    * A legacy simple name remains supported. A dot-qualified name matches
    * direct qualified source syntax or the bounded source-syntactic form of one
    * unambiguous, source-preceding, package-scope explicit unchanged import.
    * The plugin does not invoke typer or resolve wildcard, renamed/aliased,
    * local/nested, given, exported, shadowing-dependent, or semantic imports.
    */
  def annotationName: String

  /** Target envelope requested from the plugin-owned admission authority.
    *
    * The default preserves the historical class-only behavior for every
    * pre-existing handler. Opting in requests validation; it does not let the
    * handler define or bypass the restricted trait rules.
    */
  def targetProfile: ExpansionTargetProfile =
    ExpansionTargetProfile.CommonClassOnly

  /** Composition capability snapshotted by the plugin before expansion.
    *
    * The safe default keeps pre-existing handlers standalone. `SourceOrdered`
    * is only an opt-in to plugin-owned orchestration; it does not bypass each
    * participant's target admission or output and annotation-preservation
    * validation.
    */
  def compositionPolicy: ExpansionCompositionPolicy =
    ExpansionCompositionPolicy.StandaloneOnly

  /** Whether this handler wants the plugin to remove and pass a following companion.
    *
    * The default is `false` so existing external handlers keep seeing only the
    * annotated class. Handlers that generate or merge companions can opt in.
    * The current implementation is syntactic and narrow: only a following
    * top-level companion in the same package stats is consumed, and broader
    * semantic companion resolution is not attempted.
    */
  def consumesExistingCompanion: Boolean = false

  /** Expand or reject the annotated definition represented by `input`. */
  def expand(input: ExpansionInput)(using Context): ExpansionOutcome

/** Low-level input passed to an experimental external handler.
  *
  * `annotatedClass` is the raw untyped compiler tree for the annotated class.
  * `currentAnnotation` is the raw annotation tree currently being expanded,
  * when the plugin can provide it. Helper APIs use it to consume only the
  * current handled annotation while preserving later handled and unhandled raw
  * annotation trees.
  * `existingCompanion` is present only for handlers that opt in through
  * `consumesExistingCompanion`; when present, the plugin has removed that
  * following companion from the package stats so the handler can return the
  * merged replacement. Removal is a temporary lease: the coordinator restores
  * the original companion when invocation, outcome adaptation, rejection, or
  * raw-output validation fails. `topLevelNames` is the plugin's current
  * syntactic view of neighboring top-level names. This data is intentionally
  * close to the compiler internals so the spike can validate power and
  * classloading behavior, but direct raw tree access is
  * compiler-version-sensitive and not stable public API.
  */
final case class ExpansionInput(
    annotationName: String,
    annotatedClass: untpd.TypeDef,
    existingCompanion: Option[untpd.ModuleDef],
    topLevelNames: Set[String],
    currentAnnotation: Option[untpd.Tree] = None
):
  /** Convenience class name for simple handlers.
    *
    * Handlers that need more control can still inspect `annotatedClass`
    * directly, accepting the raw compiler-tree coupling that comes with it.
    */
  def className: String = annotatedClass.name.toString

  /** Decode the bounded read-only syntactic class/constructor view.
    *
    * This convenience does not replace `annotatedClass`, apply admission
    * policy, or promise compiler-version independence. Malformed and hostile
    * direct inputs produce a controlled diagnostic.
    */
  def annotatedClassView(using Context): Either[ExpansionDiagnostic, AnnotatedClassView] =
    AnnotatedClassView.decode(annotatedClass)

  /** Decode the bounded read-only syntactic direct-body and method view.
    *
    * This additive convenience preserves the raw `annotatedClass` escape hatch
    * and applies no typing or handler-specific admission policy. Malformed and
    * hostile direct inputs produce a controlled diagnostic.
    */
  def annotatedClassBodyView(using Context): Either[ExpansionDiagnostic, AnnotatedClassBodyView] =
    AnnotatedClassBodyView.decode(annotatedClass)

  /** Decode the bounded read-only enclosing-bound and direct type-member view.
    *
    * This additive convenience shares the body view's tiny syntactic type-shape
    * algebra, preserves the raw `annotatedClass` escape hatch, and applies no
    * typing or handler-specific admission policy.
    */
  def annotatedClassTypeStructureView(using Context): Either[ExpansionDiagnostic, AnnotatedClassTypeStructureView] =
    AnnotatedClassTypeStructureView.decode(annotatedClass)

/** Explicit result of an external handler expansion attempt.
  *
  * Returning an `ExpansionOutcome` keeps expected handler behavior out of
  * exception-driven control flow. Handlers can return ordered raw trees,
  * role-structured top-level definition output, reject with user-facing
  * diagnostics plus a fallback class tree, or say that the input does not
  * apply to their narrow supported shape.
  *
  * `Expanded(trees)` is an ordered raw replacement/output list. A handler may
  * return a transformed primary definition followed by additional top-level
  * definitions. The plugin preserves that raw ordering after a bounded
  * internal structural check for the current same-name primary, companion
  * placement, and duplicate or conflicting named additions. Unknown additional
  * tree kinds remain unclassified. This does not reinterpret the list as a
  * stable structured output model or add typed/symbol validation.
  *
  * `Structured(output)` is the bounded convenience path for handlers that know
  * the primary, optional same-name companion, and named additional-definition
  * roles. The plugin validates those roles, canonicalizes them to primary,
  * companion, then caller-ordered additional definitions, and applies the same
  * raw structural validation as defense in depth. Structured additional output
  * accepts only `TypeDef` and `ModuleDef`; unusual raw trees must use
  * `Expanded`.
  *
  * `Rejected(diagnostics, fallback)` describes the handler boundary, not the
  * plugin's complete error-recovery behavior. Diagnostics and fallback must be
  * non-null, and the fallback must be a same-name `TypeDef` for the current
  * primary. A helper may return the original annotated class unchanged and
  * without partial output. The plugin validates the fallback, reports the
  * diagnostics, and currently strips every annotation it handles from that
  * fallback before ordinary compilation continues, preventing another
  * expansion attempt after a known rejection.
  *
  * After a handler has been selected for a matching annotation and admitted
  * target, `NotApplicable` is a diagnosed rejection rather than a silent skip.
  * A JVM `null` outcome is invalid and is likewise converted to a diagnosed
  * current-primary fallback.
  */
enum ExpansionOutcome:
  /** Ordered raw replacement/output trees for the current top-level definition. */
  case Expanded(trees: List[untpd.Tree])
  /** Role-structured top-level definition output canonicalized by the plugin. */
  case Structured(output: StructuredExpansionOutput)
  /** Handler-level rejected fallback; plugin orchestration may strip handled annotations during error recovery. */
  case Rejected(diagnostics: List[ExpansionDiagnostic], fallback: untpd.TypeDef)
  case NotApplicable

/** Experimental role-structured successful output for common definition shapes.
  *
  * This remains a compiler-version-sensitive `untpd` surface rather than a
  * compiler-free model or stable public ADT. Additional definitions are
  * caller-ordered and are restricted by the plugin to `TypeDef` and
  * `ModuleDef`.
  */
final case class StructuredExpansionOutput(
    primary: untpd.TypeDef,
    companion: Option[untpd.ModuleDef],
    additionalTopLevelDefinitions: List[untpd.Tree]
)

/** User-facing diagnostic reported for an experimental external expansion.
  *
  * Source positions currently come from compiler-internal trees and source
  * files. Their precision and stability are part of the experiment rather than
  * a guaranteed public contract.
  */
final case class ExpansionDiagnostic(message: String, pos: SrcPos)
