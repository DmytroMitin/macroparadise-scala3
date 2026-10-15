# sbt integration

This opt-in sbt plugin automates the precompiled Macro-Paradise marker/handler
setup. It runs in sbt 1.x's Scala 2.12 plugin universe and has no Scala 3
runtime dependency of its own.

The published `0.1.1` integration and current `main` also contain a separate
no-trigger plugin for one bounded
same-module different-file Model A, experimentally supported only in the
enumerated exact-line workflows below. Enabling that plugin is an explicit
choice and does not change the default precompiled path.


Current source-built `0.2.0-SNAPSHOT` extends that no-trigger plugin with a
second, mutually exclusive bounded binding for one embedded producer source and
different-file consumers. It is not present in the immutable `0.1.1` release;
the complete source-built recipe appears below. The canonical
[authoring-mode and feature-parity guide](../docs/AUTHORING_MODES_AND_FEATURE_PARITY.md)
explains how this mode differs from the three other frontend/topology cells.
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

## Embedded same-build producer

Current source-built `0.2.0-SNAPSHOT` contains the producer-only
`MacroParadiseEmbeddedProducerPlugin`. It compiles one ordinary precompiled
producer and derives deterministic, disjoint marker and handler JARs plus the
complete ordered handler closure. Released `0.1.1` does not contain this
frontend, and no current embedded product or role coordinate is remotely
published.

```scala
import macroparadise.sbt.{
  MacroParadiseEmbeddedProducerPlugin,
  MacroParadiseIntegration,
  MacroParadisePrecompiledPlugin
}
import MacroParadiseEmbeddedProducerPlugin.autoImport._
import MacroParadisePrecompiledPlugin.autoImport._

val mpVersion = "0.2.0-SNAPSHOT"

lazy val embeddedProducer = project
  .in(file("embedded-producer"))
  .enablePlugins(MacroParadiseEmbeddedProducerPlugin)
  .settings(
    macroParadiseEmbeddedCompilerProductVersion := mpVersion
  )

lazy val core = project
  .in(file("core"))
  .dependsOn(
    embeddedProducer % "provided->macroParadiseEmbeddedMarker"
  )
  .enablePlugins(MacroParadisePrecompiledPlugin)
  .settings(
    MacroParadiseIntegration.precompiledEmbeddedProject(embeddedProducer),
    macroParadiseCompilerProductVersion := mpVersion
  )
```

The producer compiles once. The explicit hidden marker edge places only
`macroParadiseEmbeddedMarkerArtifact` on the consumer compile classpath.
`precompiledEmbeddedProject` creates static task edges to that marker and to
`macroParadiseEmbeddedHandlerClasspath`. The handler closure remains tool-only;
transform changes participate in content identity and Zinc invalidation. The
same-build path requires no producer `publishLocal`.

The complete executable form is
[`examples/embedded-producer-starter`](../examples/embedded-producer-starter/README.md).

## Embedded published or resolver-installed producer

A producer version may expose two role modules:

```text
<producer>-macro-annotations
<producer>-macro-handlers
```

They are distinct modules, not classifiers, because their dependency graphs are
different. Explicit static facade projects use:

```scala
lazy val markerFacade = project
  .in(file("facades/marker"))
  .settings(
    MacroParadiseIntegration.embeddedMarkerPublicationFacade(embeddedProducer)
  )

lazy val handlerFacade = project
  .in(file("facades/handler"))
  .settings(
    MacroParadiseIntegration.embeddedHandlerPublicationFacade(embeddedProducer)
  )
```

A consumer that intentionally resolves those modules uses:

```scala
lazy val core = project
  .in(file("core"))
  .enablePlugins(MacroParadisePrecompiledPlugin)
  .settings(
    MacroParadiseIntegration.precompiledEmbeddedModules(
      organization = "com.example",
      producerBaseModuleName = "my-embedded-producer",
      producerVersion = "1.0.0"
    ),
    macroParadiseCompilerProductVersion := "0.2.0-SNAPSHOT"
  )
```

`MacroParadiseIntegration.embeddedModuleIds` returns the same exact-full-cross
marker/handler pair for builds that need the raw `ModuleID` values. The marker
is `Provided`; the handler module and complete transitive closure use the hidden
handler configuration. During current development, stage facade modules only
to an intentional task-owned or machine-local resolver.

