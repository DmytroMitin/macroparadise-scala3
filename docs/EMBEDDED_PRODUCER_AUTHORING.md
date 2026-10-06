# Embedded producer authoring

The embedded frontend is available on current `0.2.0-SNAPSHOT` main for
source-built/local-development only. Immutable released `0.1.1` is available
remotely, but it does not contain `embeddedExpander`, the embedded producer
compiler plugin, or the embedded producer sbt integration. No `0.2.0` release
or remote `0.2.0-SNAPSHOT` product coordinate is implied here.

Macro-Paradise has two first-class precompiled authoring styles:

- **Embedded producer frontend:** an `@embeddedExpander` annotation class and
  same-file companion `transform` are compiled in one precompiled producer.
- **External marker plus handler:** a marker names a separately authored
  `ExpansionHandler` explicitly. External handler authoring remains supported
  and is useful for modular or advanced packaging.

The embedded frontend changes producer ergonomics only. Under the hood it
still uses the same `ExpansionHandler` protocol, metadata, loader, scheduler,
precheck, and Class/Trait/Object target grammar. It does not replace the
[external handler guide](EXTERNAL_HANDLER_AUTHORING.md).

## Start with the executable example

The [embedded producer starter](../examples/embedded-producer-starter/README.md)
is the preferred first use. Its actual
[build](../examples/embedded-producer-starter/build.sbt),
[producer source](../examples/embedded-producer-starter/embedded-producer/src/main/scala/starter/embedded/EmbeddedAnnotations.scala),
and [consumer](../examples/embedded-producer-starter/core/src/main/scala/starter/core/Main.scala)
are exercised on exact Scala 3.3.8, 3.8.4, and 3.9.0.

It begins with a behavior-free annotation, then adds one parameterized
generated member. The producer is independent of Quasiquotes and uses only the
current raw/helper authoring surface.

## Frozen declaration shape

The first public declaration slice is exactly a top-level annotation class plus
a same-file, same-package companion transform:

```scala
import paradise3.api.embeddedExpander
import scala.annotation.StaticAnnotation

@embeddedExpander
final class addGreeting(prefix: String) extends StaticAnnotation

object addGreeting:
  def transform(
      input: paradise3.api.ExpansionInput
  )(using dotty.tools.dotc.core.Contexts.Context): paradise3.api.ExpansionOutcome =
    // return an ExpansionOutcome through the existing handler API
    ...
```

The restrictions are intentional:

- public, top-level, `final class`;
- one direct `StaticAnnotation` parent;
- empty annotation class body;
- public, top-level, same-named companion in the same source file and package;
- one exact public concrete `transform` with the signature above;
- a precompiled producer, compiled before its consumers;
- only ordinary invariant unbounded annotation type parameters;
- no nested or local declaration;
- no same-source producer and consumer;
- no same-module consumer claim.

Multiple complete annotation/companion pairs may share a source file. Broader
parents, advanced type parameters, overloads, private transforms, and
cross-file companions are outside the current declaration contract.

## Annotation arguments are syntax

Annotation constructor arguments are pre-typer syntax, not instantiated JVM
annotation values. Decode the supported syntactic forms with:

```scala
val decoded =
  for
    application <- AnnotationApplication.fromInput(input)
    prefix <- application.requireSingleStringLiteralArgument("prefix")
  yield prefix
```

`AnnotationApplication` preserves ordered positional or named raw argument
syntax. Macro-Paradise does not:

- materialize omitted constructor defaults;
- synthesize implicits;
- evaluate arbitrary argument expressions as JVM values;
- provide semantic typing, symbol lookup, alias expansion, or subtyping at this
  phase.

`input.currentAnnotation` remains the advanced raw-tree fallback. Do not
instantiate the annotation class and expect constructor fields to contain the
source arguments.

## Generated adapter boundary

Macro-Paradise generates an ordinary public no-argument `ExpansionHandler`
adapter and existing `@expander` metadata. The adapter delegates to the
companion `transform`. Its generated adapter name is not a public contract, and
the generated adapter class is not user API. Examples, build logic, and
consumers must never need to name it.

