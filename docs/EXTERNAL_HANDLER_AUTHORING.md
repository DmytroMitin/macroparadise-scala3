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

Choose one of two top-level setups:

1. **Use the sbt integration (recommended normal path).** Install the current
   unreleased plugin from source with `cd sbt-integration && sbt -batch
   verifyIntegrationPolicy publishLocal`, then add
   `addSbtPlugin("com.github.dmytromitin" % "sbt-macroparadise" %
   "0.1.1-SNAPSHOT")` in `project/plugins.sbt`. Use the same-build
   `precompiledProjects` helper without producer `publishLocal`, or use the
   published-module keys when the producers really are resolved modules. The
   [integration guide](../sbt-integration/README.md) contains the complete,
   mechanically verified builds for both submodes. The sbt-plugin snapshot is
   not remotely published today.
2. **Use the fully manual setup below.** It exposes every compiler input and
   remains the supported transparent escape hatch. It does not load or depend
   on `sbt-macroparadise`.

The manual build must copy
[`ExternalArtifactIdentity.scala`](../examples/external-handler-starter/project/ExternalArtifactIdentity.scala)
into its own `project/ExternalArtifactIdentity.scala`. This is self-contained
sbt build-definition source, not a published library. Its compiler option is
required for the supported incremental contract: a one-shot clean compile may
work without it, but stable-path handler-body, handler-dependency, or marker
metadata edits may otherwise leave Zinc or BSP consumers stale.

Use JDK feature version 25 and select exact Scala `3.3.8`, `3.8.4`, or `3.9.0`. The
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
lazy val core = (project in file("core"))
  .dependsOn(macroAnnotations)
  .settings(
    libraryDependencies += compilerPlugin(macroparadisePlugin),
    Compile / scalacOptions ++= {
      val markerJar = (macroAnnotations / Compile / packageBin).value
      val handlerJar = (macroHandlers / Compile / packageBin).value
      val handlerClasses = (macroHandlers / Compile / classDirectory).value.getCanonicalFile
      val handlerClasspath = handlerJar +:
        (macroHandlers / Runtime / dependencyClasspath).value.files
          .filterNot(_.getCanonicalFile == handlerClasses)
      val buildIdentity = ExternalArtifactIdentity.combined(
        Seq("marker" -> markerJar),
        handlerClasspath.zipWithIndex.map { case (file, index) =>
          f"handler-$index%04d" -> file
        }
      )
      Seq(
        "-Xplugin-require:macroparadise",
        s"-P:macroparadise:handlerClasspath=${handlerClasspath.map(_.getAbsolutePath).mkString(java.io.File.pathSeparator)}",
        s"-P:macroparadise:externalArtifactIdentity=sha256:$buildIdentity"
      )
    }
  )
