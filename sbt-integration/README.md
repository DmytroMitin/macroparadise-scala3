# Source-built sbt integration

This opt-in sbt plugin automates the precompiled Macro-Paradise marker/handler
setup. It runs in sbt 1.x's Scala 2.12 plugin universe and has no Scala 3
runtime dependency of its own.

Unreleased `main` also contains a separate no-trigger plugin for one bounded
same-module different-file Model A, experimentally supported only in the
enumerated exact-line workflows below. Enabling that plugin is an explicit
choice and does not change the default precompiled path.

It selects exact-full-cross compiler plugin and authoring API coordinates,
keeps published handlers in a hidden configuration, and derives Zinc
compiler-option identity from every explicit marker artifact plus the complete
ordered handler expansion classpath. The consumer still declares its ordinary
marker dependency.

## Install the current source-built snapshot

The integration is unreleased. There is no remote sbt-plugin artifact for
`0.1.1-SNAPSHOT`. From this repository checkout, install it deliberately to
local Ivy:

```sh
cd sbt-integration
sbt -batch verifyIntegrationPolicy publishLocal
```

Then add this file to the downstream build:

```scala
// project/plugins.sbt
addSbtPlugin("com.github.dmytromitin" % "sbt-macroparadise" % "0.1.1-SNAPSHOT")
```

The compiler plugin and plugin API for the selected exact Scala line must also
be resolvable. For unreleased main, install those from the repository root as
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

ThisBuild / scalaVersion := "3.3.8" // or exact 3.8.4

val mpVersion = "0.1.1-SNAPSHOT"
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
  .dependsOn(macroAnnotations)
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
dependencies. It deliberately does not infer `.dependsOn(macroAnnotations)`.
That edge is what puts marker classes on the consumer compile/runtime
classpath; the handler implementation remains a compile-time expansion tool.

## Published marker and handler modules

Use this mode only when producer artifacts are genuinely published or
deliberately installed into a resolver such as local Ivy. Producers should use
exact-full-cross artifact names:

```scala
// producer build settings
ThisBuild / scalaVersion := "3.3.8" // or exact 3.8.4

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

ThisBuild / scalaVersion := "3.3.8" // or exact 3.8.4

lazy val core = (project in file("core"))
  .enablePlugins(macroparadise.sbt.MacroParadisePrecompiledPlugin)
  .settings(
    macroParadiseCompilerProductVersion := "0.1.1-SNAPSHOT",
    macroParadiseMarkerModules := Seq(
      ("com.example" % "my-macro-annotations" % "1.0.0")
        .cross(CrossVersion.full)
    ),
    macroParadiseHandlerModules := Seq(
      ("com.example" % "my-macro-handlers" % "1.0.0")
        .cross(CrossVersion.full)
    )
  )
```

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

This path is unreleased and experimentally supported only within its bounded
qualified workflows. It is deliberately separate from
`MacroParadisePrecompiledPlugin`, accepts exactly one explicit relationship,
and supports only exact Scala 3.3.8 or 3.8.4:

```scala
import macroparadise.sbt.MacroParadiseSameModulePlugin

enablePlugins(MacroParadiseSameModulePlugin)

scalaVersion := "3.8.4" // or exact 3.3.8

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
addSbtPlugin("com.github.dmytromitin" % "sbt-macroparadise" % "0.1.1-SNAPSHOT")
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
import paradise3.api.{ExpansionInput, ExpansionOutcome, ParadiseAnnotationExpander}
import paradise3.api.helpers.ExpansionHelpers

final class SameModuleDebugExpander extends ParadiseAnnotationExpander:
  override def annotationName: String =
    "demo.sameModuleDebug"

  override def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionHelpers.addStringMethodToClass(
      input,
      methodName = "sameModuleToken",
      value = "same-module-v1"
    )
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
source discovery, and multiple relationships are not implemented. CLI/Zinc,
persistent sbt BSP, and live sbt-delegated IntelliJ handler-edit qualification
pass on both exact compiler lines with JDK 25 and sbt 1.12.15. The IntelliJ
qualification includes no-op builds, a handler-only edit without `clean`, a
consumer-only edit, and close/reopen with a fresh sbt session. Native JPS and
general same-module support remain false.

## Manual alternative and verification

Users who do not want the sbt integration can use the complete manual setup in
[External handler authoring](../docs/EXTERNAL_HANDLER_AUTHORING.md). That path
copies a self-contained build-definition `ExternalArtifactIdentity` helper and
does not depend on this sbt plugin.

The exact hyphenated-directory source fixture and all three build modes are in
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
release. The bounded same-module implementation remains unreleased and is
experimentally supported only for its enumerated exact-line CLI/Zinc,
persistent sbt BSP, and sbt-delegated IntelliJ workflows. Precompiled handlers
remain the broad/default supported experimental path.
