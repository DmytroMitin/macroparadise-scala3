# Compatibility

## Required source-build toolchain

- JDK feature version 25
- sbt 1.12.15
- Scala `3.3.8` or `3.8.4`, selected and tested as separate exact lines
- MUnit 1.2.4 for repository tests

The build uses compiler-internal APIs and a pre-typer research phase. JDK 25 is
enforced at sbt load, and each Scala lane is pinned exactly. Binary-crossed or
nearby compiler versions are not substitutes.

## IntelliJ and build import

IntelliJ must import the build with sbt running on JDK 25. Packaged external-
handler projects should delegate build and run actions to sbt so the exact
plugin options, handler packaging dependency, and content identity are retained.
This sbt-imported workflow is the qualified IDE path. Native JPS compilation is
not currently qualified, and live same-module handler editing is not supported.

The separate source-built, unreleased sbt integration is also qualified through
real persistent sbt BSP sessions for exact Scala `3.3.8` and `3.8.4`, sbt
1.12.15, and JDK 25. One process is retained across no-op compilation,
handler-only, handler-dependency-only, marker-metadata-only, and consumer-only
edits, followed by stale-handler failure and repaired compilation without
`clean`. This BSP qualification does not extend to native JPS, same-module
handler authoring, or a remotely published sbt integration coordinate.

## Plugin and handler compatibility

The source-built sbt integration itself runs in the sbt 1.x / Scala 2.12
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

Version `0.1.0` is the immutable Maven Central release and supports exact Scala
`3.8.4` only. Unreleased `main` uses `0.1.1-SNAPSHOT` and separately qualifies
exact Scala `3.3.8` and `3.8.4`. Only `macroparadise-scala3-plugin` and
`macroparadise-scala3-plugin-api` set `publish / skip := false`, both with
`CrossVersion.full`; every aggregate, fixture, test, consumer, example, and
spike remains skipped. `publishLocal` is supported for those two artifacts.
The `sbt-integration` module is an additional source-built, unreleased surface;
it has no configured remote publication destination or credentials.

No remote publication destination or credentials are configured for ordinary
development/CI, and this requalification does not publish `0.1.1-SNAPSHOT`.

See [Versioning and stability](VERSIONING_AND_STABILITY.md) and
[Supported scope and limitations](SUPPORTED_SCOPE_AND_LIMITATIONS.md).
