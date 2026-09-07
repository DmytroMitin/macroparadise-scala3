# Expansion model and composition

The released `0.1.1` API supports legacy class/trait handlers. Current
`0.2.0-SNAPSHOT` additionally supports one role-aware handler on an ordinary
top-level object, and immutable helper composition inside either handler family.
All compiler trees and helpers are exact-version experimental APIs on Scala
3.3.8, 3.8.4, and 3.9.0. Plugin admission still decides which source shapes run.

## Input, edit state, terminal outcome

**An Outcome is not the next Input.** Input contains plugin-owned annotation
identity, target admission, lease, and neighboring-name context. An outcome
cannot reconstruct that context. There is no Outcome-to-Input conversion.

```text
real Input -> start immutable Edit -> helper -> helper -> finish -> Outcome
```

`LegacyExpansionEdit.start` and `RoleAwareExpansionEdit.start` return
`Either[ExpansionDiagnostic, Edit]`. Successful transitions retain the original
invocation context, mandatory current primary, and optional current opposite.
They validate before copying a Template and never commit package changes.
Construction is restricted to factories in the supported Scala source API.
Scala compiler-generated JVM constructor visibility is not an isolation boundary
against Java/reflection or code impersonating the API package.
Use one finalizer after a short-circuiting program:

```scala
import paradise3.api.*
import paradise3.api.helpers.*

val edited = for
  e0 <- LegacyExpansionEdit.start(input)
  e1 <- ExpansionHelpers.addStringMethodToClass(e0, "a", "A")
  e2 <- ExpansionHelpers.placeMembersInPrimary(e1, primaryMembers)
  e3 <- ExpansionHelpers.placeMembersInCompanion(e2, companionMembers)
yield e3
LegacyExpansionEdit.finish(input, edited)
```

Here `input` is the real `ExpansionInput`; the caller has already authored
`primaryMembers` and `companionMembers`. The original input is needed at this
finalizer because a failed `Either` contains a diagnostic but no mandatory
legacy fallback tree. `finish(edit)` also accepts a successful state directly.
The original input must be the same invocation object, not a reconstructed copy.

```scala
val edited = for
  e0 <- RoleAwareExpansionEdit.start(input)
  e1 <- ExpansionHelpers.placeMembersInPrimary(e0, objectMembers)
  e2 <- ExpansionHelpers.placeMembersInOpposite(
    e1, oppositeMembers,
    RoleAwareMissingOppositePolicy.CreateTrait(OppositePlacement.AfterPrimary)
  )
yield e2
RoleAwareExpansionEdit.finish(edited)
```

This `input` is `RoleAwareExpansionInput`. The missing policy is explicitly
`Reject`, `CreateClass(placement)`, or `CreateTrait(placement)`. An existing
class/trait is edited in its existing kind. Repeated edits to a newly created
opposite retain `Create` and the original placement. Editing either side first
preserves the changes when editing the other side. Primary-only edits preserve
the opposite by default. No deletion or role/focus conversion is an edit operation.

The narrow string-method edit retains the established rule that an existing
same-name method wins. Generic batch placement instead rejects any direct raw
term-name conflict, including one introduced by an earlier edit.

The role-aware input one-shot `placeMembersInPrimary` and
`placeMembersInOpposite` overloads are single-edit wrappers around this engine.
Existing legacy terminal helpers retain their historical behavior, including
companion omission by primary-only helpers. Their behavior has not been changed
to match the new state semantics.

## A. Legacy raw output

These are structural statuses under `RawExpansionOutputValidator`, assuming
legal same-name role trees and conflict-free named additions. They do not prove
admission, annotation lifecycle, transaction legality, or ordinary typing.

| Raw `Expanded` trees | Structural status |
| --- | --- |
| `[]` | Invalid: non-empty output required |
| `[primary]` | Valid shape |
| `[primary, sameNameCompanion]` | Valid shape |
| `[sameNameCompanion]` | Invalid: mandatory first TypeDef primary absent |
| `[primary, other, sameNameCompanion]` | Invalid: companion must immediately follow primary |
| Two same-name TypeDef primaries | Invalid: exactly one primary |
| Two same-name ModuleDef companions | Invalid: at most one companion |
| Same-name object described by the author as “additional” | Raw output has no additional-role field: immediately following means canonical companion; any other position fails |
| Duplicate/conflicting additional named outputs | Invalid |