## Producer AutoPlugin keys and tasks

The producer plugin is separate from `MacroParadisePrecompiledPlugin` and uses
`noTrigger`. Its public settings/tasks are:

- `macroParadiseEmbeddedCompilerProductVersion`;
- `macroParadiseEmbeddedProducerCompilerPluginModule`;
- `macroParadiseEmbeddedPluginApiModule`;
- `macroParadiseEmbeddedMarkerArtifact`;
- `macroParadiseEmbeddedHandlerArtifact`;
- `macroParadiseEmbeddedHandlerClasspath`;
- `macroParadiseEmbeddedMarkerModuleName`;
- `macroParadiseEmbeddedHandlerModuleName`;
- `macroParadiseEmbeddedRoleInventory`;
- `macroParadiseEmbeddedStrictRoleValidation`;
- `macroParadiseEmbeddedValidate`.

The module names default to `<producer>-macro-annotations` and
`<producer>-macro-handlers`. Validation rejects unsupported Scala lines,
incorrect product coordinates, empty producers, role collisions, non-JAR
outputs, incomplete closure, and overlapping roles.

## Embedded manual alternatives

The complete no-AutoPlugin producer copies the public self-contained
[`EmbeddedProducerRoles.scala`](src/main/scala/macroparadise/sbt/EmbeddedProducerRoles.scala),
selects the exact-full-cross embedded producer compiler plugin explicitly,
compiles once, and calls `packageRoles` plus `completeHandlerClasspath`.

The complete manual consumer copies
[`ExternalArtifactIdentity.scala`](../examples/external-handler-starter/project/ExternalArtifactIdentity.scala)
and configures the ordinary consumer compiler plugin, marker compile artifact,
complete handler closure, `-Xplugin-require:macroparadise`,
`handlerClasspath`, and combined `externalArtifactIdentity` directly. These are
supported transparent paths, not fallback semantics.

See [Embedded producer authoring](../docs/EMBEDDED_PRODUCER_AUTHORING.md) for
the complete same-build and resolver-installed manual recipes.

## Exact-full-cross embedded policy

The exact supported lines are 3.3.8, 3.8.4, and 3.9.0. The producer generator,
plugin API, marker role, handler role, facade modules, and consumers all use
`CrossVersion.full`.

`MARKER_CROSS_POLICY=EXACT_FULL_CROSS_SAFE_DEFAULT`

The marker contains exact-line TASTy. Current documentation makes no ordinary
`_3` binary-cross promise. The precompiled role topology remains distinct from
the bounded same-module embedded recipe and external-handler Model A below.

## Bounded two independent external same-module recipe

This complete recipe is for unreleased source-built `0.2.0-SNAPSHOT`, not Maven
Central. Use JDK 25, sbt 1.12.15, and exact Scala 3.3.8, 3.8.4, or 3.9.0. The
example selects 3.8.4; use one exact line consistently for the source build and
downstream build.

First, from the Macro-Paradise source root, install the exact-full-cross plugin
API and compiler plugin into machine-local Ivy:

```sh
sbt -Dmacroparadise.exactScalaVersion=3.8.4 -batch \
  "++3.8.4!" \
  "pluginApi/publishLocal" \
  "plugin/publishLocal"
```

Then install the source-built sbt integration separately:

```sh
cd sbt-integration
sbt -batch verifyIntegrationPolicy publishLocal
```

These commands do not publish remotely. Create a fresh downstream directory
with exactly this six-file core source layout:

```text
project/build.properties
project/plugins.sbt
build.sbt
src/main/scala/demo/MarkerA.scala
src/main/scala/demo/HandlerA.scala
src/main/scala/demo/ConsumerA.scala
src/main/scala/demo/MarkerB.scala
src/main/scala/demo/HandlerB.scala
src/main/scala/demo/ConsumerB.scala
```

The four globally distinct producer files are separate from the two distinct consumer files. The consumers are
distinct from every producer and from each other, with one selected binding per
consumer unit.

Pin sbt and the locally installed integration:

```text
# project/build.properties
sbt.version=1.12.15
```

```scala
// project/plugins.sbt
addSbtPlugin("com.github.dmytromitin" % "sbt-macroparadise" % "0.2.0-SNAPSHOT")
```