```

`ExternalArtifactIdentity` is the small SHA-256 helper in the checked-in
starter's [`project` directory](../examples/external-handler-starter/project/ExternalArtifactIdentity.scala).
It hashes every labelled marker-role artifact and the complete ordered labelled
handler expansion classpath, then hashes that deterministic manifest into one
lowercase value. The token exists to change Zinc compiler-option identity; it
is not a security or artifact-integrity signature.

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
Dotty can report unresolved-import and cyclic-completion diagnostics around the
missing annotation; those compiler diagnostics are independent of
Macro-Paradise's pre-typer identity resolver.

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

Unreleased `0.1.1-SNAPSHOT` includes the additive
`ExpansionTargetProfile.RestrictedOrTwoUpperBoundedGenericTrait` case for one
handler that must accept exactly either the established one-invariant ordinary
unbounded trait envelope or the established two-invariant ordinary upper-
bounded trait envelope. This is a dedicated closed union: handlers still
return one `ExpansionTargetProfile`, and no arbitrary profile collections,
predicates, callbacks, or boolean profile algebra are exposed. The plugin
retains all admission authority and rejects targets outside both constituent
profiles before handler invocation.

## Generated-output follow-on

After the identity smoke test passes, the user-authored `@gen` from the
[README](../README.md#a-small-user-authored-example) is the smallest generated-
output example. The same independent external sbt verifier compiles its literal
marker, `GenHandler`, and consumer and checks that
`new GenUser().generatedHello` typechecks.

The executable
[`generateGreeting` starter](../examples/external-handler-starter/README.md)
is a more extensive follow-on. Its handler uses `ExpansionHelpers` to add
`generatedGreeting`, and the consumer typechecks and runs that generated member.
It proves transformation output in addition to the same marker, handler, and
consumer wiring; generated helpers are not required merely to prove discovery
and invocation.

```sh
sbt -batch verifyExternalHandlerAuthoringStarter
```

## Practical handler-authoring progression

Use the public surface in layers, adding only one new responsibility at a
time:

1. Start with the [`@identity`](#handler) handler. It proves discovery,
   metadata binding, loading, and invocation while returning the primary
   unchanged.
2. Move to the user-authored [`@gen`](#generated-output-follow-on) example. It
   proves one narrow Macro-Paradise-generated method and ordinary downstream
   typing.
3. For ordinary new concrete `def`/`val` syntax, author with Scalameta, lower
   with Quasiquotes' generated-origin bridge, and place the exact result with
   [`placeMembersInPrimary`](#placing-authored-concrete-definitions).
4. Change only the placement call to `placeMembersInCompanion` when the same
   generated definition belongs in the companion.
5. Study the [real downstream AUXify examples](#real-downstream-examples) for
   bounded source decoding, specialized lowering, lifecycle, placement, and
   source-ordered composition.

`@addFoo` below is a user-defined teaching example, not an annotation shipped
by Macro-Paradise. Macro-Paradise supplies the handler contract and placement
helpers; the marker, handler policy, and annotation semantics belong to the
author.

## API boundary

`ExpansionInput` exposes compiler-sensitive untyped trees and bounded decoded
views. `annotatedClassView` covers the top-level class and primary constructor;
the unreleased `0.1.1-SNAPSHOT` `annotatedClassBodyView` covers ordered direct
members and normalized direct-method structure; and
`annotatedClassTypeStructureView` separately covers enclosing type-parameter
bounds plus direct type members without changing the existing view carriers.
These are syntactic,
pre-typer, read-only views. The body view distinguishes absent, empty, ordinary, and
contextual parameter clauses; retains parameter order, defaults, visibility,
annotations, positions, and abstract/concrete status; and classifies vals,
vars, type members, nested definitions, and other direct members.

`AnnotatedClassTypeStructureView.Bound` distinguishes an absent source bound
from a present bound. A present bound contains the shared `DirectTypeShape`, so
a supported simple source bound such as `Nat` is
`Present(NamedType("Nat", pos))`, while applied, qualified, malformed, and
other broader present forms remain `Present(Unsupported(...))`. Direct type
members retain their body index and distinguish abstract bounds from aliases;
their type parameters, meaningful lower bounds, visibility, annotations, and
unsupported modifiers remain explicit for consumer-owned rejection.

`AnnotatedClassBodyView.DirectTypeShape.EnclosingTypeParameter` is only a
syntactic reference to an enclosing class type parameter. The separate
`NamedType(name, pos)` case is likewise syntactic: it carries one unqualified
simple type name such as `String`, but does not resolve that name to
`java.lang.String`, `scala.Predef.String`, a package symbol, or an alias, and
does not imply type equality or fully qualified identity. Applied, qualified,
refined, function, method-local, inferred, and other broader type forms remain
explicit `Unsupported` values for controlled consumer rejection. The decoder
performs no typing, symbol or owner lookup, inheritance, alias expansion,
subtype checking, or semantic overload analysis. Advanced exact-compiler
handlers retain raw `ExpansionInput.annotatedClass` as the explicit escape
hatch. Released `0.1.0` does not contain these body-view or type-structure APIs.

`ExpansionHelpers.withAnnotatedClassView` remains the small fail-closed adapter
for the common class shape. Helper methods can place an already-authored batch
of concrete methods and immutable values in the current primary or companion,
add one bounded string method, place one already-created type definition or
module in a companion, or create one sibling class. Advanced exact-compiler
handlers may still use `ExpansionInput.annotatedClass` as the separate raw-tree
escape hatch.

Successful handlers may return structured output with explicit primary,
companion, and additional-definition roles. The ordered raw-tree outcome
remains available for unusual shapes, but it is validated and does not bypass
plugin-owned conflicts, composition rules, or rollback.

The contract is exact-compiler experimental API. It does not promise typed
trees, stable owners, semantic names, a general compiler-independent AST,
cross-version binary compatibility, or general definition builders.

### Placing authored concrete definitions

Unreleased `0.1.1-SNAPSHOT` development sources provide the generic bounded
placement pair:

```scala
ExpansionHelpers.placeMembersInPrimary(input, generatedMembers)
ExpansionHelpers.placeMembersInCompanion(input, generatedMembers)
```

`generatedMembers` must be a non-empty `List[untpd.MemberDef]` whose entries
are all non-null `untpd.DefDef` or `untpd.ValDef` trees with usable
non-constructor term names and a usable root source attachment or span. The
helper never parses, lowers, rebuilds, positions, re-sources, or repairs those
members. A handler can therefore author definitions in a separate library,
obtain insertion-ready raw trees, and hand those exact trees to Macro-Paradise
for placement.

Primary placement appends the complete batch to the current admitted class or
plain zero-parameter trait Template. Companion placement reuses the plugin's
existing companion lease: it creates a same-name object when absent or copies
the leased companion and appends the batch after all existing body members.
Both helpers preserve original constructor, parents, self, modifiers, source
position, body order, and unrelated annotations.

Validation completes before any Template copy is formed. A missing usable root
source attachment and span, a direct existing `DefDef`, `ValDef`, or
`ModuleDef` with the same raw term name, a duplicate generated name, or an
unsupported member kind rejects the whole batch. Method overloading is
intentionally rejected by raw name: this pre-typer helper does not attempt
typed signature resolution. Rejection returns the exact original annotated
primary fallback, so plugin-owned companion leasing and transaction rollback
cannot expose a partial insertion.

The Macro-Paradise plugin and plugin API retain no Scalameta or Quasiquotes
runtime dependency. Those tools are optional authoring layers used by a
separate handler build against the exact matching Scala line.

For direct insertion from the optional Quasiquotes authoring library, use its
generated-origin bridge rather than its source-free representation bridge:

```scala
import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.{ExpansionInput, ExpansionOutcome, ParadiseAnnotationExpander}
import paradise3.api.helpers.ExpansionHelpers
import quasiquotes.definitions.dotty.ScalametaDefinitionGeneratedOriginBridge
import scala.meta.*
import scala.meta.dialects.Scala3

