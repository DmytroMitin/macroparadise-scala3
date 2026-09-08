# Architecture

## Compiler strategy

The project uses a Scala 3 standard compiler plugin whose custom phase is
scheduled after `parser` and before `typer`. The phase rewrites untyped compiler
trees and replaces the compilation-unit tree before ordinary typing continues.

```text
source parsing
  -> pre-typer annotation discovery and target admission
  -> built-in or precompiled handler expansion
  -> output validation and transactional composition
  -> package-stat replacement
  -> ordinary typer and later compiler phases
```

Pre-typer placement is the central mechanism. Generated class members,
companions, and sibling definitions exist before user-written references are
typed.

## Main modules

- `plugin-api` contains the experimental shared handler contract and marker
  metadata carrier.
- `plugin` contains the compiler plugin, handler loading, admission,
  orchestration, validation, diagnostics, and preconsumer checker.
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

- annotation matching and target admission;
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

Released `0.1.1` and current `main` include one bounded different-file Model A
behind a separate opt-in sbt plugin. The build names one marker source and one handler
source beneath an explicit source root, hashes their exact bytes into a
same-module compiler-input identity, suspends consumers before mutation, and
resumes them with freshly compiled current output through a fresh child loader.
Exact Scala 3.3.8, 3.8.4, and 3.9.0 CLI/Zinc qualification passes. Persistent
sbt BSP qualification remains bounded to exact 3.3.8 and 3.8.4.

This is bounded experimental support, not general support. Live IntelliJ
qualification passes on exact 3.3.8 and 3.8.4 only for an sbt-imported project with
Build and Run delegated to sbt on JDK 25 and sbt 1.12.15, including
handler-only edits without `clean`. Native JPS, same-file
marker/handler/consumer topologies, dependency cycles, automatic discovery,
and multiple configured relationships remain unqualified, rejected, or
unimplemented. Precompiled handlers remain the broad/default supported
experimental architecture.

See [Supported scope and limitations](SUPPORTED_SCOPE_AND_LIMITATIONS.md) and
[External handler authoring](EXTERNAL_HANDLER_AUTHORING.md). The separate
[quasiquote and pre-typer AST architecture](QUASIQUOTE_ARCHITECTURE.md)
distinguishes current typed quasiquotes from proposed neutral authoring and
hypothetical raw-untyped syntax.

## Orthogonal targets and relationships

One `ExpansionHandler` protocol covers Class, Trait, and Object targets.
Target-kind admissions are independent of the primary, companion, and sibling
relationships. Sparse Merge/Replace/Create/Delete operations and raw exact
replacement share one current-tree scheduler; stacked and generated handled
annotations require no policy opt-in. See [Expansion model and scheduling](EXPANSION_MODEL_AND_COMPOSITION.md).
