# Source-built sbt integration

This opt-in sbt plugin automates the precompiled Macro-Paradise marker/handler
setup. It runs in sbt 1.x's Scala 2.12 plugin universe and has no Scala 3
runtime dependency of its own.

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
release. General same-module handler support remains outside this slice.