Configure exactly two `macroParadiseSameModuleHandler` records. Reversing the
input order below is intentional: the derived repeated W1 options are sorted
deterministically.

```scala
// build.sbt
enablePlugins(macroparadise.sbt.MacroParadiseSameModulePlugin)

scalaVersion := "3.8.4" // or exact 3.3.8 / 3.9.0

macroParadiseSameModuleBindings := Seq(
  macroParadiseSameModuleHandler(
    annotationName = "demo.markerB",
    handlerClassName = "demo.HandlerB",
    markerSource = macroParadiseLabelledSource("b-marker-source", "demo/MarkerB.scala"),
    handlerSource = macroParadiseLabelledSource("b-handler-source", "demo/HandlerB.scala")
  ),
  macroParadiseSameModuleHandler(
    annotationName = "demo.markerA",
    handlerClassName = "demo.HandlerA",
    markerSource = macroParadiseLabelledSource("a-marker-source", "demo/MarkerA.scala"),
    handlerSource = macroParadiseLabelledSource("a-handler-source", "demo/HandlerA.scala")
  )
)

lazy val verifyPairConfiguration = taskKey[Unit]("Inspect the bounded pair inputs")

verifyPairConfiguration := {
  val derived = macroParadiseSameModuleConfiguration.value
  val relationships =
    derived.compilerOptions.filter(_.startsWith("-P:macroparadise:sameModuleHandler="))
  assert(relationships.size == 2)
  assert(relationships == relationships.sorted)
  assert(derived.compilerOptions.count(
    _.startsWith("-P:macroparadise:sameModuleSourceIdentity=sha256:")
  ) == 1)
  assert(derived.sourceIdentity.sources.map(_.label) == Vector(
    "a-handler-source",
    "a-marker-source",
    "b-handler-source",
    "b-marker-source"
  ))

  val pluginName = s"macroparadise-scala3-plugin_${scalaVersion.value}.jar"
  val pluginJars = (Compile / scalacOptions).value
    .filter(_.startsWith("-Xplugin:"))
    .flatMap(_.stripPrefix("-Xplugin:").split(java.io.File.pathSeparator).map(file))
    .filter(_.getName == pluginName)
  assert(pluginJars.size == 1, s"expected one exact plugin JAR, got $pluginJars")
  println("PAIR_RELATIONSHIPS=" + relationships.mkString("|"))
  println("PAIR_SOURCE_IDENTITY=sha256:" + derived.sourceIdentity.identity)
  println("PAIR_PLUGIN_PATH=" + pluginJars.head.getCanonicalPath)
}
```

The two marker declarations are ordinary user-owned `@expander` annotations:

```scala
// src/main/scala/demo/MarkerA.scala
package demo

import paradise3.api.expander
import scala.annotation.StaticAnnotation

@expander("demo.HandlerA")
final class markerA extends StaticAnnotation
```

```scala
// src/main/scala/demo/MarkerB.scala
package demo

import paradise3.api.expander
import scala.annotation.StaticAnnotation

@expander("demo.HandlerB")
final class markerB extends StaticAnnotation
```

Each handler implements the current `ExpansionHandler` protocol and returns an
`ExpansionOutcome`. The generated runtime tokens are deliberately independent:

```scala
// src/main/scala/demo/HandlerA.scala
package demo

import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Names.{termName, typeName}
import paradise3.api.*
import paradise3.api.helpers.ExpansionHelpers

final class HandlerA extends ExpansionHandler:
  val annotationName: String = "demo.markerA"

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionEdit.finish:
      ExpansionEdit.start(input).flatMap: edit =>
        ExpansionHelpers.placeMemberInPrimary(
          edit,
          untpd.DefDef(
            termName("generatedA"),
            Nil,
            untpd.Ident(typeName("String")),
            untpd.Literal(Constant("A-v1"))
          )
        )
```

```scala
// src/main/scala/demo/HandlerB.scala
package demo

import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Names.{termName, typeName}
import paradise3.api.*
import paradise3.api.helpers.ExpansionHelpers

final class HandlerB extends ExpansionHandler:
  val annotationName: String = "demo.markerB"

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionEdit.finish:
      ExpansionEdit.start(input).flatMap: edit =>
        ExpansionHelpers.placeMemberInPrimary(
          edit,
          untpd.DefDef(
            termName("generatedB"),
            Nil,
            untpd.Ident(typeName("String")),
            untpd.Literal(Constant("B-v1"))
          )
        )
```

