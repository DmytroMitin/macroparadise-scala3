# sbt integration

This opt-in sbt plugin automates the precompiled Macro-Paradise marker/handler
setup. It runs in sbt 1.x's Scala 2.12 plugin universe and has no Scala 3
runtime dependency of its own.

The published `0.1.1` integration and current `main` also contain a separate
no-trigger plugin for one bounded
same-module different-file Model A, experimentally supported only in the
enumerated exact-line workflows below. Enabling that plugin is an explicit
choice and does not change the default precompiled path.

It selects exact-full-cross compiler plugin and authoring API coordinates,
keeps published handlers in a hidden configuration, and derives Zinc
compiler-option identity from every explicit marker artifact plus the complete
ordered handler expansion classpath. The consumer still declares its ordinary
marker dependency.

## Install the release or current source snapshot

For normal use, install the published `0.1.1` integration:

```scala
// project/plugins.sbt
addSbtPlugin("com.github.dmytromitin" % "sbt-macroparadise" % "0.1.1")
```

There is no remote sbt-plugin artifact for current `0.2.0-SNAPSHOT`
development. To exercise that source checkout, install it deliberately to local
Ivy:

```sh
cd sbt-integration
sbt -batch verifyIntegrationPolicy publishLocal
```

Then add this file to the downstream build:

```scala
// project/plugins.sbt
addSbtPlugin("com.github.dmytromitin" % "sbt-macroparadise" % "0.2.0-SNAPSHOT")
```

The compiler plugin and plugin API for the selected exact Scala line must also
be resolvable. For current main development, install those from the repository root as
described in [Getting started](../docs/GETTING_STARTED.md). Installing the sbt
plugin does not publish marker or handler projects.

## Local marker and handler projects

Use this mode when `macro-annotations/`, `macro-handlers/`, and `core/` are in
one build. The helper wires the producers' `packageBin` tasks directly, so do
not run `publishLocal` for either producer.

```scala
// build.sbt
import macroparadise.sbt.MacroParadiseIntegration
import macroparadise.sbt.MacroParadisePrecompiledPlugin.autoImport._

ThisBuild / scalaVersion := "3.3.8" // or exact 3.8.4 / 3.9.0

val mpVersion = "0.1.1"
val mpApi =
  ("com.github.dmytromitin" % "macroparadise-scala3-plugin-api" % mpVersion)
    .cross(CrossVersion.full)

lazy val macroAnnotations = (project in file("macro-annotations"))
  .settings(libraryDependencies += mpApi)

lazy val macroHandlers = (project in file("macro-handlers"))
  .settings(
    libraryDependencies ++= Seq(
      mpApi,
      "org.scala-lang" %% "scala3-compiler" % scalaVersion.value
    )
  )

lazy val core = (project in file("core"))
  .dependsOn(macroAnnotations % "provided->compile")
  .settings(
    MacroParadiseIntegration.precompiledProjects(
      macroAnnotations,
      macroHandlers
    )
  )
  .enablePlugins(macroparadise.sbt.MacroParadisePrecompiledPlugin)
  .settings(macroParadiseCompilerProductVersion := mpVersion)
```

`precompiledProjects` accepts static `ProjectReference` values. It packages the
marker and handler, puts the marker in the explicit marker role, and builds the
ordered handler expansion classpath from the primary handler plus its runtime
dependencies. It deliberately does not infer the marker dependency. For a
marker-only project, `provided->compile` is the normal mapping: consumer
`provided` receives producer `compile`, so the marker is available while the
consumer compiles and remains available to the integration's packaged-marker
identity/precheck tasks, but is absent from the ordinary runtime classpath.
Use plain `.dependsOn(macroAnnotations)` instead when that producer deliberately
contains runtime API or classes the application needs. The handler remains a
compile-time tool and is never an ordinary `core` dependency.

For multiple local producer projects, current `0.2.0-SNAPSHOT` adds this
source-compatible overload:

```scala
MacroParadiseIntegration.precompiledProjects(
  markers = Seq(markerA, markerB),
  handlers = Seq(handlerA, handlerB)
)
```

It creates static `packageBin` and runtime-classpath task edges for every
reference. Marker primaries are labelled `local-marker-0000`,
`local-marker-0001`, and so on. All handler primaries
(`local-handler-0000`, ...) precede retained runtime dependencies; canonical
files are de-duplicated in first-seen order, including shared transitives. The
original one-marker/one-handler overload and its labels are unchanged.
Repeated calls to that original overload overwrite earlier role settings; they
do not compose. Use the `Seq` overload for supported multi-local composition.

## Published marker and handler modules

