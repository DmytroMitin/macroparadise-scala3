# Expansion model and scheduling

MacroParadise 0.2.0-SNAPSHOT has one experimental, exact-Scala-version handler API. A handler implements `ExpansionHandler`, declares the annotation identity and a list of `ExpansionAdmission` values, and transforms one `ExpansionInput` into one `ExpansionOutcome`.

The public model is intentionally orthogonal:

- target kind is `Class`, `Trait`, or `Object`;
- `primary`, `companion`, and `sibling` describe relationships in the current invocation revision;
- shape admission is independent of target kind;
- the compiler plugin discovers relationships and owns scheduling and atomic application.

The API is compiled against compiler internals and must use the artifact for the exact active Scala line: 3.3.8, 3.8.4, or 3.9.0.

## Input and admission

`ExpansionTarget` is a closed representation of the currently implemented Class/Trait/Object slice. `ExpansionAdmission(targetKind, shapeProfile)` lets a handler opt into one or more exact supported target shapes. The profiles preserve the existing bounded grammar; they do not authorize arbitrary Scala definitions or nested/local targets.

`ExpansionInput.primary` is the annotated occurrence selected from the current staged package. `companion` is present only when the current staged program has a compatible class-or-trait/object definition with the same decoded name in the same enclosing scope and compilation unit. Definitions need not be adjacent. `container` exposes only bounded sibling-name information, not mutable container state.

The target, body, and type-structure views provide normalized read-only syntax for the supported handler use cases. Raw tree construction remains the handler or Quasiquotes caller's responsibility.

## Structured sparse changes

`ExpansionOutcome.Structured(ExpansionChanges(...))` describes a transaction against the input revision. Each domain is sparse:

- `PrimaryChange`: Preserve, Merge, Replace, or Delete;
- `CompanionChange`: Preserve, Merge, Replace, Create, or Delete;
- `SiblingChange`: Create, or an addressed Merge, Replace, or Delete;
- omitting a sibling change preserves that sibling exactly.

Merge contains a nonempty ordered list of `TargetPatch` operations. The current patch vocabulary appends members, replaces annotations, or sets/removes a trait self value. Unmentioned patch domains remain unchanged.

`CompanionChange.Create` is a dedicated relationship request. It is legal only when the input has no companion, the resulting primary survives, and the created definition is its real final companion. `SiblingChange.Create` cannot be used to disguise that request.

Primary/companion/sibling labels are input addresses, not durable output roles. All requested changes resolve against the same revision and apply to a private staged copy. Generic Replace may change kind and name. After application the plugin discards the old labels, recomputes companions from the final definitions, and validates collisions, tree ownership, and structure atomically. For example, replacing `class A` with `class B` while preserving `object A` may make an existing `object B` the new companion.

## Immutable edits and helpers

For composable structured authoring, start once with `ExpansionEdit.start(input)`, thread the immutable edit through helpers, and finish once with `ExpansionEdit.finish`.

The generic helpers are:

- `placeMember(s)InPrimary`;
- `placeMember(s)InCompanion`;
- `replacePrimaryAnnotations`;
- `replaceCompanionAnnotations`;
- `createSibling`;
- `prepareTraitSelf`.

One `MemberConflictPolicy` covers member kinds. `MissingCompanionPolicy` makes missing-companion creation explicit. Creating a missing companion and then adding more members stays one normalized `CompanionChange.Create` with the updated tree. Any helper error propagates through the edit and becomes a rejected outcome at `finish`.

## Raw exact replacement

`ExpansionOutcome.Expanded(trees)` is the expert escape hatch. It replaces exactly the invocation primary and its verified current companion, even when they are nonadjacent. The returned list may contain zero or more supported Class/Trait/Object definitions:

- `Expanded(Nil)` deletes the entire owned region;
- one result need not match the old kind or name;
- many results retain their exact order;
- no returned element is a distinguished continuation primary.

The owned definitions are removed and the output list is inserted at the earlier owned position, or at the primary position when no companion exists. Unrelated siblings keep their relative order. The final program is validated and all relationships are recomputed before scheduling continues.

## Current-staged-tree scheduler

Stacked annotations need no policy or handler opt-in. After every successful stage the plugin validates the complete staged package, discards stale positional and relationship conclusions, and rescans the current trees from the deterministic beginning. Selection order is container statement order, tree preorder, then annotation-list order from left to right.

A private identity ledger prevents the same physical annotation tree from running twice. It is not an immutable work queue. Therefore:

- a later annotation runs only if it still exists after earlier changes;
- fresh handled annotations on replaced targets, created siblings, or recomputed companions are ordinary work;
- a freshly constructed syntax-equivalent annotation is eligible;
- deleting a definition also deletes all pending work owned by that definition.

MacroParadise does not attempt a semantic termination proof. A 32-success operational budget protects the compiler process; exhaustion is a diagnostic and causes rollback.

## Atomicity and current limits

All stages in one compilation unit operate on private staged state. A rejection, invalid output, thrown handler failure, collision, stale/duplicate address, or budget exhaustion returns the original unit; no successful prefix escapes.

The current implementation remains limited to the established package-level Class/Trait/Object grammar and exact admission profiles. Nested, inner, local, enum, enum-case, method, value, variable, type, parameter, given, and extension targets are not enabled. This model does not widen source grammar, provide semantic typing, or move tree authoring into MacroParadise.
