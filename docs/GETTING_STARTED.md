# Getting started

Macro-Paradise `0.1.0` is available from Maven Central for exact Scala `3.8.4`.
Unreleased `main` uses `0.1.1-SNAPSHOT` and can be built or installed locally
for exact Scala `3.3.8` or `3.8.4` as separate lanes.

## Requirements

- JDK feature version 25
- sbt 1.12.15
- network access to resolve the pinned stable Scala release and ordinary build
  dependencies when they are not already cached

The build accepts only exact Scala `3.3.8` and `3.8.4`; select one lane
explicitly for cross-line qualification. The global load check rejects other
JDK feature versions before normal tasks execute.

Confirm the active JVM before loading sbt:

```sh
java -version
```

## Import into IntelliJ IDEA

The JVM that launches sbt must be JDK 25; the project language level and Scala
SDK do not select that process. Open
`Settings | Build, Execution, Deployment | Build Tools | sbt`, set
`JVM | JRE` to JDK 25, and set the project SDK to JDK 25 under
`File | Project Structure | Project`. Then refresh or reimport the sbt project.

If the import command begins with a Java 8 path such as
`.../corretto-1.8.../bin/java`, the sbt JVM is wrong. Do not change the pinned
Scala version to compensate. The repository's bootstrap check rejects that JVM
before compiling the ordinary meta-build helpers and tells you to select JDK
25.

Delegate packaged external-handler build and run actions to sbt. The
sbt-imported workflow is qualified; IntelliJ's native JPS compiler path is not.

## Run the product gate

From the repository root:

```sh
sbt -batch verifyPublicProductBoundary
```

This is the canonical source-build gate. It runs the plugin and consumer test
suites, verifies compatibility fixtures and package boundaries, checks the
experimental API baseline, exercises independent consumers and the external
handler starter, and confirms that local publication is limited to the plugin
and handler API with no remote destination or credentials.

The ordinary aggregate test command is useful during development:

```sh
sbt -batch test
```

It is narrower than the canonical product gate.

## Use the release or publish unreleased main locally

The released coordinate remains exact Scala `3.8.4`:

```scala
ThisBuild / scalaVersion := "3.8.4"
addCompilerPlugin(("com.github.dmytromitin" % "macroparadise-scala3-plugin" % "0.1.0").cross(CrossVersion.full))
```

From a clone, publish the two unreleased compiler-facing user artifacts for one
selected exact line to the machine-local sbt/Ivy repository. The separate sbt
integration module is source-built and uses its own local/test packaging; it is
not part of these commands:

```sh
sbt -Dmacroparadise.exactScalaVersion=3.3.8 -batch "++3.3.8!" "pluginApi/publishLocal" "plugin/publishLocal"
```

In a fresh external development project, use the same exact line and snapshot:

```scala
ThisBuild / scalaVersion := "3.3.8"
addCompilerPlugin(("com.github.dmytromitin" % "macroparadise-scala3-plugin" % "0.1.1-SNAPSHOT").cross(CrossVersion.full))
```

Do not replace `.cross(CrossVersion.full)` with `%%`: the published artifact is
`macroparadise-scala3-plugin_3.3.8` and is tied to that exact compiler. The
same rule applies independently to `_3.8.4`; the two artifacts are not
interchangeable.

This declaration adds one self-contained plugin JAR to Dotty's plugin loader.
That JAR contains the exact unshaded runtime `paradise3.api` classes the plugin
links against; the plugin POM does not pull in a conflicting API runtime copy.
An ordinary `plugin-api` library dependency is needed only for compiling a
user-owned marker or handler, and ordinary source dependencies do not become a
parent of the compiler plugin classloader.

## Choose the external-handler setup

There are two top-level choices:

1. **Use `sbt-macroparadise` (recommended normal path).** For producers in the
   same multi-project build, use
   `MacroParadiseIntegration.precompiledProjects(macroAnnotations, macroHandlers)`;
   neither producer needs `publishLocal`. For producers that really are
   published or deliberately installed in local Ivy, use
   `macroParadiseMarkerModules` and `macroParadiseHandlerModules`.
2. **Do not use the sbt integration.** Use the complete manual settings and
   copy the public `ExternalArtifactIdentity.scala` build helper into your own
   `project/` directory. The identity option is required for the supported
   incremental contract.

The integration plugin itself is source-built and unreleased. From this source
checkout, install it to local Ivy separately:

```sh
cd sbt-integration
sbt -batch verifyIntegrationPolicy publishLocal
```

Then the consumer build can contain:

```scala
// project/plugins.sbt
addSbtPlugin("com.github.dmytromitin" % "sbt-macroparadise" % "0.1.1-SNAPSHOT")
```

That coordinate is not available from a remote repository today. See the
[source-built integration guide](../sbt-integration/README.md) for complete
local-project and published-module examples, or
[External handler authoring](EXTERNAL_HANDLER_AUTHORING.md) for the complete
manual graph. All examples use explicit `file("macro-annotations")`,
`file("macro-handlers")`, and `file("core")` locations; an unqualified
`lazy val macroAnnotations = project` instead selects a `macroAnnotations/`
base directory and does not describe the hyphenated layout.

## Try a user-defined external handler

Pin the external build tool in `project/build.properties`:

```text
sbt.version=1.12.15
```

