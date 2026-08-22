# External handler authoring

The executable starter under `examples/external-handler-starter` is the
smallest supported authoring example. It separates three roles:

1. a marker JAR containing one annotation and runtime handler metadata;
2. a precompiled handler JAR built against the experimental handler contract
   and exact compiler universe;
3. an ordinary consumer compiled with the packaged plugin.

## Happy path

Pin the build tool in `project/build.properties`:

```text
sbt.version=1.12.15
```

Use JDK feature version 25 and Scala
`3.8.5-RC1-bin-20260405-9478256-NIGHTLY` exactly. The handler API exposes
compiler internals; a nearby Scala or JDK version is not an interchangeable
substitute.

```text
marker project                 handler project
     |                              |
     | consumer dependsOn(marker)   | handler / Compile / packageBin
     +---------------+--------------+
                     |
                consumer project
                - compilerPlugin(plugin)
                - handlerClasspath=<handler JAR>
```

The consumer does not `dependsOn(handler)`. The `packageBin` lookup is the sbt
task dependency that compiles and packages the handler before consumer
scalac-options are evaluated. This keeps handler implementation classes off the
ordinary application compile and runtime classpaths:

```scala
lazy val consumer = project
  .dependsOn(marker)
  .settings(
    libraryDependencies += compilerPlugin(
      ("com.github.dmytromitin" % "macroparadise-scala3-plugin" % "0.1.0")
        .cross(CrossVersion.full)
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

The consumer source can use the ordinary first-use form:

```scala
package starter.consumer

import starter.marker.generateGreeting

@generateGreeting
final class Greeter
```

The marker project must remain on the consumer's ordinary compile classpath via
`.dependsOn(marker)`. Omitting that edge is an sbt build-graph error. Dotty may
then report the unresolved import together with an E046 cyclic-completion
diagnostic; that compiler diagnostic is independent of Macro-Paradise's
pre-typer identity resolver.

Only the self-contained plugin coordinate activates the compiler plugin. Do
not construct a second `-Xplugin` path that appends `plugin-api`. The marker is
an ordinary project dependency; marker metadata selects the handler class, but
does not load its implementation.

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

The complete sbt graph is three projects. The marker and handler settings contain
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

## Explicit-import identity boundary

`starter.marker.generateGreeting` remains the canonical handler identity. The
pre-typer plugin can obtain it from either direct qualified annotation syntax
or one unambiguous, source-preceding package-level explicit import:

```scala
import starter.marker.generateGreeting
@generateGreeting
final class Greeter
```

This is source-syntactic canonicalization, not typer or semantic name
resolution. The resolver reads only raw `Import` plus identifier/select trees.
It does not support renamed or wildcard imports, local/nested imports, given
imports, exports, package-object semantics, shadowing, or annotation/type
aliases. Two explicit imports for the same short name fail closed and list the
canonical candidates. A short use without a safe explicit witness is never
assigned a guessed package. Direct qualified syntax is unchanged.

Existing handlers with captured simple descriptors keep their legacy simple
identity after metadata selection. This compatibility does not let a directly
qualified annotation fall back to its final segment. New handlers should use
the canonical qualified descriptor shown above.

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

## Why the handler path stays explicit

The supported default remains an explicit handler path plus the sbt
`packageBin` setting above.

- Automatically searching the ordinary source classpath would make handler
  provenance depend on source/runtime dependency resolution and could admit
  duplicate API or compiler universes.
- An opt-in source-classpath search would retain those identity and provenance
  risks while adding another mode for Zinc, BSP, and IDE imports to reproduce.
- A dedicated sbt plugin would add a separately versioned distribution surface
  for wiring that one task expression already represents.

The explicit path keeps the plugin loader, marker lookup, and handler loader
separate; avoids adding handler implementation code to application runtime;
and makes the selected artifact visible in `scalacOptions`. The direct
`handler/packageBin` task dependency also gives Zinc an explicit reason to
repackage the handler before consumer options are evaluated.

If a handler has external runtime dependencies, supply the handler JAR and
those required dependency JARs as a platform path list. Do not add them to
`-Xplugin` and do not rely on transitive application dependencies to populate
the handler loader. The first-use loading diagnostic distinguishes an absent
path from a configured path that cannot load the metadata-selected class.

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
