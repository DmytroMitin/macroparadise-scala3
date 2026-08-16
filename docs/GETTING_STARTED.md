# Getting started

Macro-Paradise for Scala 3 is currently evaluated from source. No plugin or
handler-contract artifact is available from a remote package repository.

## Requirements

- JDK feature version 25
- sbt 1.12.8
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
handler starter, and confirms that publishing is disabled.

The ordinary aggregate test command is useful during development:

```sh
sbt -batch test
```

It is narrower than the canonical product gate.

## Try the built-in fixture

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

Run:

```sh
sbt -batch verifyExternalHandlerAuthoringStarter
```

The task packages the real plugin and experimental handler API, then runs a
nested marker/handler/consumer build with task-owned local paths. The ordinary
consumer typechecks and executes a generated `generatedGreeting` method only
after the preconsumer checks pass.

Read [External handler authoring](EXTERNAL_HANDLER_AUTHORING.md) for the marker,
handler, classpath, output, and diagnostic contracts.

## Where to go next

- [Architecture](ARCHITECTURE.md)
- [Supported scope and limitations](SUPPORTED_SCOPE_AND_LIMITATIONS.md)
- [Diagnostics and troubleshooting](DIAGNOSTICS.md)
- [Compatibility](COMPATIBILITY.md)
- [Versioning and stability](VERSIONING_AND_STABILITY.md)

The source build does not require another repository checkout or any private
controller material.
