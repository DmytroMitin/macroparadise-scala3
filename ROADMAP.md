# Product roadmap

This roadmap is organized by capability. Released `0.1.1` is available for
exact Scala `3.3.8`, `3.8.4`, and `3.9.0`; current `main` is
`0.2.0-SNAPSHOT`. The roadmap does not promise dates, a `0.2.0` release,
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
- Retain plugin-owned current-staged-tree scheduling with no composition switch:
  after every committed stage, validate and rescan deterministically from the
  current trees. Annotation-specific target and shape applicability belongs in
  `ExpansionHandler.expand`; MacroParadise owns framework-level syntactic
  target eligibility. No public admission/profile metadata is required.


## Usability and compatibility hardening

- Improve actionable diagnostics for loading, metadata binding, handler-local
  applicability rejection, malformed output, composition, and exact-toolchain
  failures.
- Keep the source-build guide, starter, compact public documentation, and
  relative links executable and free of machine-local assumptions.
- Requalify compiler and JDK changes as explicit compatibility work; do not
  silently widen the supported toolchain.
- Continue separating public authoring guidance from internal research and
  release evidence.

## Same-module handler status

A bounded different-file implementation established a working lifecycle for
explicit handler-source mapping, content-derived incremental identity,
compiler-unit suspension, and resumed expansion across clean and incremental
CLI/Zinc/BSP builds. Exact 3.3.8 and 3.8.4 also have sbt-delegated IntelliJ
qualification. General production support remains deferred outside this
enumerated Model A.

Consumer invalidation after handler implementation changes is retained in the
bounded qualification. Same-file handlers, dependency cycles, automatic
discovery, multiple relationships, and native IntelliJ/JPS compilation remain
outside the established design; precompiled handlers remain the broad/default
experimental baseline.

## Generic sbt integration

The first opt-in precompiled-handler slice is implemented and published as
`sbt-macroparadise` `0.1.1`. It:

- select exact full-cross Macro-Paradise plugin and API coordinates;
- identifies and packages explicit marker and handler projects before consumer
  compilation while keeping `.dependsOn(marker)` explicit;
- derives content identity from all explicit marker-role artifacts and the
  complete ordered effective handler expansion classpath;
- install the handler classpath and build-only identity compiler options;
- is qualified in clean and incremental CLI/Zinc builds on all three exact
  Scala lines, with bounded persistent BSP and sbt-delegated IntelliJ evidence
  on exact 3.3.8 and 3.8.4;
- retain inspectable manual settings as overrides and an escape hatch;
- retains a separate bounded same-module source-identity lifecycle without
  broadening the default precompiled-handler contract.

User onboarding permanently retains two producer topologies crossed with two
wiring styles: four quadrants. The sbt integration and manual wiring both
support same-build local marker/handler projects without producer
`publishLocal`, and both support genuinely published or resolver-installed
marker/handler modules. The manual quadrants use the copied build-definition
identity helper. Public examples must use explicit project locations when
directory names are hyphenated. The integration plugin is remotely published
as `0.1.1`; current `0.2.0-SNAPSHOT` development remains source-built/local-only.
Manual wiring remains an inspectable escape hatch. A downstream project may
provide application-specific conveniences; compiler/plugin behavior remains in
the product API rather than in sbt.


## Embedded producer frontend

The current source-built `0.2.0-SNAPSHOT` embedded frontend is accepted as a
documented precompiled authoring style. Public documentation and the
product-owned starter cover the complete four-quadrant setup: same-build and
resolver-installed producer roles, each with sbt integration and complete
manual consumer wiring.

Maintain these boundaries:

- keep the declaration grammar and existing handler protocol unchanged;
- retain separate marker and handler roles with complete handler-closure
  identity;
- keep the marker policy at exact `CrossVersion.full`;
- keep external marker-plus-handler authoring first-class;
- keep same-module embedded declaration/use unsupported;
- do not describe the frontend as remotely released until a separately
  authorized release exists.

A future released template may adopt the embedded style only through separate
release work. The immutable `0.1.1` template remains an external-handler
example.

## Next public-contract work

Current `0.2.0-SNAPSHOT` has one orthogonal handler contract for class, trait,
and object primaries. Applicability is ordinary handler code in `expand`; there
is no public admission/profile declaration. Invocation inputs and container
context are plugin-minted read-only values, the current annotation occurrence
is available as a raw tree, and occupied enclosing-definition names are exposed
honestly. Structured primary/companion/sibling changes run through the staged
transactional scheduler with configurable runaway protection, recursive owned-
tree alias checks, and provenance checks. The current unreleased `0.2.0-SNAPSHOT` source line
also exposes immutable scheduler provenance through
`ExpansionInput.sourceOrderedHandledAnnotationNames: List[String]`: handled
canonical annotation identities in annotation-list order, with duplicates
preserved. A preserved physical annotation occurrence keeps its first cohort;
a fresh generated or reconstructed occurrence receives the cohort from the
first visible staged revision in which it appears. This is scheduler provenance,
not an admission or composition-policy declaration. See
[Expansion model and composition](docs/EXPANSION_MODEL_AND_COMPOSITION.md) for
the current topology and verification boundary.

This is an unreleased API-freeze candidate. Downstream AUXify migration remains
outside this repository and requires separate controller acceptance; passing
the product gates does not authorize that migration or a `0.2.0` release.

Later bounded public U-style existing-definition transformation authoring is a
separate track. Neither capability is part of released `0.1.1`, and this
roadmap does not promise a `0.2.0` release.

## Optional quasiquotes research

[Quasiquotes for Scala 3](https://github.com/DmytroMitin/quasiquotes-scala3)
supplies a Scalameta-based compiler-neutral authoring model and exact-build
lowering experiments. [AUXify for Scala 3](https://github.com/DmytroMitin/AUXify-scala3)
is an independent downstream consumer of that layering with Macro-Paradise.
Both remain optional related projects. The product build must not require
another checkout or an unavailable peer artifact.

## Open-source, publication, and stability work

The source license is Apache License 2.0. Public source visibility, artifact
publication, and API stability remain separate facts. The existing `0.1.1`
release does not authorize another release.

Before any future artifact publication:

- choose supported coordinates and compiler-crossing rules;
- define signing, provenance, source/documentation artifacts, and failure
  recovery;
- verify clean coordinate-only consumers;
- choose a versioning policy appropriate to the experimental API.

Passing the current source gates does not complete any of those later steps.
