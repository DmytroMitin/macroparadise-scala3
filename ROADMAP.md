# Product roadmap

This roadmap is organized by capability. It does not promise dates, release
cadence, compatibility duration, or remote artifact availability.

## Proven core compiler mechanism

- Preserve the pre-typer rewrite that makes generated definitions visible to
  ordinary typing in the same compilation run.
- Keep class-member, companion create/merge, sibling-definition, conflict,
  annotation-consumption, and rollback behavior executable.
- Keep plugin and consumer test discovery nonzero and retain the normalized
  experimental API surface gate.
- Keep every project unpublished until artifact publication is separately
  designed and authorized.

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

A different-file clean-build prototype demonstrates compiler-unit suspension
and resumed expansion. General production support remains deferred because the
current design does not register the implementation-sensitive dependency that
Zinc, sbt, and IDE/BSP workflows would need after a handler-only edit.

Revisit this area only when a design can prove consumer invalidation after
handler implementation changes and can provide command-line and IDE/BSP parity.
Precompiled handlers remain the supported experimental baseline.

## Optional quasiquotes research

Quasiquotes work may supply compiler-free construction models or exact-build
friend adapters for selected handler experiments. It remains independent,
optional research. The product build must not require another checkout, a
private exchange, or a peer artifact that is unavailable through the task's
explicit test setup.

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