final class AddFooHandler extends ParadiseAnnotationExpander:
  override def annotationName: String =
    "com.example.macros.annotations.addFoo"

  override def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ScalametaDefinitionGeneratedOriginBridge.lower(
      q"def foo(x: Int): String = x.toString",
      "addFoo.generated.scala"
    ) match
      case Right(lowered) =>
        ExpansionHelpers.placeMembersInPrimary(input, List(lowered.tree))
      case Left(problem) =>
        ExpansionHelpers.rejected(
          s"${problem.code}: ${problem.detail}",
          input.annotatedClass
        )
```

To put the same generated method in the companion, keep the handler and
lowering code unchanged and replace only the successful placement call:

```scala
ExpansionHelpers.placeMembersInCompanion(input, List(lowered.tree))
```

For a single method, the older narrow helper remains available:

```scala
import dotty.tools.dotc.ast.untpd
import paradise3.api.helpers.CompanionMethodConflictPolicy

lowered.tree match
  case method: untpd.DefDef =>
    ExpansionHelpers.addMethodToCompanion(
      input,
      method,
      CompanionMethodConflictPolicy.PreserveExisting
    )
  case _ =>
    ExpansionHelpers.rejected(
      "addFoo requires a generated method",
      input.annotatedClass
    )
```

Prefer the generic primary/companion pair for ordinary new concrete
`def`/`val` authoring: the same complete-batch contract works for either
location and validates every member before copying a target. Use the narrow
method/type/module helpers when their specialized member kind, namespace, and
explicit conflict policy are the contract you actually want.

The caller-supplied virtual source name belongs to generated-origin provenance;
it does not reuse or imitate the annotated target's source. In contrast,
`ScalametaDefinitionUntypedBridge.lower` intentionally returns a fresh
source-free raw representation. That representation is useful for structural
work but is **not** directly insertion-ready for Macro-Paradise placement and
is rejected before ordinary typer.

### Bridge and helper inventory

Use the narrowest operation whose ownership matches the work:

| Operation | Use it for | Do not infer |
| --- | --- | --- |
| `ScalametaDefinitionUntypedBridge.lower` (Quasiquotes) | A fresh, source-free exact `MemberDef` for structural/intermediate work | Direct insertion readiness; Macro-Paradise rejects a result with neither root source nor span |
| `ScalametaDefinitionGeneratedOriginBridge.lower` (Quasiquotes) | A positioned generated-origin concrete `DefDef`/`ValDef` ready to hand to Macro placement | Target admission, placement, conflict handling, or rollback |
| `ScalametaDefinitionClassMemberAppendBridge.append` (Quasiquotes) | The accepted request-074 hybrid: rebuild one admitted existing class while preserving exact old-member identity and appending one generated Scalameta Definition | General class editing, multi-member transactions, or Macro lifecycle replacement |
| `ExpansionHelpers.placeMembersInPrimary` | Atomically append a non-empty concrete `DefDef`/`ValDef` batch to an admitted primary | Object-primary support, overload resolution, or source repair |
| `ExpansionHelpers.placeMembersInCompanion` | Atomically create/merge the leased companion and append the same batch | Semantic companion lookup or partial success |
| `ExpansionHelpers.addMethodToCompanion` | Place one `DefDef` with explicit `PreserveExisting` or `Reject` behavior | Generic batch semantics or typed overload matching |
| `ExpansionHelpers.addTypeToCompanion` | Place one already-lowered `TypeDef` in the type namespace | Generic Definition lowering or arbitrary `MemberDef` placement |
| `ExpansionHelpers.addModuleToCompanion` | Place one already-lowered `ModuleDef` in the term namespace | Module authoring or semantic namespace resolution |
| `ExpansionHelpers.addPreparedSelfTypeToTrait` | Lease/prepare a collision-safe self alias and atomically install one caller-lowered direct `Self` type member on the admitted plain-trait slice | General Template editing or interpretation of `@self` semantics |
| `ExpansionHelpers.withAnnotatedClassView` | Decode the bounded read-only source view and fail closed with the original fallback | Symbols, owners, typing, or arbitrary source shapes |
| `ExpansionHelpers.addStringMethodToClass` / `addStringMethodToCompanion` / `addStringMethodSiblingClass` | Small built-in string-method fixtures and starter examples | A general definition-authoring language |

The ownership rule is stable:

```text
Quasiquotes  = author / match / project / lower / attach generated origin
Macro-Paradise = target admission / placement / companion lifecycle /
                 composition / conflicts / rollback