```scala
ExpansionOutcome.Expanded(List(input.annotatedClass) ++ input.existingCompanion.toList)
// Valid structural shape when the lease is the legal same-name object.
ExpansionOutcome.Expanded(List(input.annotatedClass))
// Valid structural shape; omission semantics depend on coordinator context.
ExpansionOutcome.Expanded(input.existingCompanion.toList)
// Invalid: empty for None, or companion-only for Some.
ExpansionOutcome.Expanded(Nil)
// Invalid: empty output.
```

The raw validator does not itself check class/trait kind or classify unknown
additional raw tree kinds. Structured validation and transaction validation
provide stronger role/kind checks; raw representability is not support for
primary deletion or conversion.

## B. Legacy structured output

| Field or relationship | Requirement |
| --- | --- |
| `primary` | Mandatory non-null same-name TypeDef |
| Primary raw kind | Same class/trait kind; enum and non-class-type distinctions are also checked |
| `companion` | Optional, but Option container must be non-null |
| `Some(companion)` | Non-null same-name ModuleDef |
| Same-name object | Belongs only in `companion` |
| `additionalTopLevelDefinitions` | Non-null list of non-null TypeDef/ModuleDef only |
| Additional primary/companion role | Cannot reintroduce either same-name role |
| Additional names | Unique and conflict-free |

```scala
StructuredExpansionOutput(
  primary = input.annotatedClass,
  companion = input.existingCompanion,
  additionalTopLevelDefinitions = Nil
)
// Positive when role/name/kind checks hold.

StructuredExpansionOutput(
  primary = input.annotatedClass,
  companion = None,
  additionalTopLevelDefinitions = Nil
)
// Structurally valid; terminal omission semantics remain context-sensitive.

StructuredExpansionOutput(
  primary = input.annotatedClass,
  companion = None,
  additionalTopLevelDefinitions = input.existingCompanion.toList
)
// Invalid when Some: same-name companion cannot masquerade as additional.
// With None this is simply the valid primary-only example.
```

Validated structured output is canonicalized to primary, optional companion,
then caller-ordered additions. Raw validation runs again as defense in depth.

## C. Legacy lease and omission

| Context | Companion effect |
| --- | --- |
| Not leased to this handler | Handler does not own/remove it |
| Leased and explicitly returned | Preserved or replaced by the returned tree |
| Leased and omitted in a complete standalone terminal result | Historical `DropCurrent` |
| Leased and omitted in intermediate source-ordered composition | Historical `RetainCurrent` |
| New immutable edit state | Preserved by state semantics; omission is not an edit command |

“Standalone” describes a complete result without further coordinator-owned
requests, not just the descriptor's policy value. A sole `SourceOrdered`
participant can produce a complete standalone result.

Conceptual **Preserve / Replace / Create / Delete** apply to the legacy pair,
but the terminal legacy protocol expresses them through complete output trees
and coordinator context rather than a public intent enum. The new edit API
supports preserving, replacing through member insertion, and creating an object
companion. Explicit composable Delete remains deferred. Historical leased
companion removal through complete-result omission remains compatible.

## D. Role-aware topology

| Property / operation | Current status |
| --- | --- |
| Routed primary | Mandatory ordinary top-level Object |
| Opposite | Optional same-name Class or Trait |
| Primary name and role/kind | Invariant |
| `Preserve` | Supported; also preserves an existing unleased opposite |
| `Replace` | Existing same-name same-kind lease plus descriptor capability |
| `Create` | No discovered opposite, explicit Class/Trait and placement, plus capability |
| `Delete` | Not in the public outcome or edit API |
| Primary delete | Not represented/supported |
| Primary role change or focus transfer | Not supported |
| Role-aware Class/Trait primary | Algebra cases exist; routing deferred |
| Object opposite with Object primary | Illegal |

