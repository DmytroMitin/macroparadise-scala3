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

The plugin remains the authority for every syntactic admission rule and
diagnostic. Annotation names and handler class names do not grant broader
admission.

## Annotation identity and imports

External handler identity accepts either a retained simple name or an exact
dot-qualified syntactic name. New handlers should prefer a qualified name.

```scala
@starter.marker.generateGreeting
class Greeter
```

The plugin reads raw pre-typer identifier/select chains. It does not resolve:

- imported simple names;
- renamed imports or aliases;
- wildcard imports;
- symbols or semantic package identity;
- semantic types.

A qualified source name never falls back to its final segment. An ambiguous
simple name fails closed.

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

A clean/full compiler run can suspend different-file consumers, compile an
explicitly related handler source, load its output, and resume the consumers.
The current design does not give Zinc an implementation-sensitive dependency
from the handler body to generated consumers. A handler-only edit can therefore
leave consumers stale. Same-file handlers, dependency cycles, automatic
discovery, multiple configured relationships, and IDE/BSP invalidation parity
are not supported.

Precompiled handlers remain the supported experimental path.

## Other deliberate exclusions

- stable API or cross-version raw-tree compatibility;
- JDK or Scala compatibility beyond the exact documented build;
- typed or symbol-aware handler construction;
- general owner or position repair;
- arbitrary local/nested/object/enum targets;
- general constructor, method, companion, or sibling synthesis;
- semantic import/name resolution;
- automatic remote dependency or handler discovery;
- public artifact coordinates, tags, or releases;
- a production support or security SLA.

Quasiquotes adapters may appear in isolated tests, but the product build has no
dependency on another checkout and makes no general quasiquote integration
promise.
