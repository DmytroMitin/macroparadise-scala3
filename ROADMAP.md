# Product roadmap

This roadmap is organized by capability. Released `0.1.0` is available for
exact Scala `3.8.4`; the roadmap does not promise dates, another release,
release cadence, or compatibility duration.

## Proven core compiler mechanism

- Preserve the pre-typer rewrite that makes generated definitions visible to
  ordinary typing in the same compilation run.
- Keep class-member, companion create/merge, sibling-definition, conflict,
  annotation-consumption, and rollback behavior executable.
- Keep plugin and consumer test discovery nonzero and retain the normalized
  experimental API surface gate.
- Keep remote publication limited to separately designed and authorized
  releases; ordinary development and CI remain non-publishing.

## Experimental external-handler authoring and runtime

- Maintain the independent marker/handler/consumer starter and its preconsumer
  diagnostic matrix.
- Reduce common handler boilerplate without hiding the compiler-sensitive raw
  tree boundary.
- Preserve exact qualified syntactic annotation identity and fail closed on
  ambiguous simple identities.
- Expand supported target and output shapes only through bounded,
  independently tested contracts.
- Keep source-ordered composition opt-in, transactional, and backed by concrete
  positive combinations rather than broad structural claims.

## Usability and compatibility hardening

- Improve actionable diagnostics for loading, metadata binding, target
  admission, malformed output, composition, and exact-toolchain failures.
- Keep the source-build guide, starter, compact public documentation, and
  relative links executable and free of machine-local assumptions.
- Requalify compiler and JDK changes as explicit compatibility work; do not
  silently widen the supported toolchain.
- Continue separating public authoring guidance from internal research and
  release evidence.

## Same-module handler status

A bounded different-file design probe established a feasible lifecycle for
explicit handler-source mapping, content-derived incremental identity,
compiler-unit suspension, and resumed expansion across clean and incremental
CLI/Zinc/BSP builds. General production support remains deferred because that
lifecycle is not implemented or product-qualified.

Future implementation must prove consumer invalidation after handler
implementation changes. Same-file handlers, dependency cycles, automatic
discovery, and live IntelliJ behavior remain outside the established design;
precompiled handlers remain the supported experimental baseline.

## Generic sbt integration

Plan a separately reviewed sbt integration plugin that can turn the current
manual three-project wiring into an explicit, reusable build contract. A
credible design must:

- select exact full-cross Macro-Paradise plugin and API coordinates;
- identify and package marker and handler projects before consumer compilation;
- derive content identity from the packaged marker and handler rather than from
  stable paths or timestamps;
- install the handler classpath and build-only identity compiler options;
- behave consistently in CLI, BSP, and sbt-delegated IntelliJ builds;
- retain inspectable manual settings as overrides and an escape hatch;
- leave room for the bounded same-module source-identity lifecycle only after
  the precompiled-handler form is proven.

No coordinate, setting key, implementation, or release is promised yet. The
current manual wiring remains authoritative. A downstream project may provide
application-specific conveniences; the generic layer should belong here, while
compiler/plugin behavior remains in the product API rather than in sbt.

## Optional quasiquotes research

[Quasiquotes for Scala 3](https://github.com/DmytroMitin/quasiquotes-scala3)
supplies a Scalameta-based compiler-neutral authoring model and exact-build
lowering experiments. [AUXify for Scala 3](https://github.com/DmytroMitin/AUXify-scala3)
is an independent downstream consumer of that layering with Macro-Paradise.
Both remain optional related projects. The product build must not require
another checkout or an unavailable peer artifact.

## Open-source, publication, and stability work

The source license is Apache License 2.0. Public source visibility, artifact
publication, and API stability remain separate decisions.

Before any public visibility change:

- retain and verify the complete Apache License 2.0 text and build metadata;
- preserve any required private lineage outside the public source surface;
- rehearse and independently inspect a sanitized source snapshot;
- confirm governance and security wording remains truthful.

Before any artifact publication:

- choose supported coordinates and compiler-crossing rules;
- define signing, provenance, source/documentation artifacts, and failure
  recovery;
- verify clean coordinate-only consumers;
- choose a versioning policy appropriate to the experimental API.

Passing the current source gates does not complete any of those later steps.