Dotty Typer  = ordinary typing after pre-typer expansion
```

### Real downstream examples

[AUXify for Scala 3](https://github.com/DmytroMitin/AUXify-scala3) demonstrates
that this stack is general infrastructure rather than an `@apply`-specific
path. At the current peer baseline its public development status is:

| Annotation | Current peer status | Bounded role exercised |
| --- | --- | --- |
| `@apply` | Implemented and qualified development slices | Companion contextual materializers for the simple `Show[A]` and bounded refined `Add.Out` families |
| `@aux` | Implemented and qualified first slice | An already-lowered companion `Aux` type alias |
| `@instance` | Implemented and qualified first slice | A generated companion factory for one exact two-abstract-method trait family |
| `@delegated` | Implemented and qualified first slice | One generated companion forwarding method |
| `@self` | Implemented and qualified default first slice | Prepared primary self alias plus one generated `Self` type member; historical boolean options remain research |
| `@syntax` | Characterized, not implemented | A future native-extension-module design; it is not current product behavior |

The implemented rows are local/source-built experimental milestones, not
stable or remotely published AUXify coordinates. Each admits only the source
families stated by AUXify; the names do not imply general historical Scala 2
parity. Accepted bounded composition currently includes both source orders for
selected `@apply + @delegated`, `@apply + @aux`, and `@apply + @instance`
families, not arbitrary annotation stacks.

### Conflict-policy audit

The narrow single-member companion helpers and the generic batch helpers have
different, intentional contracts:

- `addMethodToCompanion` can apply `PreserveExisting` or `Reject` to one known
  method. On a conflict, preserving the existing companion is unambiguous.
- `placeMembersInPrimary` and `placeMembersInCompanion` reject the entire batch
  when any generated raw term name conflicts with an existing direct term name
  or another generated name. Validation precedes all copying, so there is no
  partial prefix insertion.

For `0.1.1`, the generic behavior remains whole-batch rejection. Reusing the
name `PreserveExisting` for multiple generated members would not say whether
non-conflicting members are still inserted. A future generic policy, if user
evidence justifies one, should name its batch semantics explicitly:

| Possible policy | Exact meaning |
| --- | --- |
| `RejectBatch` | Any conflict rejects the operation and preserves the original transaction state |
| `SkipConflictingGenerated` | Drop only conflicting generated members and atomically insert the remaining validated batch |
| `NoOpWholeBatchOnAnyConflict` | Treat any conflict as successful no-op for the complete batch |

Those are post-release design alternatives, not current symbols. No generic
conflict-policy enum is added by this documentation audit.

### Virtual generated-source names

`ScalametaDefinitionGeneratedOriginBridge.lower(definition,
virtualSourceName)` passes the validated name to Dotty's `SourceFile.virtual`.
The returned `SourceFile` is attached to the generated root and its material
descendants, and `Lowered.virtualSourceName` reports the compiler-represented
path. Spans are offsets into Quasiquotes' deterministic generated source text.

The name does not change the admitted Definition shape or ordinary Scala
meaning; it is provenance. It is nevertheless observable source identity:
diagnostics and tooling can display or compare the path. The current bridge
rejects empty names, surrounding whitespace, NUL/CR/LF, and names the compiler
would represent differently. It does not require a `.scala` suffix, prescribe
a path layout, or enforce uniqueness across calls. Reusing one name for
different generated text can therefore make diagnostics ambiguous even though
each returned tree retains its own `SourceFile` and content.

Ordinary users currently must choose the name manually. A non-blocking
Quasiquotes follow-up is recommended:

- add a no-name overload that renders the admitted generated source first and
  derives a Quasiquotes-owned namespaced, content-addressed name, for example
  `<quasiquotes-generated:definition:sha256:<lowercase-64-hex>>` from the UTF-8
  generated-source bytes;
- guarantee that identical generated source receives the same default name and
  different content is collision-resistant rather than claiming global
  uniqueness; and
- retain the current explicit-name overload unchanged for advanced callers
  that need controlled diagnostic/source identity.

Name generation belongs in Quasiquotes because it owns generated-source and
origin construction. Macro-Paradise intentionally has no Quasiquotes product
dependency and should not generate these names. This recommendation does not
block the Macro-Paradise `0.1.1` freeze and has not allocated or sent a peer
request.

### Existing-definition transformation: current versus future

This Scalameta sketch is a useful statement of intent:

```scala
// Conceptual pseudocode, not a compile-ready exact-U transformation API.
q"..$mods def $ename[..$tparams] (...$paramss): $tpe = $expr"
// desired transformation
q"..$mods def $ename[..$tparams] (...$paramss): Option[$tpe] = Option($expr)"
```

It is not the selected implementation for transforming an existing Dotty tree.
Round-tripping a whole existing owner through Scalameta would lose exact raw
identity, provenance, and opaque compiler topology. Accepted Quasiquotes C024
instead selects this programmatic layering:

```text
existing untpd
  -> bounded exact capture/view
  -> exact preserved raw handles + selected decoded semantic fields
  -> validated replacement/reconstruction plan
  -> untpd
