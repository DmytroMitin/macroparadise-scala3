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

No contributor license agreement, developer certificate, copyright assignment,
or contribution licensing policy is currently established. A repository
license has not yet been selected; license and contribution terms must be
resolved explicitly before a public contribution process is represented as
open.

For non-sensitive usage and defect-reporting guidance, see [Support](SUPPORT.md).
Do not post sensitive security material publicly; see [Security](SECURITY.md).
