# External handler authoring

This guide describes the MacroParadise `0.2.0-SNAPSHOT` unified handler
contract. Use JDK 25, sbt 1.12.15, and artifacts built for the exact active
Scala line: 3.3.8, 3.8.4, or 3.9.0. The recipes below intentionally select
current `0.2.0-SNAPSHOT`, whose handler protocol they document. Install its
matching plugin and API from source into local Ivy first. Released `0.1.1` is a
separate immutable artifact with its earlier handler surface; use the released
Giter8 template or versioned release documentation for that API.

## Minimal `@identity` first use

Start with a behavior-free identity annotation. Successful compilation proves
marker discovery, metadata binding, handler loading, handler invocation, and
unchanged pass-through. It deliberately proves no generated-tree authoring.

### Project roles

The first build has three distinct roles:

- **marker:** defines the annotation class and names its handler with
  `@expander`;
- **handler:** implements MacroParadise's two-method `ExpansionHandler`
  protocol in a separately compiled artifact;
- **consumer:** imports the marker and compiles the annotated application code
  with the MacroParadise compiler plugin and handler tool classpath.

Put this exact marker source in the marker project:

```scala
package com.example.`macro`.annotations

import paradise3.api.expander
import scala.annotation.StaticAnnotation

@expander("com.example.macro.handlers.IdentityHandler")
class identity extends StaticAnnotation
```

Put this exact handler source in the handler project:

```scala
package com.example.`macro`.handlers

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.{ExpansionHandler, ExpansionInput, ExpansionOutcome}

final class IdentityHandler extends ExpansionHandler:
  override def annotationName: String =
    "com.example.macro.annotations.identity"

  override def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.Expanded(List(input.primary.tree))
```

The ordinary consumer form is an explicit import followed by short
`@identity`:

```scala
package com.example.core

import com.example.`macro`.annotations.identity

@identity
class Something
```

Use the direct-qualified spelling only as a control or fallback:

```scala
package com.example.core

@com.example.`macro`.annotations.identity
class Something
```

The marker and handler producer projects both require the exact full-cross
MacroParadise API. The handler additionally requires the matching Scala
compiler because `ExpansionHandler` exposes exact Dotty types:

```scala
val mpApi =
  ("com.github.dmytromitin" % "macroparadise-scala3-plugin-api" % "0.2.0-SNAPSHOT")
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
```

Keep the Scala line exact and keep `CrossVersion.full`; `%%` does not select
the compiler-specific MacroParadise API. Install the matching current artifacts
for the selected line before using these snippets, for example:

```sh
sbt -Dmacroparadise.exactScalaVersion=3.9.0 -batch "++3.9.0!" "pluginApi/publishLocal" "plugin/publishLocal"
```

