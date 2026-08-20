# Getting started

Macro-Paradise for Scala 3 can be built from source and installed locally. The
selected `0.1.0` candidate is not available from Maven Central or another
remote package repository.

## Requirements

- JDK feature version 25
- sbt 1.12.15
- network access to resolve the pinned Scala nightly and ordinary build
  dependencies when they are not already cached

The build pins Scala
`3.8.5-RC1-bin-20260405-9478256-NIGHTLY`. The global load check rejects other
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

## Publish the candidate locally

From a clone on the exact toolchain, publish only the two user artifacts to the
machine-local sbt/Ivy repository:

```sh
sbt -batch "pluginApi/publishLocal" "plugin/publishLocal"
```

In a fresh external sbt project, use the exact Scala nightly and the
full-crossed plugin coordinate:

```scala
ThisBuild / scalaVersion := "3.8.5-RC1-bin-20260405-9478256-NIGHTLY"
ThisBuild / resolvers += Resolver.scalaNightlyRepository

addCompilerPlugin(("com.github.dmytromitin" % "macroparadise-scala3-plugin" % "0.1.0").cross(CrossVersion.full))
```

Do not replace `.cross(CrossVersion.full)` with `%%`: the published artifact is
`macroparadise-scala3-plugin_3.8.5-RC1-bin-20260405-9478256-NIGHTLY` and is
tied to that exact compiler. These coordinates are the first-release candidate
only; `0.1.0` is not available from Maven Central until a later owner-authorized
release.

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

## Try the external-handler starter

Pin the consumer build tool in `project/build.properties`:

```text
sbt.version=1.12.15
```

The installed `sbt` command is a runner; its own version does not replace the
project pin. With this file present, the ordinary CLI runner, a direct
`sbt-launch.jar` invocation, IntelliJ import, and BSP all select sbt 1.12.15.
Without it, a runner may choose and even write a different default sbt version.
That is build-tool divergence, not a Macro-Paradise semantic mode. sbt 2 is not
part of the supported boundary.

Run:

```sh
sbt -batch verifyExternalHandlerAuthoringStarter
```

The task packages the real plugin and experimental handler API, then runs a
nested marker/handler/consumer build with task-owned local paths. The ordinary
consumer typechecks and executes a generated `generatedGreeting` method only
after the preconsumer checks pass.

The marker and handler are compiled before the annotated consumer, without the
Macro-Paradise plugin active in either producer compilation. The consumer's
plugin loader receives only the self-contained plugin JAR. Its ordinary source
classpath receives the API and precompiled marker, while the precompiled
handler is selected through `-P:macroparadise:handlerClasspath=...`. Compiler
plugin and handler artifacts are compilation tools, not automatic runtime
application dependencies. This standard multi-project sbt shape is suitable
for CLI use and IntelliJ/BSP import; no IDE metadata is required.

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