`leasedOpposite=None` may mean the handler did not request a lease. It does not
prove the package lacks an opposite or authorize creation. The plugin validates
discovery and capability at finalization. `ExpansionPrimaryRole.Class/Trait`
and `ExpansionOppositeRole.Object` represent closed common-model cases; their
existence does not widen current routed admission.

## E. Three distinct composition layers

| Layer | Owner and current support |
| --- | --- |
| Inside one handler / one annotation | Immutable edit program; this is helper composition |
| Between handlers / source annotations | Plugin scheduler; bounded legacy `SourceOrdered`; multiple role-aware object participants remain fail closed |
| Fresh/generated handled annotations | Plugin R1/R2 lineage scheduler; role-aware object R1/R2 remains fail closed |

A sole role-aware `SourceOrdered` participant may run. This does not establish
multi-participant object composition. No helper step creates a new invocation.

## F. Transformation examples

These annotation names illustrate behavior a correctly wired handler could
request; they are not built-in annotation implementations.

| Source example | Status |
| --- | --- |
| `@identity class A; object A` | Supported legacy identity in the admitted boundary; return a leased companion explicitly or use preserve-by-default state |
| `class A; @identity object A` | Supported role-aware object identity |
| `@addCompanion class A` | Supported bounded object-companion creation |
| `@removeCompanion class A; object A` | Historically possible with a leased companion omitted from a complete legacy result; no explicit composable delete |
| `@transformMeToCompanion class A` | Unsupported if it deletes primary or changes class to object |
| `@transformMeToCompanion object A` | Unsupported object-to-class/trait focus conversion |

Trait-primary legacy examples additionally require an admitted trait profile.
A primary remains mandatory even if an author can construct a raw tree with a
different role. Lifecycle validators reject unsupported topology changes.

## Exact generated trees, atomicity, and ownership

Each generic batch must be a non-null, non-empty list of non-null `untpd.DefDef`
or `untpd.ValDef` entries, with usable non-constructor raw term names and usable
root source attachment or span. Duplicate generated names, direct existing
term-name conflicts, and pre-typer overloads reject before Template/shell copy.
Exact supplied members are appended; they are not parsed, rebuilt, repaired,
typed, symbol-resolved, or checked against inherited members. Generic batches
do not admit TypeDef/ModuleDef.

An edit state is an immutable proposal. Raw compiler trees remain shared expert
values: callers must not mutate them. A failed later transition leaves earlier
states and original trees unchanged. Short-circuiting finalization returns one
Rejected outcome, with no earlier partial output. A successful proposal still
passes plugin-owned terminal validation. Capability, name, kind, conflict, or
annotation-lineage failure rolls back the original primary/opposite/package
snapshot. Ordinary typer errors after commit remain ordinary compiler errors.

Legacy finish consumes only the exact current annotation; older direct inputs
with no current annotation retain the historical all-annotation cleanup rule.
Role-aware finish keeps annotation identities for plugin canonicalization.
The plugin consumes the current handled annotation once, preserves later
original annotations in exact identity/order, and rejects counterfeit or fresh
handled annotations. Helpers cannot bypass this validation.

New sibling/opposite creation is primarily generation plus Macro lifecycle
placement. Modifying an existing raw primary/opposite is an existing-tree
structural transformation use case, naturally related to U-style authoring.
Deleting an opposite changes source topology; deleting/changing a primary or
transferring focus requires a future scheduler and annotation-ownership contract.
This description makes no public U-support claim.

Quasiquotes/U or expert raw code authors/transforms exact trees. Macro-Paradise
owns target admission, leasing, legal topology, annotation lifecycle, conflicts,
commit, and rollback. No production Quasiquotes, Scalameta, AUXify, controller,
or peer-checkout dependency is introduced.

Executable anchors: `RawExpansionOutputValidatorSpec`,
`StructuredExpansionOutputValidatorSpec`, `RoleAwareTransactionKernelSpec`,
`ExpansionEditSpec`, `GeneratedMemberPlacementHelperSpec`,
`RoleAwarePublicObjectCompilerSpec`, and the independently packaged handler and
typed/runtime consumer in `plugin-api-role-aware-contract-probe`.
