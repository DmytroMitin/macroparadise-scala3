# External handler authoring

This guide describes the MacroParadise 0.2.0-SNAPSHOT unified handler contract. Use JDK 25, sbt 1.12.15, and an API artifact built for the exact active Scala line: 3.3.8, 3.8.4, or 3.9.0.

## Minimal handler

```scala
package example

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.*

final class MarkerHandler extends ExpansionHandler:
  val annotationName = "example.Marker"

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionEdit.finish(ExpansionEdit.start(input))
```

The annotation identity must match marker metadata or explicit configuration.
Applicability belongs in `expand`: pattern-match the current target and return a
nonempty `Rejected` diagnostic for unsupported kinds or shapes.

## Source-like generated members

The normal authoring path uses Scalameta syntax plus Quasiquotes generated-origin
lowering, then the generic MacroParadise placement helper:

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

## Marker metadata

```scala
package example

import paradise3.api.expander
import scala.annotation.StaticAnnotation

@expander("example.MarkerHandler")
final class Marker extends StaticAnnotation
```

The metadata carrier is runtime-visible. Discovery loads the selected handler from the configured handler classpath using the shared parent-first API identity. A handler and marker may be packaged together or separately, but the consumer must see the marker and the compiler process must see the handler and its dependencies.

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

Available helpers place one or more caller-authored members in the primary or companion, replace annotations, create a sibling, and prepare a trait self. `MemberConflictPolicy.Reject` is the default; `PreserveExisting` filters conflicting names. Missing-companion behavior is explicit:

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

A create followed by additional companion member placement remains one Create operation. Finish exactly once; do not reconstruct an input from an outcome.

For direct structured authoring, use `ExpansionChanges` with `PrimaryChange`, `CompanionChange`, `SiblingChange`, and nonempty ordered `TargetPatch` lists. Unmentioned domains are preserved. Replace addresses the input occurrence and may change its name or kind; the plugin recomputes final relationships.

## Raw output

`ExpansionOutcome.Expanded(trees)` is an exact replacement of the current primary plus its verified companion, if any. It may return Nil, one definition, or many Class/Trait/Object definitions in any valid order. Do not echo unrelated siblings. There is no distinguished first result.

Use raw output only when sparse changes are insufficient. Every nonempty returned
root must carry source or span provenance. The plugin validates nulls, target
forms, recursive ownership aliases, collisions, and final topology atomically;
it never fabricates or repairs provenance.

## Input views and annotation arguments

`ExpansionInput.primary` and `companion` contain exact-version raw trees wrapped
by target kind. `container.occupiedDefinitionNames` is the actual set of named
definitions in the current staged package container, including the primary,
companion, and unrelated definitions. The plugin mints both input and context values;
external code can read but cannot construct or copy them. `targetView`,
`targetBodyView`, and `targetTypeStructureView` expose bounded normalized views
for current class/trait use cases.

`AnnotationApplication.fromInput(input)` normalizes the raw constructor/application shape used by the typed-label fixture. It is syntactic: it does not resolve defaults, fold constants, or supply semantic types.

Return `ExpansionOutcome.Rejected(List(ExpansionDiagnostic(...)))` for controlled failures. A rejection list must be nonempty.

## Scheduling consequences

There is no composition switch. All handled annotations participate in the current-staged-tree scheduler. After each successful stage the plugin rescans from the beginning. A handler should remove its current annotation when it wants the final source tree to omit it, but the private identity ledger ensures preserving that exact tree does not invoke it twice.

Fresh handled annotations introduced on replacements, siblings, or companions
run normally. Syntax-equivalent fresh annotations are new work. Recursive
generation is allowed. The default 256-success operational safeguard can be
changed with `-P:macroparadise:expansionBudget=<positive-decimal>`; invalid,
duplicate, nonpositive, or overflowing values fail before mutation. Exhaustion
is a diagnostic and whole-unit rollback.

## Packaging and checks

The handler build needs the exact MacroParadise API artifact and the matching Scala compiler dependency. Consumer compilation needs:

```scala
libraryDependencies +=
  "com.github.dmytromitin" % "macroparadise-scala3-plugin-api" % "0.2.0-SNAPSHOT"
    .cross(CrossVersion.full)
```

- the matching compiler plugin artifact via `-Xplugin`;
- the handler artifact and its dependencies on `handlerClasspath`;
- the marker artifact on the ordinary compilation classpath;
- `-Xplugin-require:macroparadise` for fail-closed activation.

For manual compiler wiring, pass the complete ordered handler path with
`-P:macroparadise:handlerClasspath=/path/to/handler.jar`.

Run the repository starter and independent packaged-consumer gates as executable examples:

```text
sbt -batch verifyExternalHandlerAuthoringStarter
sbt -batch verifyIndependentPrecompiledHandlerPackagedConsumer
```

Remote publishing is not required for local authoring or qualification. The repository's canonical verification uses task-owned local artifacts and repositories.

Two executable manual sbt stories are retained by
`verifyUserOnboardingThreeModeSetup`:

- local/unpublished development: `examples/user-onboarding-three-mode-fixture/manual/build.sbt`
  wires the current packaged plugin/API and project-local marker/handler outputs
  without `publishLocal`;
- repository-published consumption:
  `examples/user-onboarding-three-mode-fixture/manual-published/build.sbt`
  resolves producer POMs and JARs from a task-owned file repository before
  compiling and running the consumer.

Run `sbt -batch verifyUserOnboardingThreeModeSetup` for both stories plus the
AutoPlugin counterparts. The source-like handler dependency is explicit and may
resolve from Maven Central; it is never inferred from a peer checkout or cache.

## Limits

The handler API is experimental and compiler-internal. It is not binary
compatible across exact Scala lines. Scheduling does not enable nested/local
targets or definitions outside Class/Trait/Object. Handlers do not receive
transaction handles, mutable container access, semantic symbols, or a
general-purpose tree-authoring layer.