```

Conceptually, a future handler will select one exact existing `DefDef`, capture
unchanged header/opaque islands plus the selected parameter, result, and body
fields, replace only those fields through validated public semantic fragments,
and ask Quasiquotes to reconstruct the exact owner. Fresh changed fragments
compose from public project/N semantic authoring; the whole existing owner does
not travel through Scalameta.

The accepted request-074
`ScalametaDefinitionClassMemberAppendBridge` proves the simpler hybrid case:
preserve one existing class's direct members exactly and append one fresh
generated Definition. It does not expose the public exact transformation layer
needed for the sketch above. C024 chose
`B_BOUNDED_PROGRAMMATIC_U_GRAMMAR_IS_BETTER_THAN_U_STAR_INTERPOLATORS`;
possible future `uqr`/`uqq`/`utqr`/`utqq`/`udqr`/`udqq` syntax would be optional
sugar over accepted programmatic semantics. There is no current public
compile-ready U tutorial or API.

### Preparing one trait self alias and primary `Self` member

Unreleased `0.1.1-SNAPSHOT` development sources include the first-slice
`ExpansionTargetProfile.PlainZeroParameterTrait` profile and the bounded
`ExpansionHelpers.addPreparedSelfTypeToTrait` helper. The profile admits only a
plain, non-case, non-sealed, zero-type-parameter trait with no constructor value
parameters. The helper selects or preserves a usable self alias before caller
lowering and passes only its normalized name, origin, and source position to the
callback:

```scala
override def targetProfile: ExpansionTargetProfile =
  ExpansionTargetProfile.PlainZeroParameterTrait

