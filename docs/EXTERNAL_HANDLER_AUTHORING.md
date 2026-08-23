# External handler authoring

Start with a user-defined `@identity` annotation. It is the smallest supported
authoring example because it exercises the complete external-handler wiring
contract without generating a member. The independent external sbt verifier
separates three roles:

1. a marker JAR containing one annotation and runtime handler metadata;
2. a precompiled handler JAR built against the experimental handler contract
   and exact compiler universe;
3. an ordinary consumer compiled with the packaged plugin.

## Happy path

Pin the build tool in `project/build.properties`:

```text
sbt.version=1.12.15
```

Use JDK feature version 25 and select exact Scala `3.3.8` or `3.8.4`. The
plugin, API, marker, handler, and consumer must all use the same selected line;
a nearby or cross-line artifact is not interchangeable.

```text
    macroAnnotations project             macroHandlers project
             |                                   |
             | core dependsOn(macroAnnotations)  | macroHandlers / Compile / packageBin
             +------------------+----------------+
                                |
                           core project
                           - compilerPlugin(macroparadisePlugin)
                           - handlerClasspath=<handler JAR>
```

The consumer does not `dependsOn(macroHandlers)`. The `packageBin` lookup is the
sbt task dependency that compiles and packages the handler before consumer
scalac-options are evaluated. This keeps handler implementation classes off the
ordinary application compile and runtime classpaths:

```scala
lazy val core = project
  .dependsOn(macroAnnotations)
  .settings(
    libraryDependencies += compilerPlugin(macroparadisePlugin),
    Compile / scalacOptions ++= {
      val handlerJar = (macroHandlers / Compile / packageBin).value
      Seq(
        "-Xplugin-require:macroparadise",
        s"-P:macroparadise:handlerClasspath=${handlerJar.getAbsolutePath}"
      )
    }
  )
```

The normal first-use source form is one explicit import followed by the short
annotation:

```scala
package com.example.core

import com.example.`macro`.annotations.identity

@identity
class Something
```

The marker project must remain on the consumer's ordinary compile classpath via
`.dependsOn(macroAnnotations)`. Omitting that edge is an sbt build-graph error.
Dotty reports the unresolved import as E008 and can also report E046 cyclic
completion around the missing annotation; those compiler diagnostics are
independent of Macro-Paradise's pre-typer identity resolver.

Only the self-contained plugin coordinate activates the compiler plugin. Do
not construct a second `-Xplugin` path that appends `plugin-api`. The marker is
an ordinary project dependency; marker metadata selects the handler class, but
does not load its implementation.

Run it from the repository root:

```sh
sbt -batch verifyIndependentExternalSbtConsumerFromLocalRepository
```

The task packages the product artifacts, runs the isolated nested build, checks
the imported-short and direct-qualified consumers, and verifies missing-handler
and missing-marker negatives. It does not publish artifacts.

## Marker

The marker owns metadata, not the implementation:

```scala
package com.example.`macro`.annotations

import paradise3.api.expander
import scala.annotation.StaticAnnotation

@expander("com.example.macro.handlers.IdentityHandler")
class identity extends StaticAnnotation
```

The handler must already be compiled and available through the explicit
handler classpath. New markers should use an exact qualified annotation
identity. The `@expander` value is also a canonical simple or dot-qualified JVM
class name; empty, whitespace-only, or malformed values fail precheck as
`INVALID_METADATA_HANDLER_CLASS_NAME`.

## Handler

The identity handler implements `ParadiseAnnotationExpander` and returns the
annotated class unchanged:

```scala
package com.example.`macro`.handlers

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.{ExpansionInput, ExpansionOutcome, ParadiseAnnotationExpander}

final class IdentityHandler extends ParadiseAnnotationExpander:
  override def annotationName: String =
    "com.example.macro.annotations.identity"

  override def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.Expanded(List(input.annotatedClass))
```

Successful compilation is the observable smoke test. It proves marker
discovery, metadata binding, imported-short canonicalization, handler loading,
and exactly one handler invocation without depending on generated-member helper
behavior or more involved tree construction.

The safe defaults are:

- `targetProfile = CommonClassOnly`;
- `compositionPolicy = StandaloneOnly`;
- `consumesExistingCompanion = false`.

Override a capability only when the handler has evidence for the corresponding
plugin-owned admission, composition, or companion contract.

## Generated-output follow-on

After the identity smoke test passes, the executable
[`generateGreeting` starter](../examples/external-handler-starter/README.md)
is the next example. Its handler uses `ExpansionHelpers` to add
`generatedGreeting`, and the consumer typechecks and runs that generated member.
It proves transformation output in addition to the same marker, handler, and
consumer wiring; generated helpers are not required merely to prove discovery
and invocation.

```sh
sbt -batch verifyExternalHandlerAuthoringStarter
```

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

### Placing an already-created companion method

Unreleased `0.1.1-SNAPSHOT` development sources include the bounded
`ExpansionHelpers.addMethodToCompanion` helper. It accepts an already-created
raw `untpd.DefDef`; the handler remains responsible for constructing or
lowering that complete method definition:

```scala
import paradise3.api.helpers.{CompanionMethodConflictPolicy, ExpansionHelpers}

ExpansionHelpers.addMethodToCompanion(
  input,
  generatedMethod,
  CompanionMethodConflictPolicy.PreserveExisting
)
```

The helper removes the current handled annotation, creates or copies the
same-name companion, and appends the exact supplied method after existing
direct members. Conflict detection is syntactic and pre-typer: any direct
companion `MemberDef` with the same raw name conflicts, while nested members do
not. `PreserveExisting` keeps the existing companion unchanged;
`CompanionMethodConflictPolicy.Reject` returns an atomic rejected outcome with
the original annotated class fallback.

This helper does not construct syntax, perform semantic companion or overload
resolution, replace existing definitions, or accept arbitrary `MemberDef`
values. It remains compiler-version-sensitive experimental API and must be
compiled against the matching exact full-cross plugin API artifact. The
immutable released `0.1.0` coordinate does not contain this helper.

## Local coordinate for marker and handler authors

First publish the plugin API and plugin from a source clone:

```sh
sbt -batch "pluginApi/publishLocal" "plugin/publishLocal"
```

A separate marker/handler build then compiles against the exact full-cross API
coordinate, not the plugin implementation or any test fixture:

```scala
ThisBuild / scalaVersion := "3.3.8" // or exact 3.8.4
libraryDependencies +=
  ("com.github.dmytromitin" % "macroparadise-scala3-plugin-api" % "0.1.1-SNAPSHOT")
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
ThisBuild / scalaVersion := "3.3.8" // or exact 3.8.4
val mpOrg = "com.github.dmytromitin"
val mpVersion = "0.1.1-SNAPSHOT"
val mpApi =
  (mpOrg % "macroparadise-scala3-plugin-api" % mpVersion)
    .cross(CrossVersion.full)
val macroparadisePlugin =
  (mpOrg % "macroparadise-scala3-plugin" % mpVersion)
    .cross(CrossVersion.full)

lazy val macroAnnotations = project
  .settings(libraryDependencies += mpApi)

lazy val macroHandlers = project
  .settings(
    libraryDependencies ++= Seq(
      mpApi,
      "org.scala-lang" %% "scala3-compiler" % scalaVersion.value
    )
  )

lazy val core = project
  .dependsOn(macroAnnotations)
  .settings(
    libraryDependencies += compilerPlugin(macroparadisePlugin),
    Compile / scalacOptions ++= {
      val handlerJar = (macroHandlers / Compile / packageBin).value
      Seq(
        "-Xplugin-require:macroparadise",
        s"-P:macroparadise:handlerClasspath=${handlerJar.getAbsolutePath}"
      )
    }
  )
```

The executable `@identity` mirror is
`verifyIndependentExternalSbtConsumerFromLocalRepository`. It compiles the
literal marker, handler, and consumer shapes above in a disposable external sbt
build, audits the graph and plugin options, and keeps its artifacts task-local.
The checked-in generated-greeting starter uses the same three-role topology
with explicit local artifact paths.

These snapshot coordinates are locally usable from unreleased `main` but are
not published remotely. Released `0.1.0` remains available only for exact
Scala `3.8.4`. The repository's built-in `@gen` annotation is a fixture and is
not the supported public authoring API.

## Explicit-import identity boundary

`com.example.macro.annotations.identity` is the canonical handler identity. The
normal form is one unambiguous, source-preceding package-level explicit import
followed by the short annotation:

```scala
import com.example.`macro`.annotations.identity

@identity
class Something
```

Direct qualified syntax remains supported as a control or fallback:

```scala
@com.example.`macro`.annotations.identity
class Something
```

This is source-syntactic canonicalization, not typer or semantic name
resolution. The resolver reads only raw `Import` plus identifier/select trees.
It does not support renamed or wildcard imports, local/nested imports, given
imports, exports, package-object semantics, shadowing, or annotation/type
aliases. Two explicit imports for the same short name fail closed and list the
canonical candidates. A short use without a safe explicit witness is never
assigned a guessed package. The qualified control does not broaden those
boundaries.

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

The independent external sbt proof compiles the exact imported-short `@identity`
example above, observes canonical metadata selection and one `IdentityHandler`
invocation, and compiles the direct-qualified control. Its missing-marker lane
remains an ordinary E008/E046 build-graph failure. The generated-greeting
starter then typechecks and runs `new Greeter().generatedGreeting`, which
returns `Hello, Greeter!`. Together they prove wiring first and generated output
second on the pinned toolchain.

It does not prove a marker declared in the same compilation as its annotated
use, same-module handlers, remotely released coordinates, arbitrary targets,
arbitrary composition, import resolution, cross-compiler compatibility, or
release readiness. Current metadata discovery expects the marker artifact to
be precompiled. The compact command is source-build ergonomics for local
packaged artifacts; public source visibility and local publication do not mean
Maven Central availability.
