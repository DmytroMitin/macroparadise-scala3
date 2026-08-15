# Contributing

Macro-Paradise for Scala 3 is exact-compiler experimental software. Discuss
substantial API, compiler-version, target-shape, composition, or build-graph
changes before investing in a large implementation.

## Development baseline

- JDK feature version 25
- sbt 1.12.8
- Scala `3.8.5-RC1-bin-20260405-9478256-NIGHTLY`

Run the canonical product gate before proposing a change:

```sh
sbt -batch verifyPublicProductBoundary
scripts/verify-public-product-fresh-copy.sh
git diff --check
```

For changes to external-handler guidance or fixtures, also run:

```sh
sbt -batch verifyExternalHandlerAuthoringStarter
```

Keep changes narrow and test backed. Update public documentation when behavior,
dependencies, build steps, supported shapes, diagnostics, or compatibility
expectations change. Do not add credentials, generated build output,
machine-local paths, or references to nonpublic development infrastructure.

The plugin must continue to fail closed on unsupported targets, malformed
handler results, and invalid composition. Do not weaken exact JDK/compiler
checks or enable publishing as part of an unrelated contribution.

The source is licensed under the [Apache License 2.0](LICENSE). Unless stated
otherwise, contributions intentionally submitted for inclusion are accepted
under the contribution terms in that license. No separate contributor license
agreement, developer certificate, or copyright assignment is currently
required. This does not make the experimental API stable or enable artifact
publishing.

For non-sensitive usage and defect-reporting guidance, see [Support](SUPPORT.md).
Do not post sensitive security material publicly; see [Security](SECURITY.md).