override def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
  ExpansionHelpers.addPreparedSelfTypeToTrait(input): preparation =>
    lowerSelfType(preparation.selfAliasName)
```

An existing named self is retained as the exact original tree. An anonymous
self receives the first free direct term name in the deterministic sequence
`self`, `self$1`, and so on; direct vals, defs, and modules occupy that namespace,
while same-spelling type members do not. A direct raw type member named `Self`
rejects before the callback runs.

The callback owns construction and lowering of the complete generated
`untpd.TypeDef` named exactly `Self`. On success, Macro-Paradise installs the
prepared self, prepends the exact supplied type tree to the original body,
preserves every original body tree by identity and order, and removes only the
current handled annotation. Null or wrongly named output rejects atomically
with the original primary fallback; callback exceptions propagate without a
partial edit.

This is not an arbitrary primary or template editor. Macro-Paradise does not
expose raw self trees through the callback, interpret the generated bounds,
perform semantic self-type analysis, typing or symbol lookup, or implement an
annotation library's member semantics. The immutable released `0.1.0` artifact
does not contain this profile or helper. They remain source-built, unreleased,
exact-full-cross `0.1.1-SNAPSHOT` API; use the matching Scala line and retain
the complete manual wiring path described below when the source-built sbt
integration is unsuitable.

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

### Placing an already-created companion type

Unreleased `0.1.1-SNAPSHOT` development sources also include
`ExpansionHelpers.addTypeToCompanion`. It accepts exactly one already-created
raw `untpd.TypeDef`; the handler or an authoring layer owns construction and
exact lowering of the complete alias, abstract type member, nested class, or
nested trait definition:

```scala
import paradise3.api.helpers.{CompanionTypeConflictPolicy, ExpansionHelpers}

