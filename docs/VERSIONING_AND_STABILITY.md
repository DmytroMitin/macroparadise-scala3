# Versioning and stability

Macro-Paradise for Scala 3 has an experimental `0.1.0` release on Maven Central
for exact Scala `3.8.4`. The source build now uses `0.1.1-SNAPSHOT` and
separately qualifies exact Scala `3.3.8` and `3.8.4`.

The POM uses sbt's `early-semver` tooling hint so dependency tools treat the
pre-1.0 line conservatively. It does not upgrade the experimental API into a
stable compatibility promise.

## Current stability levels

- The pre-typer plugin mechanism is executable research and covered by a large
  regression suite.
- Built-in marker behavior is fixture-level product evidence, not a stable
  public annotation contract.
- The precompiled external-handler API is experimental and exposes exact
  compiler internals.
- Helper views and structured output reduce boilerplate but remain
  compiler-sensitive.
- Same-module handling is a deferred research prototype, not supported public
  behavior.
- The released coordinate does not imply a release cadence or maintained
  compatibility line.

The normalized handler-facing API inventory detects changes within the pinned
build. It does not promise source, binary, or behavioral compatibility.

The source is licensed under the [Apache License 2.0](../LICENSE). That license
grant is separate from API maturity: the handler-facing API remains
experimental, exact-compiler-specific, and without compatibility guarantees.

## Change expectations

For unreleased development after `0.1.0`:

- APIs, option names, tree contracts, supported shapes, diagnostics, and build
  structure may change;
- compiler-version changes require explicit requalification;
- no compatibility duration is promised for locally published snapshots;
- passing tests on another Scala line is evidence for that run only;
- documentation must distinguish proven fixtures from general support.

## Separate future decisions

These decisions must not be inferred from a passing source build:

- making source publicly visible;
- changing the selected artifact coordinates or exact full compiler crossing;
- adopting semantic or early-semantic versioning;
- publishing, signing, tagging, or supporting another release;
- declaring any handler API stable.

See the [Roadmap](../ROADMAP.md) for the bounded work that must precede those
decisions.
