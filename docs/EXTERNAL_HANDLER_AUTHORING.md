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
identity.

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

The consumer compiler plugin path contains the packaged plugin, handler API,
and marker. The handler JAR is supplied through:

```text
-P:helloWorld:handlerClasspath=<handler-jar>
```

The loader resolves the shared handler API and compiler types parent first.
Do not include a second compiler universe in the handler payload.

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

## What the starter proves

The positive flow typechecks and runs `new Greeter().generatedGreeting`, which
returns `Hello, Greeter!`. It proves one precompiled qualified
marker/handler/consumer path on the pinned toolchain.

It does not prove same-module handlers, stable coordinates, arbitrary targets,
arbitrary composition, import resolution, cross-compiler compatibility, or
release readiness.
