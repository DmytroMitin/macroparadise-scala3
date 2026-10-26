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
second bounded same-module mode is now experimentally supported as either the
singleton or an exact-two independent pair:

- one explicit `SameModuleEmbeddedBinding`;
- `MacroParadiseSameModulePlugin` enabled with
  `macroParadiseSameModuleEmbeddedBinding` configured through
  `macroParadiseSameModuleEmbedded`;
- or exactly two distinct `SameModuleEmbeddedBinding` values configured through
  `macroParadiseSameModuleEmbeddedBindings := Seq(...)`;
- each physical producer source containing one canonical declaration (one
  physical producer source for the singleton, two for I2),
  an `@embeddedExpander` annotation class and its same-file companion
  `transform`; and
- consumers in different physical source files in the same Scala module, with
  each consumer unit selecting at most one configured annotation.

The binding's producer path is relative to
`macroParadiseSameModuleSourceRoot`, whose default is
`Compile / scalaSource`. I1, E1, E2, and I2—the embedded singleton, external
singleton, external exact-two pair, and embedded exact-two pair—are mutually
exclusive. The generated adapter remains private:
users neither configure nor reference its name.

The exact-two embedded I2 layout is four physical files:
`demo/ProducerA.scala` to `consumer/ConsumerA.scala` and
`demo/ProducerB.scala` to `consumer/ConsumerB.scala`. A runnable `Main` is
co-located in one consumer file. Canonical qualified annotations and
source-preceding explicit unaliased imports are accepted. Alias, wildcard,
ambiguous, local, nested, late-import, same-source, producer-edge, both-in-one-
unit, and cross-binding introduction forms fail closed.

Both embedded modes retain the current Class/Trait/Object handler-side target
grammar. Annotation constructor arguments remain pre-typer syntax. Defaults
are not materialized, implicits are not synthesized, arbitrary expressions
are not evaluated, and semantic typing is unavailable.

Both bounded same-module configurations are qualified on JDK 25, sbt 1.12.15,
and exact Scala 3.3.8, 3.8.4, and 3.9.0 through CLI/Zinc, plus persistent sbt
BSP 3.8.4. Only the singleton carries accepted sbt-imported, sbt-shell-delegated
IntelliJ evidence. Pair IntelliJ, native JPS, and direct Java gutter Run remain
unqualified.

Neither configuration implies general same-module support.

The same physical source file layout and arbitrary embedded multiplicity remain
unsupported. The only multi-binding exception is exactly two independent
relationships; three or more bindings, a separate fifth initially-unsuspended
consumer source, automatic discovery, helper-source graph solving;
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

## Exactly two independent external same-module bindings

On unreleased source-built `0.2.0-SNAPSHOT`, exactly two independent external
relationships are experimentally supported with `MacroParadiseSameModulePlugin`,
`macroParadiseSameModuleBindings`, `macroParadiseSameModuleHandler`, and `Seq`.
The old `macroParadiseSameModuleBinding` singleton,
`macroParadiseSameModuleEmbeddedBinding` singleton, and
`macroParadiseSameModuleEmbeddedBindings` pair remain mutually exclusive
alternatives; the four setting families are never merged.

One Scala module contains four globally distinct producer files and two distinct
consumer files. Each physical consumer unit selects one direct-qualified
configured annotation. Imported short, wildcard, alias, and inferred
same-package short spellings are outside the pair grammar.

Exact Scala 3.3.8, 3.8.4, and 3.9.0 use JDK 25 and sbt 1.12.15 through
CLI/Zinc; persistent sbt BSP 3.8.4 is qualified, while pair IntelliJ, native
JPS, and direct Java gutter Run are not qualified. Existing singleton and
embedded IntelliJ evidence does not transfer to this pair.

Sorted repeated W1 relationship options use one aggregate exact-source-byte
identity; A-v1 to A-v2 without clean retains B-v1. Producer edges, same-unit
ambiguity, and dynamically introduced cross-binding annotations fail closed.
Stale producer output is rejected and repair proceeds without `clean`.

Three or more external or embedded bindings, multiple pair sets, mixed
embedded/external settings, producer DAGs, same-source
producer/consumer layouts, and cross-binding composition remain unsupported.
Released `0.1.1` has no pair support and `0.2.0-SNAPSHOT` is not remotely
published. See the [complete executable recipe](../sbt-integration/README.md#bounded-two-independent-external-same-module-recipe).

## External handlers

External handlers must implement `ExpansionHandler`, be precompiled for the exact compiler line, and be discoverable either through `@expander` metadata or explicit plugin configuration. The handler classpath must include the handler and its dependencies. The plugin/API artifact boundary and parent-first contract identity checks remain enforced.

The API contains raw compiler trees and is not binary portable across Scala compiler versions. There are no compatibility aliases for the 0.1.1 protocol in the 0.2.0-SNAPSHOT surface.

Tree authoring is caller-owned. Quasiquotes integration remains separate and no AUXify or Quasiquotes source is migrated by this change.
