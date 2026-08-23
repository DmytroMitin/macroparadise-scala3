# Macro-Paradise for Scala 3

Macro-Paradise for Scala 3 is an experimental compiler-plugin project inspired
by Scala 2 Macro Paradise. It explores pre-typer annotation expansion so that
generated class members, companions, and sibling definitions are available to
ordinary Scala typing in the same compilation run.

The core mechanism and a precompiled external-handler path are executable and
well tested. The project is still compiler-sensitive research: its API,
configuration, supported shapes, and compatibility policy may change. The
immutable `0.1.0` release supports exact Scala `3.8.4` and is available
from Maven Central. Unreleased `main` is now qualified separately for exact
Scala `3.3.8` and `3.8.4` at development version `0.1.1-SNAPSHOT`.

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
- sbt 1.12.15;
- Scala `3.3.8` or `3.8.4`, selected as a separate exact build lane.

The build rejects other JDK feature versions before normal tasks run. The
plugin and handler contract expose Scala compiler internals, so a nearby Scala
version is not an interchangeable substitute.

### IntelliJ IDEA import

IntelliJ must launch **sbt itself** on JDK 25; setting only the source language
level or Scala SDK is insufficient. For this project, open
`Settings | Build, Execution, Deployment | Build Tools | sbt`, set
`JVM | JRE` to a JDK 25 installation, and also set the project SDK to JDK 25
under `File | Project Structure | Project`.

If an import command starts with a Java 8 executable such as
`.../corretto-1.8.../bin/java`, IntelliJ has selected the wrong sbt JVM.
Changing the Scala version is not the fix. Select JDK 25 in both locations,
then refresh or reimport the sbt project. An unsupported importer JVM is
rejected during meta-build settings load, before the ordinary `project/*.scala`
helpers compile.

Run the complete product gate from the repository root:

```sh
sbt -batch verifyPublicProductBoundary
```

The gate runs nonempty plugin and consumer suites, packages the plugin and
experimental handler contract, checks the normalized API surface, exercises
independent consumers, verifies the external-handler starter, and confirms
that only the two selected user artifacts are locally publishable while remote
publishing remains fail closed.

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

## Release and development installation

The immutable Central release and unreleased source checkout are separate
states. Released `0.1.0` is an exact Scala `3.8.4` artifact:

```scala
ThisBuild / scalaVersion := "3.8.4"
addCompilerPlugin(("com.github.dmytromitin" % "macroparadise-scala3-plugin" % "0.1.0").cross(CrossVersion.full))
```

To install unreleased `0.1.1-SNAPSHOT` from this checkout for one exact line,
select that line explicitly and publish only to the machine-local repository:

```sh
sbt -Dmacroparadise.exactScalaVersion=3.3.8 -batch "++3.3.8!" "pluginApi/publishLocal" "plugin/publishLocal"
sbt -Dmacroparadise.exactScalaVersion=3.8.4 -batch "++3.8.4!" "pluginApi/publishLocal" "plugin/publishLocal"
```

Then a local development consumer uses the matching exact line and snapshot:

```scala
ThisBuild / scalaVersion := "3.3.8" // or exact 3.8.4
addCompilerPlugin(("com.github.dmytromitin" % "macroparadise-scala3-plugin" % "0.1.1-SNAPSHOT").cross(CrossVersion.full))
```

`CrossVersion.full` is required; `%%` produces only a binary Scala suffix and
does not name this exact-compiler plugin. The new 3.3.8 line and snapshot
version are source-build/local-publication support only; no 0.1.1 artifact has
been published remotely.

The plugin JAR is self-contained for compiler loading: it embeds the exact
unshaded `paradise3.api` runtime classes that the plugin links against. Its POM
does not add a second runtime API dependency. An ordinary API dependency is a
source-compilation dependency for marker/handler authors; it is not, and need
not be, part of `-Xplugin`.

Authors of precompiled annotation markers and handlers also use the exact
full-cross `macroparadise-scala3-plugin-api` coordinate described in
[External handler authoring](docs/EXTERNAL_HANDLER_AUTHORING.md). Ordinary
plugin-only users do not add implementation or repository test artifacts.

The supported external-handler flow has three precompiled stages: marker,
handler, then annotated consumer. The plugin loader sees the self-contained
plugin JAR; the ordinary source classpath sees the API and precompiled marker;
the handler is selected through
`-P:macroparadise:handlerClasspath=<handler-jar>`. The running application does
not need the compiler plugin or handler JAR merely because they were used at
compile time. Same-compilation marker metadata is not currently claimed.

## External-handler starter

Start with the minimal user-defined `@identity` example. Its handler returns the
annotated class unchanged, so successful compilation isolates marker discovery,
metadata binding, imported-short canonicalization, handler loading, and one
invocation from generated-member behavior. The independent external sbt proof
also compiles the direct-qualified control and checks the exact marker/handler/
consumer dependency graph.

```sh
sbt -batch verifyIndependentExternalSbtConsumerFromLocalRepository
```

Then use the fixture-independent `generateGreeting` starter to prove generated-
member typing and runtime behavior on the same three-role topology. Its
packaged precheck retains the maximum-witness explicit form and a bounded compact
form without changing the experimental handler API.

```sh
sbt -batch verifyExternalHandlerAuthoringStarter
```

The copy/paste identity tutorial is in
[External handler authoring](docs/EXTERNAL_HANDLER_AUTHORING.md); the executable
[starter example](examples/external-handler-starter/README.md) is the generated-
output follow-on.

## Supported experimental boundary

The current implementation provides bounded evidence for:

- top-level class rewriting before typer;
- generated class methods;
- companion creation and existing-companion merge;
- generated sibling classes;
- structured primary, companion, and ordered additional-output roles;
- raw untyped output as an expert escape hatch;
- precompiled external handlers selected explicitly or by marker metadata;
- qualified syntactic annotation identities and unambiguous package-level
  explicit-import canonicalization;
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
- Annotation matching is syntactic. One unambiguous, source-preceding,
  package-level explicit import is supported; alias, wildcard, local/nested,
  given, export, symbol, and general semantic resolution are not implemented.
- General same-module handler support is deferred. A clean-build prototype
  exists, but handler-only changes do not reliably invalidate consumers through
  Zinc under the current design.
- The project does not provide arbitrary target shapes, arbitrary definition
  construction, arbitrary composition, typed tree construction, or a stable
  public API.
- Quasiquotes integration is optional cross-project research, not a product
  build dependency.
- Local publication is enabled only for the exact-cross plugin and handler API.
  Released `0.1.0` has no implied release cadence or production support
  commitment.

See [Supported scope and limitations](docs/SUPPORTED_SCOPE_AND_LIMITATIONS.md)
for the detailed boundary.

## Documentation

- [Getting started](docs/GETTING_STARTED.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Quasiquote and pre-typer AST architecture](docs/QUASIQUOTE_ARCHITECTURE.md)
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
stability guarantees. The exact Scala 3.8.4 plugin and plugin API are published
as `0.1.0`; the 0.1.1-SNAPSHOT/Scala 3.3.8 work is not published remotely.
Only the plugin and plugin API support `publishLocal`; internal fixtures,
tests, examples, consumers, and spikes remain unpublished.
