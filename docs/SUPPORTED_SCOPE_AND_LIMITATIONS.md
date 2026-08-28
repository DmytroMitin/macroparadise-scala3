# Supported scope and limitations

Macro-Paradise for Scala 3 is a bounded compiler experiment, not a complete
replacement for Scala 2 Macro Paradise or a stable general macro-annotation
framework.

## Proven output shapes

Current executable fixtures cover:

- a rewritten top-level class with a generated method;
- a created companion object;
- an existing following companion whose unrelated members are preserved;
- user-defined direct members winning over same-name generated members;
- a generated sibling class;
- a structured primary, optional companion, and ordered additional top-level
  definitions;
- low-level ordered raw output under plugin-owned validation;
- atomic rollback after rejection, exception, linkage failure, malformed
  output, conflict, or failed later composition step.

The built-in `@gen` fixture has a stricter constructor contract than the common
external class profile. It is not evidence for arbitrary class parameters or
general definition generation.

## Target profiles

External handlers default to `CommonClassOnly`, the bounded non-case,
non-generic top-level class profile.

One opt-in `RestrictedGenericTraitApply` profile admits exactly one top-level,
non-case, non-sealed trait with one invariant ordinary unbounded type parameter,
no context bound, and no constructor/value parameters. It exists for a narrow
contextual companion-method experiment; it is not general trait or generic
support.

Unreleased `0.1.1-SNAPSHOT` adds a separate
`TwoUpperBoundedGenericTrait` profile. It admits only an ordinary top-level,
non-case, non-sealed trait with exactly two invariant ordinary single
upper-bounded type parameters, no lower/alias/context bounds, and no
constructor/value parameters. The profile is purely structural: it does not
inspect direct body features or implement downstream annotation semantics.

The plugin remains the authority for every syntactic admission rule and
diagnostic. Annotation names and handler class names do not grant broader
admission.

## Annotation identity and imports

External handler identity accepts a retained legacy simple name, an exact
dot-qualified syntactic name, or the canonical identity witnessed by one
unambiguous, source-preceding explicit import at package scope. New handlers
should declare a qualified name.

```scala
import a.b.identity

@identity
class Something
```

Before typer, the plugin canonicalizes this occurrence to
`a.b.identity`. Metadata lookup, handler binding, composition, and normalized
handler input use that canonical identity while the raw annotation tree and
source position remain unchanged. Direct `@a.b.identity` syntax remains
supported as a control or fallback.

Two explicit imports that provide the same short annotation name fail with a
deterministic ambiguity diagnostic listing the canonical candidates. The
plugin does not implement or infer:

- renamed imports or aliases;
- wildcard imports;
- local or nested imports;
- given imports or exports;
- shadowing-dependent semantics;
- package-object semantics;
- symbols, semantic package identity, or semantic annotation/type aliases.

A qualified source name never falls back to its final segment. An unwitnessed
short name retains the previous bounded behavior and is never assigned a
guessed package. The reserved `paradise3` compatibility namespace retains its
established simple-identity import behavior. Existing external handlers whose
captured descriptors are simple also retain that legacy identity; the imported
canonical form is selected only when marker metadata selects a handler that
declares the matching qualified identity.

## Composition

Handlers default to `StandaloneOnly`. A handler may opt into `SourceOrdered`,
but a multi-annotation stack is admitted only when every participant opts in,
all participants share one target profile, and all admission checks succeed.

After each successful step, the plugin requires the returned primary to consume
the current annotation and preserve all later handled annotation objects in
their original identity, multiplicity, and order. A late failure restores the
original primary and companion and discards all earlier generated output.

The coordinator is structurally reusable, but positive evidence remains
bounded to combinations exercised by the test suite. No arbitrary stack or new
pair is implied.

## Same-module handlers

General production same-module support is deferred.

A bounded design probe established a feasible lifecycle for explicitly mapped
different-file handler sources: package the selected source, derive content
identity for incremental invalidation, suspend dependent compiler units, load
the compiled handler, and resume consumers. The design is intended to preserve
clean and incremental CLI/Zinc/BSP behavior, but it is not implemented or
supported today. Same-file handlers, dependency cycles, automatic discovery,
multiple configured relationships, and live IntelliJ behavior remain outside
the established boundary.

Precompiled handlers remain the supported experimental path.

## Other deliberate exclusions

- stable API or cross-version raw-tree compatibility;
- JDK or Scala compatibility beyond the exact documented build;
- typed or symbol-aware handler construction;
- general owner or position repair;
- arbitrary local/nested/object/enum targets;
- general constructor, method, type, companion, or sibling synthesis;
- arbitrary `MemberDef` placement or semantic companion namespace resolution;
- semantic import/name resolution;
- automatic remote dependency or handler discovery;
- public artifact coordinates, tags, or releases;
- a production support or security SLA.

Quasiquotes adapters may appear in isolated tests, but the product build has no
dependency on another checkout and makes no general quasiquote integration
promise.
