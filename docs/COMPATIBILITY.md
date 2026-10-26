# Compatibility

## Required source-build toolchain

- JDK feature version 25
- sbt 1.12.15
- Scala `3.3.8`, `3.8.4`, or stable `3.9.0`, selected and tested as separate exact lines
- MUnit 1.2.4 for repository tests

The build uses compiler-internal APIs and a pre-typer research phase. JDK 25 is
enforced at sbt load, and each Scala lane is pinned exactly. Binary-crossed or
nearby compiler versions are not substitutes. The [mode and parity guide](AUTHORING_MODES_AND_FEATURE_PARITY.md)
records the evidence envelope separately for each authoring/topology cell.

## IntelliJ and build import

IntelliJ must import the build with sbt running on JDK 25. Packaged external-
handler projects should delegate build and run actions to sbt so the exact
plugin options, handler packaging dependency, and content identity are retained.
This sbt-imported workflow is the qualified IDE path. Native JPS compilation is
not currently qualified.

On current source-built `0.2.0-SNAPSHOT`, the bounded same-module embedded singleton
is qualified on exact Scala 3.3.8, 3.8.4, and 3.9.0 when the project is imported
through sbt, import and Build are delegated to the sbt shell, and `run` is
submitted in that same IntelliJ sbt shell. The sbt JVM must be JDK 25. A direct
Java gutter Run and native JPS are not qualified. A disposable fixture's
Corretto 21 project SDK was an observation, not evidence for JDK 21 compiler
execution. The separate external-handler Model A retains its previously
qualified exact 3.3.8 and 3.8.4 IntelliJ envelope.

The sbt integration, published as `0.1.1` and source-built for current
development, has real persistent sbt BSP evidence for the precompiled path and
external-handler Model A on exact Scala `3.3.8` and `3.8.4`, sbt 1.12.15, and
JDK 25. Exact 3.9.0 has CLI/Zinc and ordinary sbt qualification, but no retained-
process BSP claim for those paths. Their retained process covers no-op
compilation, handler-only, handler-dependency-only, marker-metadata-only, and
consumer-only edits, followed by stale-handler failure and repaired compilation
without `clean`.

The same-module embedded singleton and exact-two independent pair have accepted
persistent sbt BSP evidence on exact Scala 3.8.4. Both have all-line CLI/Zinc
evidence; only the singleton has accepted all-line IntelliJ sbt-shell evidence.
The exact-two embedded pair has no live IntelliJ, native JPS, or direct Java
gutter Run qualification. Its four-file topology co-locates `Main` in one
consumer; a separate fifth initially-unsuspended source remains unqualified.

The exact-two independent external same-module pair on source-built
`0.2.0-SNAPSHOT` is separately qualified through CLI/Zinc on exact Scala 3.3.8,
3.8.4, and 3.9.0 plus persistent sbt BSP 3.8.4. Singleton Model A IntelliJ
evidence does not transfer to the pair: live pair IntelliJ, native JPS, and
direct Java gutter Run remain unqualified. Released `0.1.1` has no pair mode.

## Plugin and handler compatibility

The sbt integration itself runs in the sbt 1.x / Scala 2.12
universe and does not depend on the Dotty runtime. It selects the product
artifacts for the consumer's exact Scala line with `CrossVersion.full`.

The plugin, handler API, precompiled handlers, and ordinary consumer compilation
must share one exact compiler universe. Handler class loading is parent first
for compiler, Scala runtime, and handler API identities. A second compiler copy
in the handler class loader is unsupported.

Raw `untpd` constructors, context behavior, tree shapes, source positions, and
binary descriptors may change between compiler builds. The experimental
handler API therefore makes no cross-version binary or source compatibility
promise.

Current `0.2.0-SNAPSHOT` exposes bounded syntactic target, body, and
type-structure views. They report source shape only: no typing, symbol/owner
lookup, inheritance, alias expansion, subtyping, or overload analysis occurs.
Raw `ExpansionInput.primary.tree` is the advanced exact-line escape hatch.

The 0.2.0-SNAPSHOT protocol is intentionally source-breaking from 0.1.1. It has
one two-method `ExpansionHandler`, descriptive target kinds with applicability
inside `expand`, generic member placement, sparse structured changes, and exact
zero-or-more raw replacement. Invocation values are plugin-minted read-only
objects without public construction or copy semantics.
Old handler binaries must be rebuilt and migrated; no protocol compatibility
shim is provided. Released 0.1.1 artifacts themselves remain immutable.

## Marker metadata compatibility

Current markers use a runtime-retained classfile annotation to name the handler.
The build retains bounded compatibility fixtures for older marker metadata
produced by selected Scala lines. Those fixtures are compatibility evidence for
marker discovery only; they do not make old handler binaries compatible with
the current compiler.

## API baseline

The repository maintains a normalized exact-build inventory for the
experimental handler-facing surface. The canonical product gate rejects
unreviewed drift from that inventory. It is a change detector, not a stable API
or semantic-versioning commitment.

## Publication

Version `0.1.1` is the immutable Maven Central release and supports exact Scala
`3.3.8`, `3.8.4`, and stable `3.9.0`. Current `main` uses
`0.2.0-SNAPSHOT` for the same separately qualified exact lines. Only
`macroparadise-scala3-plugin` and
`macroparadise-scala3-plugin-api` set `publish / skip := false`, both with
`CrossVersion.full`; every aggregate, fixture, test, consumer, example, and
spike remains skipped. `publishLocal` is supported for those two artifacts.
The `sbt-integration` module is additionally published as
`sbt-macroparadise` `0.1.1`; its current `0.2.0-SNAPSHOT` source build has no
configured remote publication destination or credentials.

No remote publication destination or credentials are configured for ordinary
development/CI, and this requalification does not publish `0.2.0-SNAPSHOT`.

See [Versioning and stability](VERSIONING_AND_STABILITY.md) and
[Supported scope and limitations](SUPPORTED_SCOPE_AND_LIMITATIONS.md).