The producer integration packages separate marker and handler roles. The
marker contains the annotation class and metadata; the handler contains the
companion transform, generated adapter, and producer helper implementation.
One physical artifact used for both roles remains unsupported.

## Install current main locally

Use JDK 25 and sbt 1.12.15. Select one exact Scala line and install all three
current compiler-facing product artifacts into machine-local Ivy:

```sh
sbt -Dmacroparadise.exactScalaVersion=3.3.8 -batch "++3.3.8!"   "pluginApi/publishLocal" "embeddedProducerPlugin/publishLocal" "plugin/publishLocal"

sbt -Dmacroparadise.exactScalaVersion=3.8.4 -batch "++3.8.4!"   "pluginApi/publishLocal" "embeddedProducerPlugin/publishLocal" "plugin/publishLocal"

sbt -Dmacroparadise.exactScalaVersion=3.9.0 -batch "++3.9.0!"   "pluginApi/publishLocal" "embeddedProducerPlugin/publishLocal" "plugin/publishLocal"
```

Install the current source-built sbt integration separately:

```sh
cd sbt-integration
sbt -batch verifyIntegrationPolicy publishLocal
```

Then a downstream `project/plugins.sbt` may select:

```scala
addSbtPlugin("com.github.dmytromitin" % "sbt-macroparadise" % "0.2.0-SNAPSHOT")
```

These commands install only to the machine-local repositories. They do not
remotely publish `0.2.0-SNAPSHOT`.

## Embedded-producer setup matrix

The following are build topologies and wiring choices, not different macro
semantics:

| Producer topology | sbt integration consumer | Manual consumer |
| --- | --- | --- |
| same-build local derived roles | [preferred same-build recipe](#preferred-same-build-sbt-integration) | [manual producer and consumer](#complete-manual-producer-and-consumer) |
| published/resolver-installed roles | [paired-module recipe](#published-or-resolver-installed-role-modules) | [manual resolved consumer](#manual-resolved-consumer) |

All four quadrants retain the same consumer compiler plugin, marker/handler
roles, complete handler closure, and content identity. Same-build local uses
task outputs and requires no producer `publishLocal`. Published/resolver mode
intentionally resolves two role modules. Producer generation and consumer
wiring remain separate responsibilities.

## Preferred same-build sbt integration

Use one normal multi-project build:

```scala
import macroparadise.sbt.{
  MacroParadiseEmbeddedProducerPlugin,
  MacroParadiseIntegration,
  MacroParadisePrecompiledPlugin
}
import MacroParadiseEmbeddedProducerPlugin.autoImport._
import MacroParadisePrecompiledPlugin.autoImport._

ThisBuild / scalaVersion := "3.8.4" // or exact 3.3.8 / 3.9.0

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

The producer compiles once. `macroParadiseEmbeddedMarkerArtifact` and
`macroParadiseEmbeddedHandlerArtifact` derive disjoint deterministic JARs.
`macroParadiseEmbeddedHandlerClasspath` begins with the direct handler and
contains its complete canonical de-duplicated runtime closure.

The static `provided->macroParadiseEmbeddedMarker` edge puts only the derived
marker on the consumer compile classpath. The handler closure remains tool-only.
No producer `publishLocal` is used. Transform and handler-dependency edits feed
the combined content identity, so Zinc invalidates the unchanged consumer when
tool behavior changes at a stable path.

The complete checked-in version is the
[starter build](../examples/embedded-producer-starter/build.sbt).

## Published or resolver-installed role modules

A producer may expose two distinct exact-full-cross modules with one version:

```text
<base>-macro-annotations
<base>-macro-handlers
```

These are modules, not classifiers. The marker is an ordinary compile/provided
role. The handler is a hidden tool role with its complete transitive closure.
Both use `CrossVersion.full`.

Explicit static facade projects can publish or stage the producer-derived
roles:

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

For a resolver consumer, select the paired modules:

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

`embeddedModuleIds` returns the same marker and handler `ModuleID` pair when a
build needs to inspect or extend the declarations. The marker module is
`Provided`; the handler module is resolved in the hidden handler configuration.

During source development, stage facades only into a task-owned local Maven or
Ivy repository. This guide does not claim that any embedded producer role
module is remotely available.

## Producer AutoPlugin keys and tasks

`MacroParadiseEmbeddedProducerPlugin` is a separate no-trigger producer plugin.
Its public surface includes:

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

The default module names are `<producer>-macro-annotations` and
`<producer>-macro-handlers`. The generator and plugin API are selected with
`CrossVersion.full` for the exact active line.

## Complete manual producer and consumer

The no-AutoPlugin producer uses the same implementation, not a second splitting
algorithm. Copy the public self-contained
[`EmbeddedProducerRoles.scala`](../sbt-integration/src/main/scala/macroparadise/sbt/EmbeddedProducerRoles.scala)
into the downstream `project/` directory, add the exact-full-cross producer
compiler plugin and plugin API, compile once, and define these tasks:

```scala
import macroparadise.sbt.EmbeddedProducerRoles

val mpVersion = "0.2.0-SNAPSHOT"
val mpApi =
  ("com.github.dmytromitin" % "macroparadise-scala3-plugin-api" % mpVersion)
    .cross(CrossVersion.full)
val mpProducer =
  ("com.github.dmytromitin" %
    "macroparadise-scala3-embedded-producer-plugin" % mpVersion)
    .cross(CrossVersion.full)

lazy val ManualEmbeddedMarker = config("manualEmbeddedMarker").hide
lazy val manualRoleInventory =
  taskKey[EmbeddedProducerRoles.RoleInventory]("Manual embedded role inventory")
lazy val manualMarkerArtifact = taskKey[File]("Manual marker role")
lazy val manualHandlerArtifact = taskKey[File]("Manual handler role")
lazy val manualHandlerClasspath = taskKey[Seq[File]]("Complete handler closure")

lazy val embeddedProducer = project
  .in(file("embedded-producer"))
  .configs(ManualEmbeddedMarker)
  .settings(inConfig(ManualEmbeddedMarker)(Defaults.configSettings))
  .settings(
    libraryDependencies ++= Seq(compilerPlugin(mpProducer), mpApi),
    Compile / scalacOptions +=
      "-Xplugin-require:macroparadise-embedded-producer",
    manualRoleInventory := {
      (Compile / compile).value
      EmbeddedProducerRoles.packageRoles(
        (Compile / classDirectory).value,
        target.value / ("manual-marker_" + scalaVersion.value + ".jar"),
        target.value / ("manual-handler_" + scalaVersion.value + ".jar"),
        strict = true
      )
    },
    manualMarkerArtifact := {
      manualRoleInventory.value
      target.value / ("manual-marker_" + scalaVersion.value + ".jar")
    },
    manualHandlerArtifact := {
      manualRoleInventory.value
      target.value / ("manual-handler_" + scalaVersion.value + ".jar")
    },
    manualHandlerClasspath := {
      val excluded = (Compile / dependencyClasspath).value.files.filter: file =>
        val name = file.getName
        name.contains("embedded-producer-plugin") ||
          name.contains("scala3-compiler") ||
          name.contains("scala3-interfaces") ||
          name.contains("tasty-core") ||
          name.contains("scala-asm") ||
          name.contains("compiler-interface") ||
          name.contains("util-interface")

      EmbeddedProducerRoles.completeHandlerClasspath(
        manualHandlerArtifact.value,
        (Compile / classDirectory).value,
        (Runtime / dependencyClasspath).value.files,
        excluded :+ manualMarkerArtifact.value,
        target.value / "manual-handler-runtime"
      )
    },
    ManualEmbeddedMarker / products := Seq(manualMarkerArtifact.value),
    ManualEmbeddedMarker / exportedProducts :=
      Seq(Attributed.blank(manualMarkerArtifact.value))
  )
```

A same-build manual consumer uses:

```scala
lazy val core = project
  .in(file("core"))
  .dependsOn(embeddedProducer % "provided->manualEmbeddedMarker")
  .settings(
    libraryDependencies += compilerPlugin(
      ("com.github.dmytromitin" % "macroparadise-scala3-plugin" % mpVersion)
        .cross(CrossVersion.full)
    ),
    Compile / scalacOptions ++= Def.task {
      val marker = (embeddedProducer / manualMarkerArtifact).value
      val handlers = (embeddedProducer / manualHandlerClasspath).value
      val identity = ExternalArtifactIdentity.combined(
        Seq("manual-embedded-marker" -> marker),
        handlers.zipWithIndex.map:
          case (file, index) => f"manual-handler-$index%04d" -> file
      )
      Seq(
        "-Xplugin-require:macroparadise",
        "-P:macroparadise:handlerClasspath=" +
          handlers.map(_.getAbsolutePath).mkString(File.pathSeparator),
        "-P:macroparadise:externalArtifactIdentity=sha256:" + identity
      )
    }.value
  )
```

Copy the current public
[`ExternalArtifactIdentity.scala`](../examples/external-handler-starter/project/ExternalArtifactIdentity.scala)
into `project/`. Do not replace it with a simplified digest: the supported
identity covers every marker artifact and the complete ordered effective
handler closure.

## Manual resolved consumer

For resolver-installed roles, call
`MacroParadiseIntegration.embeddedModuleIds` to obtain the exact-full-cross
marker/handler pair, or declare the equivalent two coordinates explicitly.
Then use the complete hidden-configuration manual consumer recipe in
[Manual published marker and handler modules](EXTERNAL_HANDLER_AUTHORING.md#manual-published-marker-and-handler-modules):

- place the marker module on the ordinary compile classpath, normally
  `% Provided`;
- resolve the handler module and closure in a hidden configuration;
- keep direct handler artifacts before transitives;
- compute the combined identity with `ExternalArtifactIdentity.scala`;
- pass `-Xplugin-require:macroparadise`, `handlerClasspath`, and
  `externalArtifactIdentity`.

The external and embedded forms share that consumer protocol. The only
difference is how the marker and handler roles were authored.

## Exact-full-cross marker policy

`MARKER_CROSS_POLICY=EXACT_FULL_CROSS_SAFE_DEFAULT`

The producer generator, marker role, handler role, facades, and consumer
coordinates all use `CrossVersion.full`. The marker role contains exact-line
TASTy; this source line makes no ordinary `_3` binary-cross promise. A future
bounded experiment may revisit marker crossing without weakening the current
default.

## Supported and unsupported scope

Current source-built `0.2.0-SNAPSHOT` supports:

- top-level final `StaticAnnotation` classes;
- same-file companion transforms;
- precompiled producers;
- derived marker and handler roles;
- exact Scala 3.3.8, 3.8.4, and 3.9.0;
- the same current Class/Trait/Object target grammar used by external handlers.

The following remain unsupported:

- same-module embedded declaration and use is unsupported;
- same-source producer plus consumer;
- nested or local embedded annotations;
- semantic evaluation of constructor arguments;
- typed `MacroAnnotation` equivalence;
- binary-cross marker guarantees;
- a remote `0.2.0` release;
- broader target shapes.

The bounded different-file same-module Model A is a separate external-handler
track. It does not make embedded declaration/use same-module.

## Verification

From the product root, the focused public starter gate for the selected exact
line is:

```sh
sbt -Dmacroparadise.exactScalaVersion=3.8.4 -batch   "++3.8.4!" verifyPublicEmbeddedProducerStarter
```

The full gate also covers all four embedded quadrants and the existing external
handler setup:

```sh
sbt -Dmacroparadise.exactScalaVersion=3.8.4 -batch   "++3.8.4!" verifyPublicProductBoundary
```