ExpansionHelpers.addTypeToCompanion(
  input,
  generatedType,
  CompanionTypeConflictPolicy.PreserveExisting
)
```

The helper owns only current-annotation cleanup and same-name companion
creation/merge. It appends the exact supplied tree without parsing, rebuilding,
re-lowering, interpreting, or repairing it. Conflict detection is direct,
syntactic, and type-namespace bounded: a direct raw `TypeDef` with the same
`TypeName` conflicts, covering aliases/type members and nested classes/traits.
A direct term-only `DefDef`, `ValDef`, or `ModuleDef` with the same decoded
spelling does not conflict, and nested/non-direct members are not searched.

`CompanionTypeConflictPolicy.PreserveExisting` returns the exact existing
companion unchanged; `Reject` returns the original annotated primary and no
partial companion. The generic concrete-definition helpers do not admit
`TypeDef`; there is no public arbitrary-`MemberDef` placement API, semantic
companion or name resolution, or alias/refinement semantics in Macro-Paradise.
The raw-tree escape hatch remains available. Released `0.1.0` does not contain
this helper, policy, or the two-upper-bounded trait profile.

### Placing an already-created companion module

Unreleased `0.1.1-SNAPSHOT` development sources also include the bounded
`ExpansionHelpers.addModuleToCompanion` helper. It accepts exactly one
already-created raw `untpd.ModuleDef`; the handler or authoring layer owns the
module's complete construction and lowering:

```scala
import paradise3.api.helpers.{CompanionModuleConflictPolicy, ExpansionHelpers}

ExpansionHelpers.addModuleToCompanion(
  input,
  generatedModule,
  CompanionModuleConflictPolicy.PreserveExisting
)
```

The helper owns only current-annotation cleanup and same-name companion
creation/merge. It appends the exact supplied module without inspecting,
rebuilding, validating, or interpreting its body. Placement is syntactic and
pre-typer. Direct `ModuleDef`, `DefDef`, and `ValDef` members with the same raw
`TermName` conflict; a same-spelling direct `TypeDef` is in the type namespace,
and nested/non-direct members are not searched.

`CompanionModuleConflictPolicy.PreserveExisting` returns the exact existing
companion unchanged; `Reject` returns the original annotated primary and no
partial companion. Macro-Paradise does not construct extension methods, search
semantic companions or inherited members, or admit modules through the generic
concrete-definition placement pair. Released `0.1.0` does not contain this
helper or policy; it is only an unreleased source-built `0.1.1-SNAPSHOT` API on
the matching exact Scala line.

## Local coordinate for marker and handler authors

First publish the plugin API and plugin from a source clone:

```sh
sbt -batch "pluginApi/publishLocal" "plugin/publishLocal"
```

A separate marker/handler build then compiles against the exact full-cross API
coordinate, not the plugin implementation or any test fixture:

```scala
ThisBuild / scalaVersion := "3.3.8" // or exact 3.8.4 / 3.9.0
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
ThisBuild / scalaVersion := "3.3.8" // or exact 3.8.4 / 3.9.0
val mpOrg = "com.github.dmytromitin"
val mpVersion = "0.1.1-SNAPSHOT"
val mpApi =
  (mpOrg % "macroparadise-scala3-plugin-api" % mpVersion)
    .cross(CrossVersion.full)
val macroparadisePlugin =
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
  .dependsOn(macroAnnotations)
  .settings(
    libraryDependencies += compilerPlugin(macroparadisePlugin),
    Compile / scalacOptions ++= {
      val markerJar = (macroAnnotations / Compile / packageBin).value
      val handlerJar = (macroHandlers / Compile / packageBin).value
      val handlerClasses = (macroHandlers / Compile / classDirectory).value.getCanonicalFile
      val handlerClasspath = handlerJar +:
        (macroHandlers / Runtime / dependencyClasspath).value.files
          .filterNot(_.getCanonicalFile == handlerClasses)
      val buildIdentity = ExternalArtifactIdentity.combined(
        Seq("marker" -> markerJar),
        handlerClasspath.zipWithIndex.map { case (file, index) =>
          f"handler-$index%04d" -> file
        }
      )
      Seq(
        "-Xplugin-require:macroparadise",
        s"-P:macroparadise:handlerClasspath=${handlerClasspath.map(_.getAbsolutePath).mkString(java.io.File.pathSeparator)}",
        s"-P:macroparadise:externalArtifactIdentity=sha256:$buildIdentity"
      )
    }
  )
