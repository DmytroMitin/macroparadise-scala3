# External handler authoring

The executable starter under `examples/external-handler-starter` is the
smallest supported authoring example. It separates three roles:

1. a marker JAR containing one annotation and runtime handler metadata;
2. a precompiled handler JAR built against the experimental handler contract
   and exact compiler universe;
3. an ordinary consumer compiled with the packaged plugin.

Run it from the repository root:

```sh
sbt -batch verifyExternalHandlerAuthoringStarter
```

The task packages the product artifacts, runs the isolated nested build, checks
the positive consumer/runtime flow, and verifies a fail-closed negative matrix.
It does not publish artifacts.

## Marker

The marker owns metadata, not the implementation:

```scala
package starter.marker

import paradise3.api.expander

@expander("starter.handler.GenerateGreetingHandler")
final class generateGreeting extends scala.annotation.StaticAnnotation
```

The handler must already be compiled and available through the explicit
handler classpath. New markers should use an exact qualified annotation
identity. The `@expander` value is also a canonical simple or dot-qualified JVM
class name; empty, whitespace-only, or malformed values fail precheck as
`INVALID_METADATA_HANDLER_CLASS_NAME`.

## Handler

The starter handler implements `ParadiseAnnotationExpander`:

```scala
package starter.handler

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.*
import paradise3.api.helpers.ExpansionHelpers

final class GenerateGreetingHandler extends ParadiseAnnotationExpander:
  val annotationName: String = "starter.marker.generateGreeting"

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionHelpers.withAnnotatedClassView(input): view =>
      ExpansionHelpers.addStringMethodToClass(
        input,
        methodName = "generatedGreeting",
        value = s"Hello, ${view.className}!"
      )
```

The safe defaults are:

- `targetProfile = CommonClassOnly`;
- `compositionPolicy = StandaloneOnly`;
- `consumesExistingCompanion = false`.

Override a capability only when the handler has evidence for the corresponding
plugin-owned admission, composition, or companion contract.

## API boundary

`ExpansionInput` exposes compiler-sensitive untyped trees and an optional
decoded class view. `ExpansionHelpers.withAnnotatedClassView` provides a small
fail-closed adapter for the common class shape. Helper methods can add one
bounded method to a class or companion or create one sibling class.

Successful handlers may return structured output with explicit primary,
companion, and additional-definition roles. The ordered raw-tree outcome
remains available for unusual shapes, but it is validated and does not bypass
plugin-owned conflicts, composition rules, or rollback.

The contract is exact-compiler experimental API. It does not promise typed
trees, stable owners, semantic names, cross-version binary compatibility, or
general definition builders.

## Local coordinate for marker and handler authors

First publish the plugin API and plugin from a source clone:

```sh
sbt -batch "pluginApi/publishLocal" "plugin/publishLocal"
```

A separate marker/handler build then compiles against the exact full-cross API
coordinate, not the plugin implementation or any test fixture:

```scala
ThisBuild / scalaVersion := "3.8.5-RC1-bin-20260405-9478256-NIGHTLY"
ThisBuild / resolvers += Resolver.scalaNightlyRepository

libraryDependencies +=
  ("com.github.dmytromitin" % "macroparadise-scala3-plugin-api" % "0.1.0")
    .cross(CrossVersion.full)
```

The same API artifact supplies the runtime-retained `paradise3.api.expander`
metadata annotation and the `ParadiseAnnotationExpander` contract. Keep it on
the marker/handler compile classpath exactly once. The ordinary consumer adds
the full-cross compiler plugin as shown in [Getting started](GETTING_STARTED.md),
depends on the precompiled marker, and supplies the precompiled handler JAR
through `-P:macroparadise:handlerClasspath=<handler-jar>`.

The minimal sbt graph is three projects. The marker and handler settings contain
no `compilerPlugin` dependency, so Macro-Paradise is not active while either
producer is compiled:

```scala
ThisBuild / scalaVersion := "3.8.5-RC1-bin-20260405-9478256-NIGHTLY"
ThisBuild / resolvers += Resolver.scalaNightlyRepository

val mpOrg = "com.github.dmytromitin"
val mpVersion = "0.1.0"
val mpApi =
  (mpOrg % "macroparadise-scala3-plugin-api" % mpVersion)
    .cross(CrossVersion.full)

lazy val marker = project
  .settings(libraryDependencies += mpApi)

lazy val handler = project
  .settings(
    libraryDependencies ++= Seq(
      mpApi,
      "org.scala-lang" %% "scala3-compiler" % scalaVersion.value
    )
  )

lazy val consumer = project
  .dependsOn(marker)
  .settings(
    libraryDependencies ++= Seq(
      compilerPlugin(
        (mpOrg % "macroparadise-scala3-plugin" % mpVersion)
          .cross(CrossVersion.full)
      ),
      mpApi
    ),
    Compile / scalacOptions ++= {
      val handlerJar = (handler / Compile / packageBin).value
      Seq(
        "-Xplugin-require:macroparadise",
        s"-P:macroparadise:handlerClasspath=${handlerJar.getAbsolutePath}"
      )
    }
  )
```

The executable coordinate-resolution mirror is
`verifyIndependentExternalSbtConsumerFromLocalRepository`; the checked-in
starter uses the same marker/handler/consumer topology with explicit local
artifact paths.

The candidate coordinates are locally usable but are not available from Maven
Central. The repository's built-in `@gen` annotation is a fixture and is not
the supported public authoring API.

