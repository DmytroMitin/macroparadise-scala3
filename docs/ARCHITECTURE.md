# Architecture

## Compiler strategy

The project extends Scala 3 `StandardPlugin`; it is not a Scala 3
`ResearchPlugin`. Its unusual property is phase placement: the custom phase is
scheduled after `parser` and before ordinary `typer`. The phase rewrites
untyped compiler trees and replaces the compilation-unit tree before ordinary
typing continues.


```text
source parsing
  -> pre-typer annotation discovery and framework target eligibility
  -> built-in or precompiled handler expansion
  -> output validation and transactional composition
  -> package-stat replacement
  -> ordinary typer and later compiler phases
```

Pre-typer placement is the central mechanism. Generated class members,
companions, and sibling definitions exist before user-written references are
typed.

## Standard plugin, pre-typer trade-off

MacroParadise performs a syntactic transformation before typing, then hands the
rewritten program back to stock Dotty. Ordinary typer enters symbols, resolves
names, and types both source and generated definitions. In this bounded
architecture, that is why generated class members, companions, and siblings
can participate in ordinary typing without Scala-2-style analyzer hooks.

The trade-off is deliberate: a pre-typer handler has raw syntax and bounded
normalized syntactic views, not general typed semantic information. This
describes the current Scala 3 design; it is not a theorem that Scala 2 could
not have used a different syntactic-transformer architecture.


## Main modules

- `plugin-api` contains the experimental shared handler contract and marker
  metadata carrier.
- `plugin` contains the compiler plugin, handler loading, framework target
  eligibility, orchestration, validation, diagnostics, and preconsumer checker.
- `plugin-test-markers` and `plugin-test-handlers` contain unpublished product
  fixtures for marker and precompiled-handler behavior.
- `plugin-tests` is an ordinary consumer compiled with the packaged plugin.
- `examples/external-handler-starter` is an independent marker, handler, and
  consumer build that does not depend on repository fixture modules.
- Compatibility and same-module projects are isolated evidence lanes rather
  than additional production plugin artifacts.

The exact-cross plugin/API pair and sbt integration have an immutable `0.1.1`
release. Current `0.2.0-SNAPSHOT` development is source-built/local-only.
Fixture modules remain unpublished.

See [Authoring modes and feature parity](AUTHORING_MODES_AND_FEATURE_PARITY.md)
for the canonical frontend/topology matrix and the precise limits of shared backend semantics.

## Unified external handler flow

An external marker carries runtime metadata naming an already compiled handler:

```text
marker class metadata
  -> exact syntactic annotation identity
  -> unified handler descriptor and metadata binding
  -> parent-first handler class loader
  -> current staged-tree target and companion discovery
  -> one ExpansionInput / ExpansionOutcome transaction
  -> final-program validation and complete rescan
```

The handler contract exposes raw untyped Dotty trees and a small helper layer.
The helpers reduce repeated decoding and common class/companion/sibling
construction, but they do not make the boundary compiler independent.

## Input, output, and scheduling ownership

The plugin owns:

- annotation matching and framework-level supported-target recognition/eligibility;
- handler discovery, loading, descriptor capture, and failure adaptation;
- current-revision companion discovery and final relationship recomputation;
- deterministic current-staged-tree annotation scheduling;
- package conflicts, output validation, rollback, and diagnostics;
- final ordering and splicing before typer.

A handler owns its bounded transformation. It may use normalized read-only
target views and return either:

- exact zero-or-more raw replacement trees for the owned primary/companion region;
- sparse structured primary, companion, and sibling changes;
- a nonempty controlled diagnostic rejection.

Structured labels address the current input revision only. The plugin applies
the complete change set privately, discards the old labels, recomputes real
companions, validates the final program, and rescans it. Any later failure rolls
the whole compilation unit back.

## Class loading and exact compiler identity

Precompiled handlers load below a parent containing the self-contained plugin,
its exact unshaded embedded handler API, Scala runtime, and the one exact
compiler universe. The separate `plugin-api` artifact is an ordinary authoring
dependency, not a plugin-loader parent. The handler loader resolves the shared
interface and compiler types parent first. A child compiler copy would break
raw tree and context identity and is therefore rejected by the starter
precheck.

## Same-module boundary

Two opt-in tracks share the same suspension, current-output, source-identity,
and fresh-loader lifecycle but retain distinct public bindings and producer
topologies.

Released `0.1.1` and current `main` include bounded different-file external-
handler Model A. Its build names one marker source and one handler source
beneath an explicit source root and hashes their exact bytes into the compiler-
input identity. Exact Scala 3.3.8, 3.8.4, and 3.9.0 CLI/Zinc qualification
passes. Persistent sbt BSP and sbt-delegated IntelliJ qualification remain
bounded to exact 3.3.8 and 3.8.4.

Unreleased source-built `0.2.0-SNAPSHOT` additionally accepts one bounded
embedded producer source: one canonical `@embeddedExpander` annotation and its
same-file companion `transform`, consumed only from different physical source
files in the same module. `macroParadiseSameModuleEmbeddedBinding` is mutually
exclusive with Model A's `macroParadiseSameModuleBinding`. This embedded track
is qualified on exact Scala 3.3.8, 3.8.4, and 3.9.0 through CLI/Zinc and an sbt-
imported IntelliJ project whose Build and `run` execute in the sbt shell on JDK
25 and sbt 1.12.15. Persistent sbt BSP evidence is accepted on exact 3.8.4.

Both tracks are bounded experimental support, not general support. Native JPS,
direct Java gutter Run, same-file producer/consumer topologies, dependency
cycles, automatic discovery, multiple configured relationships, and multiple
embedded bindings remain unqualified, rejected, or unimplemented. Precompiled
handlers remain the broad/default supported experimental architecture, and the
four precompiled embedded producer setup quadrants remain unchanged.

See [Supported scope and limitations](SUPPORTED_SCOPE_AND_LIMITATIONS.md) and
[External handler authoring](EXTERNAL_HANDLER_AUTHORING.md). The separate
[quasiquote and pre-typer AST architecture](QUASIQUOTE_ARCHITECTURE.md)
distinguishes current typed quasiquotes from proposed neutral authoring and
hypothetical raw-untyped syntax.

## Orthogonal targets and relationships

One `ExpansionHandler` protocol covers Class, Trait, and Object targets.
Target kind is descriptive and handler applicability is decided inside
`expand`, independently of primary, companion, and sibling relationships.
Sparse Merge/Replace/Create/Delete operations and raw exact replacement share
one current-tree scheduler; stacked and generated handled annotations require
no policy opt-in. See [Expansion model and scheduling](EXPANSION_MODEL_AND_COMPOSITION.md).