```

The executable `@identity` mirror is
`verifyIndependentExternalSbtConsumerFromLocalRepository`. It compiles the
literal identity and user-authored `@gen` marker, handler, and consumer shapes
in a disposable external sbt build, audits the graph and plugin options, and
keeps its artifacts task-local.
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

## Manual wiring and the source-built sbt integration

The manual escape hatch remains an explicit handler path plus identity derived
from all explicit marker artifacts and the complete ordered effective handler
expansion classpath in the sbt setting above.

- Automatically searching the ordinary source classpath would make handler
  provenance depend on source/runtime dependency resolution and could admit
  duplicate API or compiler universes.
- An opt-in source-classpath search would retain those identity and provenance
  risks while adding another mode for Zinc, BSP, and IDE imports to reproduce.

The explicit path keeps the plugin loader, marker lookup, and handler loader
separate; avoids adding handler implementation code to application runtime;
and makes the selected artifact visible in `scalacOptions`. The direct
`handler/packageBin` task dependency repackages the handler before consumer
options are evaluated, but a stable path alone is not content-sensitive to
Zinc. The `externalArtifactIdentity` option changes when either packaged marker
metadata, handler implementation bytes, or handler dependency bytes change, so
Zinc recompiles the unchanged consumer without `clean`.

`externalArtifactIdentity` is intentionally a build-only token. The plugin does
not interpret or validate its value; the exact option string participates in
Zinc compiler-option identity. Compute it from the current packaged marker and
handler classpath bytes rather than assigning a constant, timestamp, path, or manually
managed version. It is not an artifact-integrity or security check.

The opt-in [`sbt-integration` module](../sbt-integration/README.md) now automates
this precompiled topology. It derives exact full-cross plugin and API modules,
keeps published handlers in a hidden configuration, expands their complete
ordered dependency classpath, and installs the compiler options. Its static
local-project helper returns settings only: the consumer still declares
`.dependsOn(marker)`. The module is source-built and unreleased, and persistent
BSP compilation and run requests are qualified for exact Scala 3.3.8 and 3.8.4
with sbt 1.12.15 on JDK 25. Exact 3.9.0 has CLI/Zinc and ordinary sbt
qualification, but no retained-process BSP claim. That qualification covers one retained
BSP process through no-op, handler-only, handler-dependency-only,
marker-metadata-only, consumer-only, stale-handler failure, and repaired
compilation without `clean` or restart. It does not qualify IntelliJ native JPS
compilation or live same-module handler authoring. Manual wiring above remains
supported.

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

The starter preserves the full explicit fail-closed matrix and the focused
compact matrix. It also runs paired metadata-authoring negatives in both modes:
nonexistent or non-handler classes, an A/B artifact mismatch,
descriptor/stale/qualified-identity mismatches, and empty, whitespace, or
malformed handler metadata. Each negative proves the preconsumer stop boundary.

## What the starter proves

The independent external sbt proof compiles the exact imported-short `@identity`
example above, observes canonical metadata selection and one `IdentityHandler`
invocation, and compiles the direct-qualified control. Its missing-marker lane
remains an ordinary unresolved-import/cyclic-completion build-graph failure.
The same proof typechecks the documented user-authored `@gen` method call. The
generated-greeting starter then typechecks and runs
`new Greeter().generatedGreeting`, which returns `Hello, Greeter!`. Together
they prove wiring first and generated output second on the pinned toolchain.

It does not prove a marker declared in the same compilation as its annotated
use, same-module handlers, remotely released coordinates, arbitrary targets,
arbitrary composition, import resolution, cross-compiler compatibility, or
release readiness. Current metadata discovery expects the marker artifact to
be precompiled. The compact command is source-build ergonomics for local
packaged artifacts; public source visibility and local publication do not mean
Maven Central availability.