Each consumer is in its own physical file and selects one binding with a
direct-qualified annotation. Pair mode does not accept imported short, wildcard,
alias, or inferred same-package short spellings.

```scala
// src/main/scala/demo/ConsumerA.scala
package demo

@demo.markerA
final class ConsumerA

object Main:
  def main(args: Array[String]): Unit =
    val a = new ConsumerA().generatedA
    val b = new ConsumerB().generatedB
    assert(a == args(0), s"expected A=${args(0)}, got $a")
    assert(b == args(1), s"expected B=${args(1)}, got $b")
    println(s"PAIR_RUNTIME:A=$a,B=$b")
```

```scala
// src/main/scala/demo/ConsumerB.scala
package demo

@demo.markerB
final class ConsumerB
```

Compile from a blank target, inspect the resolved exact plugin artifact and
derived options, and run both consumers:

```sh
sbt -batch clean verifyPairConfiguration compile "runMain demo.Main A-v1 B-v1"
```

The expected runtime line is `PAIR_RUNTIME:A=A-v1,B=B-v1`. The inspection must
print two sorted `sameModuleHandler` options, one
`sameModuleSourceIdentity=sha256:` option, and the actual machine-local exact
plugin path. The plugin/API artifacts are exact-full-cross JVM tools; the
handler, plugin, and consumer must all use the selected Scala line and JDK 25.

For the incremental witness, edit only `"A-v1"` to `"A-v2"` in
`HandlerA.scala`, then run without `clean`:

```sh
sbt -batch verifyPairConfiguration compile "runMain demo.Main A-v2 B-v1"
sbt -batch compile "runMain demo.Main A-v2 B-v1"
```

The first command must report A-v2 while retaining B-v1. The second command is
the second no-op witness. The setting accepts exactly two members; use
`macroParadiseSameModuleBinding` for one relationship. The singleton key, pair
key, and `macroParadiseSameModuleEmbeddedBinding` are mutually exclusive. No
separate manual same-module wiring contract is qualified. Do not infer a manual
same-module wiring contract from the precompiled recipes.

The pair remains deliberately fail closed: producer edges, same-unit ambiguity,
dynamic cross-binding, mixed embedded/external settings, three or more bindings,
same-source producer/consumer use, and non-direct spellings fail. One aggregate
identity covers the exact bytes of all four producers, so an A-only edit may
conservatively recompile B's consumer; no selective-invalidation claim is made.

As an optional qualification check, after one successful A-v2/B-v1 run, make
`HandlerA.scala` syntactically invalid and confirm `compile` fails instead of
using old output. Restore the exact valid A-v2 bytes without `clean`, rerun, and
confirm A-v2/B-v1. Exact source restore can likewise reproduce the original
aggregate digest. These recovery steps are qualification, not ordinary setup.

Pair CLI/Zinc is qualified on all three exact lines and persistent sbt BSP on
3.8.4. Live IntelliJ, native JPS, and direct Java gutter Run are not qualified
for the pair. Released `0.1.1` has no exact-two pair and no remote
`0.2.0-SNAPSHOT` exists.

## Bounded same-module embedded recipe

This recipe is for unreleased source-built `0.2.0-SNAPSHOT`, not Maven Central.
Use JDK 25, sbt 1.12.15, and exact Scala 3.3.8, 3.8.4, or 3.9.0. It keeps one
embedded annotation producer and its consumers in the same Scala module, but
the producer and every consumer remain in different physical source files.

First install the three compiler-facing artifacts for the exact Scala line you
will use. Repeat with `3.3.8`, `3.8.4`, or `3.9.0` as needed:

```sh
sbt -Dmacroparadise.exactScalaVersion=3.8.4 -batch \
  "++3.8.4!" \
  "pluginApi/publishLocal" \
  "embeddedProducerPlugin/publishLocal" \
  "plugin/publishLocal"
```

That runs `pluginApi/publishLocal`, `embeddedProducerPlugin/publishLocal`, and
`plugin/publishLocal`. Install the source-built sbt plugin separately:

```sh
cd sbt-integration
sbt -batch verifyIntegrationPolicy publishLocal
```