Use this mode only when producer artifacts are genuinely published or
deliberately installed into a resolver such as local Ivy. Producers should use
exact-full-cross artifact names:

```scala
// producer build settings
ThisBuild / scalaVersion := "3.3.8" // or exact 3.8.4 / 3.9.0

lazy val macroAnnotations = (project in file("macro-annotations"))
  .settings(
    moduleName := "my-macro-annotations",
    crossVersion := CrossVersion.full
  )

lazy val macroHandlers = (project in file("macro-handlers"))
  .settings(
    moduleName := "my-macro-handlers",
    crossVersion := CrossVersion.full
  )
```

The consumer selects those resolved modules:

```scala
// build.sbt, with MacroParadisePrecompiledPlugin enabled on core
import macroparadise.sbt.MacroParadisePrecompiledPlugin.autoImport._

ThisBuild / scalaVersion := "3.3.8" // or exact 3.8.4 / 3.9.0

lazy val core = (project in file("core"))
  .enablePlugins(macroparadise.sbt.MacroParadisePrecompiledPlugin)
  .settings(
    macroParadiseCompilerProductVersion := "0.1.1",
    macroParadiseMarkerModules := Seq(
      (("com.example" % "my-macro-annotations" % "1.0.0")
        .cross(CrossVersion.full)) % Provided
    ),
    macroParadiseHandlerModules := Seq(
      ("com.example" % "my-macro-handlers" % "1.0.0")
        .cross(CrossVersion.full)
    )
  )
```

`macroParadiseMarkerModules` preserves each supplied `ModuleID` configuration
when it adds the marker to ordinary `libraryDependencies`. `% Provided` is the
normal form for a marker-only module: it resolves through
`Compile / dependencyClasspath`, becomes a labelled marker-role artifact, and
participates in `macroParadiseExternalArtifactIdentity`, while remaining absent
from ordinary runtime. Omit `% Provided` when the published marker module also
contains runtime-bearing API the application needs.

`macroParadiseHandlerModules` places the declared modules in the hidden
`macroParadiseHandler` configuration. Direct configured handler artifacts are
resolved first, followed by their complete transitive dependency classpath.
That ordered closure becomes `macroParadiseHandlerClasspath` and also
participates in the external identity; it is not added to ordinary application
runtime dependencies.

Together, marker artifacts and the handler classpath pass through validation
and precheck, then produce these compiler inputs:

```text
-Xplugin-require:macroparadise
-P:macroparadise:handlerClasspath=<ordered handler paths>
-P:macroparadise:externalArtifactIdentity=sha256:<derived identity>
```

The published and local APIs are intentionally asymmetric. Published
`ModuleID` values are declarative resolver inputs. Local `ProjectReference`
values must create static sbt task dependencies on `packageBin`,
`classDirectory`, and runtime dependency classpaths. Project-reference setting
keys would hide that real task-graph distinction rather than simplify it.

In this mode, resolving the producer modules is intentional. A workflow that
temporarily removes `core`, publishes both producers locally, then restores
`core` is a consequence of choosing module resolution; it is not a
Macro-Paradise requirement. Prefer the local-project helper during development
when all three projects already share one build.

The primary settings remain explicit overrides, including
`macroParadiseCompilerPluginModule`, `macroParadiseMarkerArtifacts`,
`macroParadiseHandlerClasspath`, `macroParadiseAdditionalHandlerClasspath`, and
`macroParadisePrecheckEnabled`. `macroParadiseExternalArtifactIdentity` is a
derived output in supported AutoPlugin mode; replacing it fails validation.

## Experimental same-module different-file Model A

This path is included in released `0.1.1` but remains experimentally supported
only within its bounded qualified workflows. It is deliberately separate from
`MacroParadisePrecompiledPlugin`, accepts exactly one explicit relationship,
and supports only exact Scala 3.3.8, 3.8.4, or 3.9.0:

```scala
import macroparadise.sbt.MacroParadiseSameModulePlugin

enablePlugins(MacroParadiseSameModulePlugin)

scalaVersion := "3.8.4" // or exact 3.3.8 / 3.9.0

macroParadiseSameModuleBinding := Some(
  macroParadiseSameModuleHandler(
    annotationName = "demo.sameModuleDebug",
    handlerClassName = "demo.SameModuleDebugExpander",
    markerSource = macroParadiseLabelledSource(
      "marker-source",
      "demo/SameModuleDebugAnnotation.scala"
    ),
    handlerSource = macroParadiseLabelledSource(
      "handler-source",
      "demo/SameModuleDebugExpander.scala"
    )
  )
)
```