## Qualified identity boundary

`starter.marker.generateGreeting` must match the raw source identity exactly.
The plugin does not resolve a short name introduced by an import, an alias, or
a wildcard import. Qualified syntax never falls back to `generateGreeting`.

This limitation is deliberate: the handler path runs before ordinary semantic
name resolution.

## Classpath and loading

The handler compiles directly against the packaged handler contract and the
exact Scala compiler/runtime universe. It must not depend directly on the
plugin implementation or repository fixture modules.

The five classpath roles are deliberately separate:

- Dotty's compiler-plugin loader receives only the self-contained plugin JAR.
  The JAR embeds the exact API classes unshaded, preserving
  `paradise3.api.ParadiseAnnotationExpander` identity.
- The ordinary source compilation classpath contains the separate API artifact
  when its types or metadata are needed, plus the already-compiled marker.
  It is not a parent of the plugin classloader.
- Marker metadata must be available from a precompiled marker artifact before
  the annotated consumer compilation starts.
- The precompiled handler is selected from the path supplied through:

```text
-P:macroparadise:handlerClasspath=<handler-jar>
```

- The runtime application classpath contains only ordinary application/runtime
  dependencies. The compiler plugin and handler JAR do not need to remain there
  merely because compilation used them.

The handler child loader resolves the embedded shared API and exact compiler
types parent first. A handler compiled against the separate `plugin-api` JAR
therefore implements the plugin-owned runtime identity without a shaded alias
or a second compiler universe.

## Preconsumer checks

Before consumer compilation, the starter verifies:

- JDK 25 and the pinned Scala compiler;
- packaged plugin, API, marker, and handler roles;
- qualified annotation identity;
- marker metadata and handler descriptor binding;
- parent-first API/class-loader identity;
- the handler's direct dependency boundary;
- absence of forbidden plugin/test linkage in the handler payload.

Failures stop before consumer compilation. Categories cover malformed handler
identity, metadata/descriptor mismatch, exact compiler or JDK mismatch,
forbidden handler dependencies, and missing artifacts.

Metadata-authoring failures expose the independent facts already known to the
precheck in a stable order: `failureStage`, `markerIdentity`,
`expectedAnnotation`, `metadataHandler`, `expectedHandler`, `markerArtifact`,
and `handlerArtifact`. A descriptor mismatch also includes
`declaredAnnotation`. For example, a marker selecting handler A while the
supplied handler JAR contains only B remains a `HANDLER_CLASS_LOADING_FAILURE`,
but now identifies the marker, both handler witnesses, and the exact JAR that
lacks A.

Explicit and compact commands classify the same underlying metadata fault with
the same category and core fields. Compact derivation does not remove the
independent expected-handler or expected-annotation witnesses.

The packaged plugin also exposes deterministic command help:

```sh
java -cp <plugin-and-exact-runtime-classpath> \
  macroparadise.ExternalHandlerPrecheckMain --help
```

The help output presents two additive modes. Explicit mode keeps the maximum
caller-supplied independent expectations:

```text
--plugin=<plugin.jar>
--plugin-api=<plugin-api.jar>
--marker=<marker.jar>
--handler=<handler.jar>
--handler-compile-classpath=<path-list>
--marker-class=<qualified-marker-class>
--expected-handler-class=<qualified-handler-class>
--expected-annotation=<qualified-annotation-name>
--expected-scala-version=<exact-version>
--expected-jdk-major=<major>
```

Compact mode removes only three duplicated inputs:

```text
--compact
--marker=<marker.jar>
--handler=<handler.jar>
--handler-compile-classpath=<path-list>
--expected-handler-class=<qualified-handler-class>
--expected-annotation=<qualified-annotation-name>
--expected-scala-version=<exact-version>
--expected-jdk-major=<major>
```

The running `ExternalHandlerPrecheckMain` code source supplies the production
plugin JAR. In the self-contained package, the parent-loaded
`ParadiseAnnotationExpander` has that same code source. Compact mode therefore
selects the unique separate authoring API JAR from the recorded handler compile
classpath; zero or multiple candidates fail closed. The canonical expected
annotation supplies the marker class. All derived paths must be local regular
JARs with the expected artifact roles.

The expected handler, expected annotation, exact Scala version, and JDK major
remain caller-supplied independent witnesses. They are intentionally not
derived from marker/handler metadata or observed runtime versions. A failed
precheck exits with status 2 and reports
`stage=preconsumer consumerCompilationStarted=false expansionInvoked=false`
before its category, detail, and usage. `--help` exits successfully without
loading artifacts or running the precheck.

The starter preserves P1-P7 and C1-C6 and adds M1-M9 paired metadata-authoring
lanes: nonexistent or non-handler classes, an A/B artifact mismatch,
descriptor/stale/qualified-identity mismatches, and empty, whitespace, or
malformed handler metadata. Every M lane runs in both modes and proves the
preconsumer stop boundary.

## What the starter proves

The positive flow typechecks and runs `new Greeter().generatedGreeting`, which
returns `Hello, Greeter!`. It proves one precompiled qualified
marker/handler/consumer path on the pinned toolchain.

It does not prove a marker declared in the same compilation as its annotated
use, same-module handlers, remotely released coordinates, arbitrary targets,
arbitrary composition, import resolution, cross-compiler compatibility, or
release readiness. Current metadata discovery expects the marker artifact to
be precompiled. The compact command is source-build ergonomics for local
packaged artifacts; public source visibility and local publication do not mean
Maven Central availability.