Create this downstream layout:

```text
project/build.properties
project/plugins.sbt
build.sbt
src/main/scala/demo/EmbeddedAnnotations.scala
src/main/scala/consumer/QualifiedUsage.scala
src/main/scala/consumer/ImportedUsage.scala
```

Pin sbt and the locally installed integration:

```text
# project/build.properties
sbt.version=1.12.15
```

```scala
// project/plugins.sbt
addSbtPlugin("com.github.dmytromitin" % "sbt-macroparadise" % "0.2.0-SNAPSHOT")
```

The build enables `MacroParadiseSameModulePlugin` and constructs
`macroParadiseSameModuleEmbeddedBinding` with `macroParadiseSameModuleEmbedded`.
Exactly one public binding is configured; users do not name the generated private adapter. The labelled
producer path is relative to `macroParadiseSameModuleSourceRoot`, which defaults
to `Compile / scalaSource`.

```scala
// build.sbt
enablePlugins(macroparadise.sbt.MacroParadiseSameModulePlugin)

scalaVersion := "3.8.4" // or exact 3.3.8 / 3.9.0

macroParadiseSameModuleEmbeddedBinding := Some(
  macroParadiseSameModuleEmbedded(
    annotationName = "demo.sameModuleGreeting",
    producerSource =
      macroParadiseLabelledSource("embedded-producer-source", "demo/EmbeddedAnnotations.scala")
  )
)
```

The producer class and its companion transform share one file. Constructor
arguments are decoded from pre-typer syntax; defaults are not materialized and
arbitrary Scala expressions are not evaluated.

```scala
// src/main/scala/demo/EmbeddedAnnotations.scala
package demo

import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Names.{termName, typeName}
import paradise3.api.*
import paradise3.api.embeddedExpander
import paradise3.api.helpers.ExpansionTransforms
import scala.annotation.StaticAnnotation

@embeddedExpander
final class sameModuleGreeting(prefix: String) extends StaticAnnotation

object sameModuleGreeting:
  private def greetingMethod(value: String)(using Context): untpd.DefDef =
    untpd.DefDef(
      termName("generatedGreeting"),
      Nil,
      untpd.Ident(typeName("String")),
      untpd.Literal(Constant(value + ":same-module-v1"))
    )

  def transform(input: ExpansionInput)(using Context): ExpansionOutcome =
    val decoded =
      for
        application <- AnnotationApplication.fromInput(input)
        prefix <- application.requireSingleStringLiteralArgument("prefix")
      yield prefix
    decoded match
      case Left(problem) => ExpansionOutcome.Rejected(List(problem))
      case Right(prefix) =>
        ExpansionEdit.finish:
          ExpansionEdit.start(input).flatMap(
            ExpansionTransforms.placeMemberInPrimary(greetingMethod(prefix))
          )
```

Both accepted consumer spellings are shown in separate source files:

```scala
// src/main/scala/consumer/QualifiedUsage.scala
package consumer

@demo.sameModuleGreeting("Hello")
final class QualifiedGreeter

object Main:
  def main(args: Array[String]): Unit =
    val qualified = new QualifiedGreeter().generatedGreeting
    val imported = new ImportedGreeter().generatedGreeting
    assert(qualified == "Hello:same-module-v1")
    assert(imported == "Welcome:same-module-v1")
    println("SAME_MODULE_EMBEDDED_RUNTIME:" + qualified.stripPrefix("Hello:"))
```

```scala
// src/main/scala/consumer/ImportedUsage.scala
package consumer

import demo.sameModuleGreeting

@sameModuleGreeting(prefix = "Welcome")
final class ImportedGreeter
```

Compile and run the downstream build:

```sh
sbt -batch compile run
```

The runtime witness is `SAME_MODULE_EMBEDDED_RUNTIME:same-module-v1`. You may
then edit only `same-module-v1` to `same-module-v2` in the producer transform
and run `compile run` again without `clean`; both consumers must print the new
value. A failed producer recompilation rejects stale generated output rather
than silently running the previous transform.

External same-module Model A remains separate. Its
`macroParadiseSameModuleBinding` and the embedded
`macroParadiseSameModuleEmbeddedBinding` are mutually exclusive. This recipe
also does not alter the four precompiled producer topology/wiring quadrants.

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
