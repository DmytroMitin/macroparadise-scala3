# Existing-method U-U starter

This experimental, source-built example shows a real pre-Typer transformation
of an existing method. A marker project selects a precompiled handler in the
genuinely foreign package `macroconsumer.uu`; the handler uses only the public
Quasiquotes `ExistingClassUntypedRewrite` facade, and an ordinary consumer sees
the rewritten method through normal Scala typing.

The example is bounded to current source-built Macro-Paradise
`0.2.0-SNAPSHOT`, Quasiquotes `0.4.0-SNAPSHOT`, JDK 25, sbt 1.12.15, and exact
Scala 3.3.8, 3.8.4, or 3.9.0. This source-only combination has no remote snapshot
and is not in the immutable Macro-Paradise 0.1.1 or Quasiquotes 0.3.0
releases.

## What changes

The consumer writes an ordinary class:

```scala
@rewriteEcho
final class Service:
  val untouchedBefore = 1
  def echo(value: AnyVal): AnyVal = value
  val untouchedAfter = 2
```

Before Typer, the handler performs three simultaneous edits in one immutable
plan and one validated owner reconstruction:

```scala
def echo(value: Int): Int = Math.abs(value)
```

This is an existing-method transformation, not generated sibling-only code.
The original direct `echo` method is captured, its parameter and result types
are both replaced with `TypeNormalForm.STypeIdent("Int")`, and its body becomes
a semantic `TermShape` call to `Math.abs` using the capture-local parameter
reference. Untouched direct members preserve their exact tree identities and
order. The class owner and edited method are deliberately fresh.

The handler never reconstructs the method with raw `untpd` constructors, never
round-trips the owner through Scalameta, and does not use a package-private
Quasiquotes adapter. Its public failure code and detail become an ordinary
Macro-Paradise diagnostic.

## Source layout and ownership

```text
marker/       @rewriteEcho plus @expander metadata
handler/      macroconsumer.uu.RewriteEchoHandler and the public U-U plan
consumer/     the annotated Service and ordinary typed runtime assertions
negative-call/      an ordinary Typer rejection after the rewrite
unsupported-shape/  a controlled handler selection diagnostic
```

The marker is an ordinary compile-only consumer dependency. The handler and
its exact Quasiquotes compiler-facing closure are hidden on the handler tool
path. The Macro-Paradise compiler plugin remains on its normal plugin path.
Neither the handler implementation, Quasiquotes Dotty backend, nor Scala
compiler belongs on the running application's classpath.

## Build the source snapshots locally

Clone Macro-Paradise and Quasiquotes, select the same exact Scala line in both,
and install their current snapshot artifacts locally. For Scala 3.8.4:

```sh
cd macroparadise-scala3
sbt -Dmacroparadise.exactScalaVersion=3.8.4 -batch \
  "++3.8.4!" \
  "pluginApi/publishLocal" \
  "plugin/publishLocal"
cd sbt-integration
sbt -batch verifyIntegrationPolicy publishLocal

# In a separate shell, from the Quasiquotes checkout:
sbt -batch \
  "++3.8.4!" \
  "core/publishLocal" \
  "neutralScalameta/publishLocal" \
  "dottyInternal/publishLocal"
```

Repeat with exact 3.3.8 or 3.9.0 when selecting that line. Do not mix Scala
patch versions or reuse Quasiquotes 0.3.0: `dotty-internal` and the
Macro-Paradise plugin/API are exact-full-cross compiler artifacts.

## Run the starter

From this directory:

```sh
sbt -Dmacroparadise.example.scalaVersion=3.8.4 -batch clean verifyStarter
```

Expected output contains exactly one recognizable success line:

```text
PUBLIC_EXISTING_METHOD_U_U_STARTER_PASS echo=7 before=1 after=2
```

The same command supports `3.3.8` and `3.9.0`. `verifyStarter` also checks that
the exact Quasiquotes backend appears once on the hidden handler path, the
handler is first, the compiler plugin is selected, and compiler-facing
artifacts do not leak onto runtime.

Two intentional negatives are separate from the normal aggregate so the
positive starter remains runnable:

```sh
sbt -Dmacroparadise.example.scalaVersion=3.8.4 -batch negativeCall/compile
sbt -Dmacroparadise.example.scalaVersion=3.8.4 -batch unsupportedShape/compile
```

The first must fail ordinary typing because `echo` now requires and returns
`Int`; the second must report `EXISTING_METHOD_U_U_ECHO_SELECTION_FAILED`
because no direct `echo` exists.

## Deliberate limit: applied method types

Core `TypeNormalForm` can represent an application such as `Option[String]`,
but `Option[String]` is not yet supported by the existing-method U040 type
editor. Its current method-site preparation admits only the bounded primitive
replacement family used here. This example therefore does not implement
`@addOption`, arbitrary method-type rewriting, generic local-binder type edits,
or a general raw `untpd` rewriting API.

For the wider marker/handler setup choices, use
[External handler authoring](../../docs/EXTERNAL_HANDLER_AUTHORING.md). For the
public Quasiquotes project and its evolving exact-version U APIs, see
[quasiquotes-scala3](https://github.com/DmytroMitin/quasiquotes-scala3).
