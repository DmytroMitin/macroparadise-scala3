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

From a clone, publish only the two unreleased user artifacts for one selected
exact line to the machine-local sbt/Ivy repository:

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

The built-in `@gen` below remains a repository fixture, not the promised
external annotation API. A user-authored annotation uses a precompiled marker
and `ParadiseAnnotationExpander` handler as described in
[External handler authoring](EXTERNAL_HANDLER_AUTHORING.md).

## Try the built-in source fixture

The built-in `@gen` annotation demonstrates class, companion, and sibling
generation:

```scala
import paradise3.gen

@gen
class User(val name: String)

val user = new User("Ada")
assert(user.generatedHello == "hello Ada")
assert(User.generatedFactory("Grace").name == "Grace")
val metadata: UserMeta = new UserMeta
```

The exact admitted shape is one concrete top-level, non-case, non-generic
class with one non-contextual primary-constructor clause containing one bare or
immutable `val name: String`, no default, and an accessible constructor.

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

Successful unchanged-class compilation proves marker discovery, metadata
binding, canonicalization, handler loading, and one handler invocation. It does
not depend on generated-member helpers or more involved tree construction.

### Continue with generated output

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
application dependencies. This standard multi-project sbt shape is suitable
for CLI use and IntelliJ/BSP import; no IDE metadata is required.

The plugin canonicalizes only the bounded explicit-import form before typer. It
does not implement wildcard, alias, local/nested, given, export, shadowing-
dependent, package-object, or general semantic name resolution. Omitting the
marker project dependency is an ordinary sbt classpath error and can surface as
Dotty E008 plus E046 diagnostics; it is not a Macro-Paradise identity failure.

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
