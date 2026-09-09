# Supported scope and limitations

MacroParadise 0.2.0-SNAPSHOT is an experimental pre-Typer compiler plugin with a source-breaking handler API. It is qualified only on JDK 25, sbt 1.12.15, and the exact Scala lines 3.3.8, 3.8.4, and 3.9.0.

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

## External handlers

External handlers must implement `ExpansionHandler`, be precompiled for the exact compiler line, and be discoverable either through `@expander` metadata or explicit plugin configuration. The handler classpath must include the handler and its dependencies. The plugin/API artifact boundary and parent-first contract identity checks remain enforced.

The API contains raw compiler trees and is not binary portable across Scala compiler versions. There are no compatibility aliases for the 0.1.1 protocol in the 0.2.0-SNAPSHOT surface.

Tree authoring is caller-owned. Quasiquotes integration remains separate and no AUXify or Quasiquotes source is migrated by this change.
