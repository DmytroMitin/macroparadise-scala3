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

All projects remain unpublished.

## External handler flow

An external marker carries runtime metadata naming an already compiled handler:

```text
marker class metadata
  -> exact syntactic annotation identity
  -> immutable handler descriptor and metadata binding
  -> parent-first handler class loader
  -> one ExpansionInput
  -> one ExpansionOutcome
  -> plugin-owned output validation
```

The handler contract exposes raw untyped Dotty trees and a small helper layer.
The helpers reduce repeated decoding and common class/companion/sibling
construction, but they do not make the boundary compiler independent.

## Input and output ownership

The plugin owns:

- annotation matching and target admission;
- handler discovery, loading, descriptor capture, and failure adaptation;
- companion leasing;
- source-ordered composition and handled-annotation closure;
- package conflicts, output validation, rollback, and diagnostics;
- final ordering and splicing before typer.

A handler owns its bounded transformation. It may use a decoded read-only class
view and return either:

- ordered raw replacement/output trees;
- a structured primary, optional companion, and ordered additional definitions;
- a rejection with diagnostics and a fallback;
- a not-applicable outcome, which is an error after matching and admission.

The plugin canonicalizes structured output as primary, companion, then
additional definitions. Raw output remains an expert escape hatch and still
passes structural validation.

## Class loading and exact compiler identity

Precompiled handlers load below a parent containing the self-contained plugin,
its exact unshaded embedded handler API, Scala runtime, and the one exact
compiler universe. The separate `plugin-api` artifact is an ordinary authoring
dependency, not a plugin-loader parent. The handler loader resolves the shared
interface and compiler types parent first. A child compiler copy would break
raw tree and context identity and is therefore rejected by the starter
precheck.

## Same-module boundary

A different-file clean-build prototype uses compiler-unit suspension to compile
a handler before resuming annotated consumers. It does not establish general
same-module support because the build tool does not know that handler
implementation changes must invalidate generated consumers. The supported
experimental architecture therefore keeps handlers precompiled.

See [Supported scope and limitations](SUPPORTED_SCOPE_AND_LIMITATIONS.md) and
[External handler authoring](EXTERNAL_HANDLER_AUTHORING.md). The separate
[quasiquote and pre-typer AST architecture](QUASIQUOTE_ARCHITECTURE.md)
distinguishes current typed quasiquotes from proposed neutral authoring and
hypothetical raw-untyped syntax.
