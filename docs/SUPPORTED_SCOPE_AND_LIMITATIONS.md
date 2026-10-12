# Supported scope and limitations

MacroParadise 0.2.0-SNAPSHOT is an experimental pre-Typer compiler plugin with a source-breaking handler API. It is qualified only on JDK 25, sbt 1.12.15, and the exact Scala lines 3.3.8, 3.8.4, and 3.9.0.

The [authoritative mode and parity guide](AUTHORING_MODES_AND_FEATURE_PARITY.md)
separates shared backend semantics from authoring, topology, wiring, and release limits.

## Supported targets

The current public target kinds are Class, Trait, and Object. The kind is
descriptive; handlers decide their own applicability inside `expand` and reject
unsupported shapes explicitly. This does not widen the established bounded
source grammar.

Execution remains restricted internally to supported package-scope occurrences in one compilation unit. The neutral public naming does not widen support to nested or local definitions. Enums, enum cases, methods, values, variables, type aliases, parameters, type parameters, givens, and extension-related forms are out of scope.

## Relationships and changes

The plugin identifies the current annotated primary and any real same-name class-or-trait/object companion in the same scope and unit. Adjacency is irrelevant. The public input does not expose mutable container state.

Structured output is a sparse `ExpansionChanges` transaction. Preserve, nonempty ordered Merge, Replace, and Delete are supported for the primary and existing companion; missing-companion Create is explicit. Omitted sibling changes preserve siblings. Generic Replace can change name or target kind, after which relationships are recomputed from the final program.

Public helpers support generic member placement, annotation replacement, sibling creation, and bounded trait-self preparation. They do not author arbitrary trees, inspect semantic symbols, infer missing-companion policy, or provide arbitrary container mutation.

## Raw output

Raw `Expanded` replaces the exact owned primary/verified-companion region with
zero or more Class/Trait/Object definitions. Empty output is legal. Output order
is exact and no first element receives a privileged role. Nonempty generated
roots must have source or span provenance. Recursive identity aliases among
owned definition/template/annotation nodes are rejected, except canonical empty
sentinels; provenance is never fabricated or repaired.

## Scheduling and failure

Handled annotations are selected from current staged trees in deterministic
source/preorder/annotation order. Fresh annotations introduced by earlier stages
are eligible; removed annotations disappear. The same physical annotation
occurrence is consumed once. The default 256-success operational budget is
configurable through `expansionBudget=<positive-decimal>` and is not a claim
about termination.

Every compilation unit is transactional. Any later rejection, validation error, handler exception, ambiguous topology, or budget exhaustion rolls back the whole unit.

## Embedded producer frontend

Current main has two distinct embedded-producer modes. The established
precompiled mode compiles a producer before its consumers and derives separate
marker and handler roles. On unreleased source-built `0.2.0-SNAPSHOT`, a
second bounded same-module mode is now experimentally supported with exactly:

- one explicit `SameModuleEmbeddedBinding`;
- `MacroParadiseSameModulePlugin` enabled with
  `macroParadiseSameModuleEmbeddedBinding` configured through
  `macroParadiseSameModuleEmbedded`;
- one physical producer source containing one canonical
  `@embeddedExpander` annotation class and its same-file companion
  `transform`; and
- one or more consumers in different physical source files in the same Scala
  module.

The binding's producer path is relative to
`macroParadiseSameModuleSourceRoot`, whose default is
`Compile / scalaSource`. The external `macroParadiseSameModuleBinding` and
the embedded binding are mutually exclusive. The generated adapter remains private:
users neither configure nor reference its name.

Both embedded modes retain the current Class/Trait/Object handler-side target
grammar. Annotation constructor arguments remain pre-typer syntax. Defaults
are not materialized, implicits are not synthesized, arbitrary expressions
are not evaluated, and semantic typing is unavailable.

The bounded same-module mode is qualified on JDK 25, sbt 1.12.15, and exact
Scala 3.3.8, 3.8.4, and 3.9.0 through CLI/Zinc and the accepted sbt-imported,
sbt-shell-delegated IntelliJ workflow. This is not native JPS support or
general same-module support.

The same physical source file layout and multiple embedded bindings remain
unsupported, as do multiple producer sources or
annotation identities; automatic discovery; helper-source graph solving;
consumer-owned implementation dependencies or cycles; nested or local
embedded declarations; wildcard, renamed, local, ambiguous, or inferred
same-package short imports; broader target grammar; binary-cross marker
guarantees; and typed `MacroAnnotation` equivalence.

The separate bounded different-file same-module Model A remains an
external-handler track. The four precompiled embedded setup quadrants and
manual wiring remain first-class and unchanged.

This acceptance does not alter the immutable `0.1.1` release. The
`0.2.0-SNAPSHOT` development artifacts are source-built/local-only and are
not remotely published; no stable API or release commitment follows.

See [Embedded producer authoring](EMBEDDED_PRODUCER_AUTHORING.md).

## External handlers

External handlers must implement `ExpansionHandler`, be precompiled for the exact compiler line, and be discoverable either through `@expander` metadata or explicit plugin configuration. The handler classpath must include the handler and its dependencies. The plugin/API artifact boundary and parent-first contract identity checks remain enforced.

The API contains raw compiler trees and is not binary portable across Scala compiler versions. There are no compatibility aliases for the 0.1.1 protocol in the 0.2.0-SNAPSHOT surface.

Tree authoring is caller-owned. Quasiquotes integration remains separate and no AUXify or Quasiquotes source is migrated by this change.
