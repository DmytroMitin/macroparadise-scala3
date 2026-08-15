# Macro-Paradise for Scala 3

Macro-Paradise for Scala 3 is an experimental compiler-plugin project inspired
by Scala 2 Macro Paradise. It explores pre-typer annotation expansion so that
generated class members, companions, and sibling definitions are available to
ordinary Scala typing in the same compilation run.

The core mechanism and a precompiled external-handler path are executable and
well tested. The project is still compiler-sensitive research: its API,
configuration, supported shapes, and compatibility policy may change, and no
artifact is available from a remote package repository.

## A small example

The built-in `@gen` fixture demonstrates the mechanism:

```scala
import paradise3.gen

@gen
class User(val name: String)

val greeting = new User("Ada").generatedHello
val copy = User.generatedFactory("Grace")
val metadata = new UserMeta
```

Before ordinary typing, the plugin rewrites the annotated class, creates or
merges its companion, and adds the sibling `UserMeta`. The generated members
are then typechecked like ordinary source definitions.

This fixture is intentionally narrow. It is evidence for the compiler
mechanism, not a general-purpose macro-annotation API.

## Exact toolchain

The source build requires:

- JDK feature version 25;
- sbt 1.12.8;
- Scala `3.8.5-RC1-bin-20260405-9478256-NIGHTLY`.

The build rejects other JDK feature versions before normal tasks run. The
plugin and handler contract expose Scala compiler internals, so a nearby Scala
version is not an interchangeable substitute.

Run the complete product gate from the repository root:

```sh
sbt -batch verifyPublicProductBoundary
```

The gate runs nonempty plugin and consumer suites, packages the plugin and
experimental handler contract, checks the normalized API surface, exercises
independent consumers, verifies the external-handler starter, and confirms
that publishing remains disabled.

For an additional product-only isolation proof, run the same canonical gate in
a disposable source copy with fresh dependency and build caches:

```sh
scripts/verify-public-product-fresh-copy.sh
```

This companion gate copies only tracked and task-owned untracked product files;
it does not read a controller checkout or reuse repository build output.

For a smaller ordinary development pass:

```sh
sbt -batch test
```

## External-handler starter

The fixture-independent starter separates a marker, a precompiled handler, and
an ordinary consumer. It verifies qualified annotation identity, metadata
binding, exact compiler/JDK compatibility, class-loader identity, dependency
boundaries, generated-member typing, runtime behavior, and a fail-closed
precheck matrix.

```sh
sbt -batch verifyExternalHandlerAuthoringStarter
```

Start with [External handler authoring](docs/EXTERNAL_HANDLER_AUTHORING.md) and
the executable [starter example](examples/external-handler-starter/README.md).

## Supported experimental boundary

The current implementation provides bounded evidence for:

- top-level class rewriting before typer;
- generated class methods;
- companion creation and existing-companion merge;
- generated sibling classes;
- structured primary, companion, and ordered additional-output roles;
- raw untyped output as an expert escape hatch;
- precompiled external handlers selected explicitly or by marker metadata;
- qualified syntactic annotation identities;
- plugin-owned, source-ordered composition for handlers that explicitly opt in;
- a restricted, opt-in generic-trait target profile used by one contextual
  companion-method fixture.

Composition is fail closed. Every participant must opt in, share the same
target profile, preserve remaining handled annotations exactly, and satisfy
the plugin's output and rollback invariants. The coordinator is generic, but
positive evidence remains bounded to the combinations in the test suite.

## Important limitations

- External handlers and their raw tree values are tied to the exact compiler
  build.
- Qualified annotation matching is syntactic. General import, alias, wildcard,
  symbol, and semantic type resolution are not implemented.
- General same-module handler support is deferred. A clean-build prototype
  exists, but handler-only changes do not reliably invalidate consumers through
  Zinc under the current design.
- The project does not provide arbitrary target shapes, arbitrary definition
  construction, arbitrary composition, typed tree construction, or a stable
  public API.
- Quasiquotes integration is optional cross-project research, not a product
  build dependency.
- Publishing is disabled. There are no supported remote coordinates, release
  cadence, or production support commitment.

See [Supported scope and limitations](docs/SUPPORTED_SCOPE_AND_LIMITATIONS.md)
for the detailed boundary.

## Documentation

- [Getting started](docs/GETTING_STARTED.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Supported scope and limitations](docs/SUPPORTED_SCOPE_AND_LIMITATIONS.md)
- [External handler authoring](docs/EXTERNAL_HANDLER_AUTHORING.md)
- [Diagnostics and troubleshooting](docs/DIAGNOSTICS.md)
- [Compatibility](docs/COMPATIBILITY.md)
- [Versioning and stability](docs/VERSIONING_AND_STABILITY.md)
- [Roadmap](ROADMAP.md)

## Contributing, support, and security

Before proposing a change, read [Contributing](CONTRIBUTING.md). For usage
questions and defect reports, see [Support](SUPPORT.md). Security-sensitive
material must not be posted publicly; the current reporting limitation is
explained in the [Security policy](SECURITY.md).

## License and publication status

The source is licensed under the [Apache License 2.0](LICENSE). The plugin and
handler-facing API remain experimental, compiler-version-specific, and without
stability guarantees. No plugin or handler-contract artifact is published
remotely, and all build projects remain configured with publishing disabled.
