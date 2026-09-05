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

The same unreleased line adds
`RestrictedOrTwoUpperBoundedGenericTrait`, one closed profile that admits
exactly the existing `RestrictedGenericTraitApply` envelope or the existing
`TwoUpperBoundedGenericTrait` envelope. It does not broaden either constituent
profile and is not a profile set, combinator, predicate API, or arbitrary
composition mechanism. Representative zero-parameter, two-unbounded, mixed-
bound, constructor-parameter, class, case/sealed, context-bound, and variant
shapes remain outside this closed union.

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
and all admission checks succeed. Source-ordered handlers may use different
existing closed target profiles when the concrete target independently
satisfies every participant's profile. The coordinator does not synthesize an
intersection profile or expose profile algebra.

After each successful step, the plugin requires the returned primary to consume
the current annotation and preserve all later handled annotation objects in
their original identity, multiplicity, and order. A late failure restores the
original primary and companion and discards all earlier generated output.

The coordinator is structurally reusable, but positive evidence remains
bounded to combinations exercised by the test suite. No arbitrary stack or new
pair is implied.

## Same-module handlers

General production same-module support is deferred and remains false.

Unreleased `main` contains a separate no-trigger sbt plugin for one experimental
different-file Model A. The user must opt in, configure exactly one annotation
name and handler class, and identify exactly one marker source and one handler
source beneath a bounded source root. The integration hashes deterministic
label, normalized relative path, and exact source bytes into a distinct
`sameModuleSourceIdentity` compiler input. The compiler suspends consumers
before mutation, then loads the compiled handler from current output through a
fresh, correctly parented, closed child loader.

The current bounded scheduler matches the configured annotation against raw
consumer syntax before the normal precompiled-handler import canonicalization
step. A qualified same-module binding therefore uses the identical
direct-qualified consumer spelling; imported-short syntax is not currently a
same-module scheduling trigger.

Exact Scala 3.3.8, 3.8.4, and 3.9.0 clean/incremental CLI/Zinc qualification
passes on JDK 25 and sbt 1.12.15. Persistent sbt BSP and live sbt-delegated
IntelliJ handler-edit qualification remain bounded to exact 3.3.8 and 3.8.4.
The IntelliJ qualification includes baseline and no-op builds,
handler-only and consumer-only edits without `clean`, and close/reopen with a
fresh sbt session. Native IntelliJ/JPS compilation is not qualified. Same-file
marker/handler/consumer topologies, dependency cycles, automatic discovery,
source-root escapes, multiple configured relationships, and broader scheduling
remain rejected or unimplemented. See the experimental configuration in the
[source-built sbt integration guide](../sbt-integration/README.md).

Precompiled handlers remain the supported experimental path.

## Other deliberate exclusions

- stable API or cross-version raw-tree compatibility;
- JDK or Scala compatibility beyond the exact documented build;
- typed or symbol-aware handler construction;
- general owner or position repair;
- arbitrary local/nested/object/enum targets;
- general constructor, method, type, companion, or sibling synthesis;
- arbitrary `MemberDef` placement beyond the bounded exact `DefDef`/`ValDef`
  primary/companion batch, or semantic companion namespace resolution;
- position or source repair for caller-authored members; direct placement
  requires an insertion-ready `DefDef`/`ValDef` with a usable root source
  attachment or span and rejects a source-free member before target copying;
- semantic import/name resolution;
- automatic remote dependency or handler discovery;
- public artifact coordinates, tags, or releases;
- a production support or security SLA.

Quasiquotes adapters may appear in isolated tests, but the product build has no
dependency on another checkout and makes no general quasiquote integration
promise.
