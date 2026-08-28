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

## A small user-authored example

A user can define an annotation marker, its precompiled handler, and an
annotated consumer. The marker carries only the handler identity:

```scala
package com.example.`macro`.annotations

import paradise3.api.expander
import scala.annotation.StaticAnnotation

@expander("com.example.macro.handlers.GenHandler")
final class gen extends StaticAnnotation
```

The handler uses the public experimental helper API:

```scala
package com.example.`macro`.handlers

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.{ExpansionInput, ExpansionOutcome, ParadiseAnnotationExpander}
import paradise3.api.helpers.ExpansionHelpers

final class GenHandler extends ParadiseAnnotationExpander:
  override def annotationName: String =
    "com.example.macro.annotations.gen"

  override def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionHelpers.addStringMethodToClass(
      input,
      methodName = "generatedHello",
      value = s"hello ${input.className}"
    )
```

After the marker and handler have been compiled, the consumer imports and uses
its own annotation:

```scala
package com.example.core

import com.example.`macro`.annotations.gen

@gen
class GenUser

val greeting: String = new GenUser().generatedHello
```

Before ordinary typing, Macro-Paradise loads `GenHandler`, adds
`generatedHello`, and lets the compiler typecheck the call like ordinary source.
The repository mechanically compiles this exact example in the independent
marker/handler/consumer verification task. The repository's built-in `@gen`
remains an internal fixture; it is not an installed user annotation.

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

For packaged external-handler projects, delegate build and run actions to sbt.
The sbt-imported workflow is qualified; IntelliJ's native JPS compiler path is
not currently part of the supported boundary.

Run the complete product gate from the repository root:

```sh
sbt -batch verifyPublicProductBoundary
```

The gate runs nonempty plugin and consumer suites, packages the plugin and
experimental handler contract, checks the normalized API surface, exercises
independent consumers, verifies the external-handler starter, and confirms
that only the compiler plugin and handler API are top-level product artifacts
for `publishLocal`. The separate source-built sbt module also validates its
local/test packaging while every remote publication path remains fail closed.

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
handler, then annotated consumer. An opt-in, source-built
[`sbt-integration`](sbt-integration/README.md) module now automates the exact
plugin selection, hidden handler resolution, complete handler-classpath
identity, and compiler options for this precompiled topology. Its static local
project helper deliberately does not infer the marker dependency: the consumer
must still declare `.dependsOn(marker)`. Manual settings remain supported.

The plugin loader sees the self-contained
plugin JAR; the ordinary source classpath sees the API and precompiled marker;
the handler is selected through
`-P:macroparadise:handlerClasspath=<handler-jar>`. A second build-only option
contains a SHA-256 identity derived from every explicit marker artifact and the
complete ordered effective handler expansion classpath, including handler
dependencies, so Zinc invalidates consumers when any role input changes at a
stable path. The running
application does not need the compiler plugin or handler JAR merely because
they were used at compile time. Same-compilation marker metadata is not
currently claimed.

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
- an unreleased syntactic pre-typer read-only view of ordered direct members
  and bounded direct-method structure, with a deliberately tiny type-shape
  normalization;
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
- General same-module handler support is deferred. A bounded different-file
  design probe established a feasible explicit source-mapping, content-identity,
  and compiler-unit-suspension lifecycle for clean and incremental CLI/Zinc/BSP
  builds. It is not implemented, does not cover same-file handlers or dependency
  cycles, and does not qualify live IntelliJ behavior.
- The project does not provide arbitrary target shapes, arbitrary definition
  construction, arbitrary composition, typed tree construction, semantic
  member analysis, or a stable public API. The bounded body view performs no
  typing, symbol/owner lookup, inheritance, alias expansion, or overload
  resolution; raw `ExpansionInput.annotatedClass` remains the exact-compiler
  escape hatch.
- Quasiquotes integration is optional cross-project research, not a product
  build dependency.
- Top-level local publication is enabled only for the exact-cross plugin and
  handler API; the unreleased sbt module has separate local/test packaging.
  Released `0.1.0` does not contain the unreleased direct-body view and has no
  implied release cadence or production support commitment.

See [Supported scope and limitations](docs/SUPPORTED_SCOPE_AND_LIMITATIONS.md)
for the detailed boundary.

## Related projects

- [Quasiquotes for Scala 3](https://github.com/DmytroMitin/quasiquotes-scala3)
  explores a compiler-neutral Scalameta authoring model and exact-compiler
  lowering adapters. It is independent, optional research rather than a
  Macro-Paradise product dependency.
- [AUXify for Scala 3](https://github.com/DmytroMitin/AUXify-scala3) is an
  independent downstream consumer. Its user-authored `@apply` demonstrates a
  richer integration of Macro-Paradise placement with Quasiquotes construction.

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