The installed `sbt` command is a runner; its own version does not replace the
project pin. With this file present, the ordinary CLI runner, a direct
`sbt-launch.jar` invocation, IntelliJ import, and BSP all select sbt 1.12.15.
Without it, a runner may choose and even write a different default sbt version.
That is build-tool divergence, not a Macro-Paradise semantic mode. sbt 2 is not
part of the supported boundary.

### Start with `@identity`

The canonical minimal first-use example has a precompiled marker, a precompiled
handler that returns the class unchanged, and this consumer:

```scala
package com.example.core

import com.example.`macro`.annotations.identity

@identity
class Something
```

The normal form is the explicit import plus short `@identity` shown above. A
direct-qualified annotation remains supported as a control or fallback:

```scala
@com.example.`macro`.annotations.identity
class Something
```

The complete copy/paste marker, handler, and three-project sbt graph are in
[External handler authoring](EXTERNAL_HANDLER_AUTHORING.md). The repository
mechanically compiles both source forms with:

```sh
sbt -batch verifyIndependentExternalSbtConsumerFromLocalRepository
```

For the opt-in source-built integration, see the
[`sbt-integration` module](../sbt-integration/README.md). It preserves the
three-project topology, requires the marker `.dependsOn` edge explicitly, and
derives invalidation identity from the complete ordered handler expansion
classpath. Its same-build local-project mode packages producers directly and
does not require producer `publishLocal`. The manual graph remains a supported,
transparent escape hatch.

For this source-built, unreleased integration, real persistent sbt BSP
compilation and run requests are qualified on exact Scala `3.3.8` and `3.8.4`
with sbt 1.12.15 and JDK 25. The retained-process qualification includes
content-sensitive handler, handler-dependency, and marker invalidation plus
stale-handler failure and repair without `clean` or server restart. The
separate opt-in bounded different-file Model A on unreleased `main` is also
qualified for live IntelliJ use on both exact Scala lines when Build and Run
are delegated to sbt, including handler-only edits without `clean`. This does
not make the integration a remotely published sbt plugin, establish general
same-module support, or qualify IntelliJ native JPS compilation.

Successful unchanged-class compilation proves marker discovery, metadata
binding, canonicalization, handler loading, and one handler invocation. It does
not depend on generated-member helpers or more involved tree construction.

### Continue with your own `@gen`

The [README example](../README.md#a-small-user-authored-example) defines a
user-owned `@gen` marker and `GenHandler`. Its consumer typechecks an ordinary
call to the generated method:

```scala
import com.example.`macro`.annotations.gen

@gen
class GenUser

val greeting: String = new GenUser().generatedHello
```

The same independent external-build task compiles that literal marker, handler,
and consumer alongside the minimal `@identity` path:

```sh
sbt -batch verifyIndependentExternalSbtConsumerFromLocalRepository
```

This is the first generated-output step. The repository's built-in `@gen` is
only an internal compiler fixture and is not an installed annotation API.

### See a real downstream `@apply`

[AUXify for Scala 3](https://github.com/DmytroMitin/AUXify-scala3) is an
independent downstream project whose user-authored `@apply` combines
Macro-Paradise placement with Quasiquotes construction. It is the next step
after the minimal `@identity` wiring proof and the user-owned `@gen` output
example; it is not a dependency of this product build.

### Optional: run the more extensive starter

Run the fixture-independent `generateGreeting` starter:

```sh
sbt -batch verifyExternalHandlerAuthoringStarter
```

The task packages the real plugin and experimental handler API, then runs a
nested marker/handler/consumer build with task-owned local paths. The ordinary
consumer typechecks and executes a generated `generatedGreeting` method only
after the preconsumer checks pass. This second example proves transformation
output after the identity smoke test has isolated the wiring contract.

The marker and handler are compiled before the annotated consumer, without the
Macro-Paradise plugin active in either producer compilation. The consumer's
plugin loader receives only the self-contained plugin JAR. Its ordinary source
classpath receives the API and precompiled marker, while the precompiled
handler is selected through `-P:macroparadise:handlerClasspath=...`. Compiler
plugin and handler artifacts are compilation tools, not automatic runtime
application dependencies. The consumer options also include a build-only
`externalArtifactIdentity` SHA-256 derived from the packaged marker artifacts
and complete ordered effective handler expansion classpath; that content
identity is required for reliable handler-dependency-only, handler-only, and
marker-metadata incremental invalidation while their artifact paths stay
stable. This standard
multi-project sbt shape is suitable for CLI use and BSP import; no IDE metadata
is required.

The plugin canonicalizes only the bounded explicit-import form before typer. It
does not implement wildcard, alias, local/nested, given, export, shadowing-
dependent, package-object, or general semantic name resolution. Omitting the
marker project dependency is an ordinary sbt classpath error and can surface as
unresolved-import and cyclic-completion compiler diagnostics; it is not a
Macro-Paradise identity failure.

Read [External handler authoring](EXTERNAL_HANDLER_AUTHORING.md) for the marker,
handler, classpath, output, and diagnostic contracts.

## Where to go next

- [Architecture](ARCHITECTURE.md)
- [Quasiquote and pre-typer AST architecture](QUASIQUOTE_ARCHITECTURE.md)
- [Supported scope and limitations](SUPPORTED_SCOPE_AND_LIMITATIONS.md)
- [Diagnostics and troubleshooting](DIAGNOSTICS.md)
- [Compatibility](COMPATIBILITY.md)
- [Versioning and stability](VERSIONING_AND_STABILITY.md)

The source build does not require another repository checkout or any private
controller material.