The `demo` package, annotation, handler, source labels, and generated method
below are examples, not reserved names. For the current bounded Model A, keep
the configured `annotationName`, the handler's `annotationName`, and the
consumer's raw annotation spelling identical. In particular, a qualified
binding such as `demo.sameModuleDebug` currently requires the direct-qualified
consumer spelling `@demo.sameModuleDebug`. Imported-short canonicalization is
supported by the precompiled-handler path but is not a same-module scheduling
trigger in this bounded implementation.

A minimal fresh downstream source layout is:

```text
project/build.properties
project/plugins.sbt
build.sbt
src/main/scala/demo/SameModuleDebugAnnotation.scala
src/main/scala/demo/SameModuleDebugExpander.scala
src/main/scala/demo/SameModuleDebugUsage.scala
```

Pin the external build and use the locally installed integration:

```text
# project/build.properties
sbt.version=1.12.15
```

```scala
// project/plugins.sbt
addSbtPlugin("com.github.dmytromitin" % "sbt-macroparadise" % "0.1.1")
```

Use the `build.sbt` configuration above, then define the marker in its own
file:

```scala
// src/main/scala/demo/SameModuleDebugAnnotation.scala
package demo

import paradise3.api.expander
import scala.annotation.StaticAnnotation

@expander("demo.SameModuleDebugExpander")
final class sameModuleDebug extends StaticAnnotation
```

Define the handler in a second file. The generated string is an observable
runtime token for incremental checks:

```scala
// src/main/scala/demo/SameModuleDebugExpander.scala
package demo

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.{ExpansionChanges, ExpansionHandler, ExpansionInput, ExpansionOutcome}
import paradise3.api.helpers.ExpansionHelpers

final class SameModuleDebugExpander extends ExpansionHandler:
  override def annotationName: String =
    "demo.sameModuleDebug"

  override def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.Structured(ExpansionChanges())
```

Use it from a third file with the current direct-qualified spelling:

```scala
// src/main/scala/demo/SameModuleDebugUsage.scala
package demo

@demo.sameModuleDebug
class SameModuleUser

object SameModuleDebugUsage:
  def main(args: Array[String]): Unit =
    println(new SameModuleUser().sameModuleToken)
```

Run `sbt -batch "runMain demo.SameModuleDebugUsage"` and expect
`same-module-v1`. To check the documented incremental envelope, edit only the
handler token to `same-module-v2`, run `sbt -batch compile` without `clean`,
and run the unchanged consumer again. The output must be `same-module-v2`.

Paths are normalized relative to `Compile / scalaSource` by default. The
derived `macroParadiseSameModuleSourceIdentity` hashes each configured label,
normalized path, and exact source bytes; it is distinct from the precompiled
path's `macroParadiseExternalArtifactIdentity`. Absolute, missing, duplicate,
or source-root-escaping paths fail closed.

The marker definition, handler implementation, and every consumer must remain
in separate source files. Same-file topologies, dependency cycles, automatic
source discovery, and multiple relationships are not implemented. CLI/Zinc
qualification passes on exact Scala 3.3.8, 3.8.4, and 3.9.0. Persistent sbt BSP
and live sbt-delegated IntelliJ handler-edit qualification pass on exact Scala
3.3.8 and 3.8.4 with JDK 25 and sbt 1.12.15. The IntelliJ
qualification includes no-op builds, a handler-only edit without `clean`, a
consumer-only edit, and close/reopen with a fresh sbt session. Native JPS and
general same-module support remain false.

## Manual alternative and verification

Users who do not want the sbt integration can use the complete same-build or
published-module manual setup in
[External handler authoring](../docs/EXTERNAL_HANDLER_AUTHORING.md). Both paths
copy a self-contained build-definition `ExternalArtifactIdentity` helper and do
not depend on this sbt plugin.

The exact hyphenated-directory source fixture and all four build modes are in
[`examples/user-onboarding-three-mode-fixture`](../examples/user-onboarding-three-mode-fixture/README.md).
From the repository root, the focused external verifier runs it on the selected
exact Scala line:

```sh
sbt -Dmacroparadise.exactScalaVersion=3.8.4 -batch \
  "++3.8.4!" verifyUserOnboardingThreeModeSetup
```

Verify the integration module itself with:

```sh
sbt -batch verifyIntegrationPolicy test scripted packageSrc packageDoc
```

Neither command remotely publishes an sbt plugin, Maven artifact, tag, or
release. The bounded same-module implementation in `0.1.1` is experimentally
supported only for exact Scala 3.3.8, 3.8.4, and 3.9.0 CLI/Zinc,
plus exact 3.3.8 and 3.8.4 persistent sbt BSP and sbt-delegated IntelliJ
workflows. Precompiled handlers remain the broad/default supported
experimental path.