This installs MacroParadise itself; it does not publish the user's marker or
handler. Then choose either the
[sbt integration](../sbt-integration/README.md#local-marker-and-handler-projects)
or one of the complete manual recipes below to wire the consumer. The identity
sources above are also exercised by the independent external consumer fixture.

## Source-like generated members

After identity has isolated the wiring contract, the normal authoring path uses
Scalameta syntax plus Quasiquotes generated-origin lowering, then the generic
MacroParadise placement helper:

```scala
import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.*
import paradise3.api.helpers.ExpansionHelpers
import quasiquotes.definitions.dotty.ScalametaDefinitionGeneratedOriginBridge
import scala.meta.*
import scala.meta.dialects.Scala3

final class GenerateGreetingHandler extends ExpansionHandler:
  val annotationName = "example.generateGreeting"

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionEdit.finish:
      for
        edit <- ExpansionEdit.start(input)
        _ <- input.primary match
          case ExpansionTarget.Class(_) => Right(())
          case _ => Left(ExpansionDiagnostic("@generateGreeting requires a class primary", input.currentAnnotation.sourcePos))
        definition = q"""def generatedGreeting: String = "Hello, Greeter!" """.asInstanceOf[Defn.Def]
        lowered <- ScalametaDefinitionGeneratedOriginBridge
          .lower(definition, "<macroparadise-generated:GenerateGreetingHandler:generatedGreeting>")
          .left.map(error => ExpansionDiagnostic(s"${error.code}: ${error.detail}", input.currentAnnotation.sourcePos))
        result <- ExpansionHelpers.placeMemberInPrimary(edit, lowered.tree)
      yield result
```

The handler project adds
`("com.github.dmytromitin" % "quasiquotes-scala3-dotty-internal" % "0.3.0").cross(CrossVersion.full)`.
The repository starter compiles and runs this exact source. Direct `untpd`
constructors remain an expert path only.

## Structured edits

Prefer the immutable edit pipeline for ordinary changes:

```scala
def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
  val edited = for
    start <- ExpansionEdit.start(input)
    withoutCurrent <- ExpansionHelpers.replacePrimaryAnnotations(
      start,
      dotty.tools.dotc.ast.Trees.mods(input.primary.tree).annotations
        .filterNot(_ eq input.currentAnnotation)
    )
    withMember <- ExpansionHelpers.placeMemberInPrimary(
      withoutCurrent,
      authoredMember
    )
  yield withMember

  ExpansionEdit.finish(edited)
```

Available helpers place one or more caller-authored members in the primary or
companion, replace annotations, create a sibling, and prepare a trait self.
`MemberConflictPolicy.Reject` is the default; `PreserveExisting` filters
conflicting names. Missing-companion behavior is explicit:

```scala
ExpansionHelpers.placeMemberInCompanion(
  edit,
  authoredMember,
  MissingCompanionPolicy.Create(
    ExpansionTargetKind.Object,
    DefinitionPlacement.AfterPrimary
  )
)
```

A create followed by additional companion member placement remains one Create
operation. Finish exactly once; do not reconstruct an input from an outcome.
For direct structured authoring, use `ExpansionChanges` with `PrimaryChange`,
`CompanionChange`, `SiblingChange`, and nonempty ordered `TargetPatch` lists.
Unmentioned domains are preserved. Replace addresses the input occurrence and
may change its name or kind; the plugin recomputes final relationships.

## Raw output

`ExpansionOutcome.Expanded(trees)` is an exact replacement of the current
primary plus its verified companion, if any. It may return Nil, one definition,
or many Class/Trait/Object definitions in any valid order. Do not echo
unrelated siblings. There is no distinguished first result.

Use raw output only when sparse changes are insufficient. Every nonempty
returned root must carry source or span provenance. The plugin validates nulls,
target forms, recursive ownership aliases, collisions, and final topology
atomically; it never fabricates or repairs provenance.

## Input views and scheduling

`ExpansionInput.primary` and `companion` contain exact-version raw trees wrapped
by target kind. `container.occupiedDefinitionNames` is the actual set of named
definitions in the current staged package container, including the primary,
companion, and unrelated definitions. The plugin mints both input and context
values; external code can read but cannot construct or copy them. `targetView`,
`targetBodyView`, and `targetTypeStructureView` expose bounded normalized views
for current class/trait use cases.

`AnnotationApplication.fromInput(input)` normalizes the raw constructor or
application shape used by the typed-label fixture. It is syntactic: it does not
resolve defaults, fold constants, or supply semantic types. Return
`ExpansionOutcome.Rejected(List(ExpansionDiagnostic(...)))` for controlled
failures. A rejection list must be nonempty.

There is no composition switch. All handled annotations participate in the
current-staged-tree scheduler. After each successful stage the plugin rescans
from the beginning. A handler should remove its current annotation when it
wants the final source tree to omit it, but the private identity ledger ensures
preserving that exact tree does not invoke it twice.

Fresh handled annotations introduced on replacements, siblings, or companions
run normally. Syntax-equivalent fresh annotations are new work. Recursive
generation is allowed. The default 256-success operational safeguard can be
changed with `-P:macroparadise:expansionBudget=<positive-decimal>`; invalid,
duplicate, nonpositive, or overflowing values fail before mutation. Exhaustion
is a diagnostic and whole-unit rollback.

## AutoPlugin to manual translation

The AutoPlugin and manual builds are not different MacroParadise modes. The
manual forms below spell out the same work that `sbt-macroparadise` derives:

| `sbt-macroparadise` surface | Manual responsibility |
|---|---|
| `MacroParadiseIntegration.precompiledProjects(...)` | Declare the explicit marker project dependency, package local marker and handler projects, build the complete handler closure, compute content identity, and emit the compiler options. |
| `macroParadiseMarkerModules := ...` | Add ordinary compile-time marker module dependencies and resolve the exact marker artifacts used for identity. |
| `macroParadiseHandlerModules := ...` | Resolve handler modules in a tool-only configuration, with direct handlers first and their complete transitive closure retained. |
| integration-derived invalidation identity | Call `ExternalArtifactIdentity.combined(...)` over the marker artifacts and ordered effective handler classpath. |
| integration-derived compiler options | Emit explicit `-Xplugin-require`, `handlerClasspath`, and `externalArtifactIdentity` scalac options. |

## ExternalArtifactIdentity helper

For either no-AutoPlugin recipe, copy the public
[`ExternalArtifactIdentity.scala`](../examples/external-handler-starter/project/ExternalArtifactIdentity.scala)
source unchanged into the downstream build's
`project/ExternalArtifactIdentity.scala`. It is self-contained Scala 2.12 sbt
build-definition code, so `build.sbt` can call `ExternalArtifactIdentity`
directly without a package import.

The helper validates nonempty roles, real regular JARs, labels, role separation,
and canonical de-duplication. Its result hashes the explicit marker artifact(s)
and the complete ordered effective handler expansion classpath. This is a Zinc
build-option identity, not a security or artifact-signing claim: when bytes at a
stable path change, the option changes and invalidates consumer compilation.

## Manual same-build local projects

This is the no-`sbt-macroparadise` same-build equivalent, and the manual translation of `MacroParadiseIntegration.precompiledProjects(macroAnnotations, macroHandlers)`.
Marker and handler projects are packaged directly; neither needs
`publishLocal`.

After copying the helper, this compact one-marker/one-handler `build.sbt` is
copy/pasteable:

```scala
import java.io.File

ThisBuild / scalaVersion := "3.9.0" // or exact 3.3.8 / 3.8.4

val mpVersion = "0.2.0-SNAPSHOT"
val mpOrg = "com.github.dmytromitin"
val mpApi =
  (mpOrg % "macroparadise-scala3-plugin-api" % mpVersion)
    .cross(CrossVersion.full)
val mpPlugin =
  (mpOrg % "macroparadise-scala3-plugin" % mpVersion)
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
    libraryDependencies += compilerPlugin(mpPlugin),
    Compile / scalacOptions ++= {
      val markerJar = (macroAnnotations / Compile / packageBin).value
      val handlerJar = (macroHandlers / Compile / packageBin).value
      val handlerClasses =
        (macroHandlers / Compile / classDirectory).value.getCanonicalFile
      val handlerClasspath = handlerJar +:
        (macroHandlers / Runtime / dependencyClasspath).value.files
          .filterNot(_.getCanonicalFile == handlerClasses)
      val identity = ExternalArtifactIdentity.combined(
        Seq("marker" -> markerJar),
        handlerClasspath.zipWithIndex.map { case (file, index) =>
          f"handler-$index%04d" -> file
        }
      )
      Seq(
        "-Xplugin-require:macroparadise",
        s"-P:macroparadise:handlerClasspath=${handlerClasspath.map(_.getAbsolutePath).mkString(File.pathSeparator)}",
        s"-P:macroparadise:externalArtifactIdentity=sha256:$identity"
      )
    }
  )
```

The explicit `.dependsOn(macroAnnotations % "provided->compile")` edge puts
the marker API on the consumer compile classpath. `provided->compile` is the
normal marker-only mapping because that API need not become an application
runtime dependency; use a plain dependency if the marker project deliberately
contains runtime-bearing application API.

`Compile / packageBin` makes marker content part of build-only identity. The
handler path starts with the packaged direct handler, then keeps its complete
ordered runtime/dependency closure. The class-directory representation of the
same handler project is excluded so the loader does not receive duplicate
representations. `handlerClasspath` is a compiler/tool classpath, not an
ordinary application dependency.

The larger executable
[`manual/build.sbt`](../examples/user-onboarding-three-mode-fixture/manual/build.sbt)
adds assertions for marker compile visibility, marker/handler runtime absence,
the identity and generated consumers, and all three supported exact Scala
lanes.

## Manual published marker and handler modules

This recipe is for marker and handler artifacts that are genuinely published
or deliberately installed in a resolver. That producer state is independent
of whether marker/handler modules use a remote repository or local Ivy. This
current-protocol recipe still uses the separately locally installed
MacroParadise `0.2.0-SNAPSHOT` plugin.

The following is the manual translation of `macroParadiseMarkerModules := markerModules` and `macroParadiseHandlerModules := handlerModules`.
It keeps handlers in a hidden tool-only configuration instead of ordinary
application `libraryDependencies`:

```scala
import java.io.File
import scala.collection.mutable

ThisBuild / scalaVersion := "3.9.0" // or exact 3.3.8 / 3.8.4

val mpVersion = "0.2.0-SNAPSHOT"
val producerVersion = "1.0.0"
val mpPlugin =
  ("com.github.dmytromitin" % "macroparadise-scala3-plugin" % mpVersion)
    .cross(CrossVersion.full)

val MacroParadiseHandler = config("macroParadiseHandler").hide
lazy val markerArtifacts =
  taskKey[Seq[(String, File)]]("Exact published marker inventory")
lazy val handlerClasspath =
  taskKey[Seq[(String, File)]]("Complete published handler closure")
lazy val externalArtifactIdentity =
  taskKey[String]("Combined marker and handler identity")

val markerModules = Seq(
  (("com.example" % "my-macro-annotations" % producerVersion)
    .cross(CrossVersion.full)) % Provided
)
val handlerModules = Seq(
  ("com.example" % "my-macro-handlers" % producerVersion)
    .cross(CrossVersion.full)
)

def resolveConfigured(
    modules: Seq[ModuleID],
    classpath: Classpath,
    role: String
): Seq[(String, File)] =
  modules.flatMap { requested =>
    val matches = classpath.filter(_.get(moduleID.key).exists { actual =>
      actual.organization == requested.organization &&
      (actual.name == requested.name ||
        actual.name.startsWith(requested.name + "_")) &&
      actual.revision == requested.revision
    })
    require(
      matches.nonEmpty,
      s"configured $role module did not resolve: ${requested.organization}:${requested.name}:${requested.revision}"
    )
    matches.zipWithIndex.map { case (entry, index) =>
      s"${requested.organization}:${requested.name}:${requested.revision}:$index" ->
        entry.data
    }
  }

def completeHandlers(
    modules: Seq[ModuleID],
    classpath: Classpath
): Seq[(String, File)] = {
  val direct = resolveConfigured(modules, classpath, "handler")
  val directPaths = direct.map(_._2.getCanonicalFile).toSet
  val transitive = classpath.iterator
    .filterNot(entry => directPaths(entry.data.getCanonicalFile))
    .zipWithIndex
    .map { case (entry, index) =>
      val coordinate = entry.get(moduleID.key)
        .map(module => s"${module.organization}:${module.name}:${module.revision}")
        .getOrElse(entry.data.getName)
      f"transitive-$index%04d:$coordinate" -> entry.data
    }
    .toVector
  val seen = mutable.LinkedHashSet.empty[File]
  (direct ++ transitive).filter { case (_, file) =>
    seen.add(file.getCanonicalFile)
  }
}

lazy val core = (project in file("core"))
  .configs(MacroParadiseHandler)
  .settings(inConfig(MacroParadiseHandler)(Defaults.configSettings))
  .settings(
    libraryDependencies += compilerPlugin(mpPlugin),
    libraryDependencies ++= markerModules,
    libraryDependencies ++= handlerModules.map(_ % MacroParadiseHandler.name),
    markerArtifacts := resolveConfigured(
      markerModules,
      (Compile / dependencyClasspath).value,
      "marker"
    ),
    handlerClasspath := completeHandlers(
      handlerModules,
      (MacroParadiseHandler / dependencyClasspath).value
    ),
    externalArtifactIdentity := ExternalArtifactIdentity.combined(
      markerArtifacts.value,
      handlerClasspath.value
    ),
    Compile / scalacOptions ++= Seq(
      "-Xplugin-require:macroparadise",
      "-P:macroparadise:handlerClasspath=" +
        handlerClasspath.value.map(_._2.getAbsolutePath).mkString(File.pathSeparator),
      "-P:macroparadise:externalArtifactIdentity=sha256:" +
        externalArtifactIdentity.value
    )
  )
```

Marker modules are ordinary consumer compilation dependencies. `% Provided` is
the normal marker-only form: the resolved marker JAR appears in
`Compile / dependencyClasspath` and participates in identity without becoming
an application runtime dependency. `resolveConfigured` fails closed if an
exact configured coordinate does not resolve.

Handler modules belong in `MacroParadiseHandler`, not the consumer's normal
configuration. `completeHandlers` resolves every direct handler first, then
retains the canonical de-duplicated transitive dependency closure in resolver
order. Simply putting handlers on ordinary `libraryDependencies`, or finding
only one direct JAR on disk, loses the tool/runtime isolation and complete
loader contract.

The executable
[`manual-published/build.sbt`](../examples/user-onboarding-three-mode-fixture/manual-published/build.sbt)
expands this recipe to two direct markers, two direct handlers, and one shared
handler runtime. It proves direct-first ordering, exactly-once shared closure,
fail-closed missing coordinates/dependencies, runtime isolation, identity, and
all three explicit compiler options.

## Compiler options and equivalence

Both manual routes emit exactly one of each:

```text
-Xplugin-require:macroparadise
-P:macroparadise:handlerClasspath=<complete path>
-P:macroparadise:externalArtifactIdentity=sha256:<digest>
```

The first makes activation fail closed. The second is the complete ordered
compiler/tool classpath used to load handlers and their dependencies. The third
changes when any explicit marker or effective handler artifact changes at a
stable path, giving Zinc reliable incremental invalidation. These are the raw
settings derived by the AutoPlugin, not a second semantic mode.

## Executable documentation qualification

Run the focused identity consumer and four-mode onboarding gates from the
MacroParadise repository root:

```text
sbt -batch verifyIndependentExternalSbtConsumerFromLocalRepository
sbt -batch verifyUserOnboardingThreeModeSetup
sbt -batch verifyPublicDocumentationPolicy
```

The independent consumer compiles the marker, handler, imported-short
consumer, and direct-qualified control. The onboarding gate executes the two
manual recipes plus their AutoPlugin equivalents with task-owned repositories
and copied identity helpers. The documentation policy fails if this guide
regresses to fixture links without the identity sources, either inline manual
translation, or the AutoPlugin/manual mapping.

## Limits

The handler API is experimental and compiler-internal. It is not binary
compatible across exact Scala lines. Scheduling does not enable nested/local
targets or definitions outside Class/Trait/Object. Handlers do not receive
transaction handles, mutable container access, semantic symbols, or a
general-purpose tree-authoring layer. Remote publishing is not required for
same-build local marker/handler authoring or repository qualification.
