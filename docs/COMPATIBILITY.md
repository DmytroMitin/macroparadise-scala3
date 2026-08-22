# Compatibility

## Required source-build toolchain

- JDK feature version 25
- sbt 1.12.15
- Scala `3.8.4`
- MUnit 1.2.4 for repository tests

The build uses compiler-internal APIs and a pre-typer research phase. JDK 25 is
enforced at sbt load, and the Scala version is pinned exactly. A different
nightly, release candidate, or stable compiler is a separate compatibility
experiment, not an automatic supported substitute.

## Plugin and handler compatibility

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

Version `0.1.0` and group `com.github.dmytromitin` identify the selected local
release candidate. Only `macroparadise-scala3-plugin` and
`macroparadise-scala3-plugin-api` set `publish / skip := false`, both with
`CrossVersion.full`; every aggregate, fixture, test, consumer, example, and
spike remains skipped. `publishLocal` is supported for those two artifacts.

No remote publication destination or credentials are configured, and ordinary
CI does not publish. The candidate is not available from Maven Central and is
not a released compatibility promise.

See [Versioning and stability](VERSIONING_AND_STABILITY.md) and
[Supported scope and limitations](SUPPORTED_SCOPE_AND_LIMITATIONS.md).
