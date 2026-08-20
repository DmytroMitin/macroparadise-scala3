# Quasiquote and pre-typer AST architecture

This document separates three AST and quasiquote worlds. Only the first world
exists as a public Quasiquotes surface today. The neutral direction is proposed
design work, and the raw-untyped naming family is hypothetical. Macro-Paradise
does not publish any new quasiquote API or take a product dependency on
Quasiquotes.

## Current: typed quoted-reflection quasiquotes

The existing quasiquote families are:

- `qr` / `qq` for terms;
- `tqr` / `tqq` for types;
- `dqr` / `dqq` for definitions.

They operate in Scala 3's staged `Quotes` and `quotes.reflect` world. That is
the right representation for ordinary quoted macros and typed reflection. It
is not directly the tree representation that Macro-Paradise inserts before
ordinary typer.

Macro-Paradise must ultimately produce exact compiler trees such as
`untpd.DefDef`, templates, companions, and sibling definitions. Ordinary typer
then attributes those inserted untyped trees together with user source.

## Proposed: compiler-neutral authoring

The design direction under discussion uses provisional names:

- `nqr` / `nqq`;
- `ntqr` / `ntqq`;
- `ndqr` / `ndqq`.

These names are not promised API. The important boundary is the pipeline:

```text
source-like neutral quasiquote
  -> compiler-free neutral term, type, or definition
  -> backend-specific lowering
       -> Quotes/reflection backend for quoted macros
       -> exact Dotty untpd backend for Macro-Paradise
  -> pre-typer insertion and ordinary typing
```

The neutral model should preserve syntax and source provenance without
pretending to know owners, resolved symbols, or other typer facts. A tightly
versioned exact backend may attach the compiler-specific details required by
the phase. Raw compiler construction remains available as an experimental
escape hatch when the neutral model is intentionally incomplete.

This design aims to recover source-like annotation-authoring ergonomics while
keeping volatile compiler internals out of the common authoring surface.
Useful definition coverage includes adding members, creating or merging a
companion under plugin policy, and emitting ordered sibling definitions.

## Hypothetical: raw-untyped quasiquotes

A third family could be imagined:

- `uqr` / `uqq`;
- `utqr` / `utqq`;
- `udqr` / `udqq`.

This family is not currently requested or planned as public API. Neutral
authoring plus exact backend lowering is narrower: it avoids creating a second
public language tied directly to `untpd`, while retaining a compiler-coupled
escape hatch for advanced handlers. A public raw family should be reconsidered
only if realistic annotations show that the neutral model cannot express the
required transformations with acceptable power, provenance, and diagnostics.

## Why Scala 2 felt like one world

Scala 2 quasiquotes were built around a broadly shared reflection/compiler
`Tree` universe. The same source-like machinery could construct or match trees
whose attribution state varied with compiler context, including trees used by
def macros and untyped annottees used by macro annotations.

Scala 3 has a stronger boundary:

- public, typed, staged reflection through `Quotes`; and
- compiler-internal phase trees through `untpd`, `tpd`, and compiler `Context`.

Macro-Paradise deliberately runs on the compiler-internal side before typer so
new definitions participate in ordinary typing. A `dqr` result must therefore
not be described as directly insertable at that phase.

## Current proven bridge

One positioned contextual-method integration has already proved the useful
shape: compiler-free structural construction followed by exact
compiler-coupled lowering to an `untpd.DefDef`. Macro-Paradise inserts that
lowered untyped definition before typer. It did not directly insert a `dqr`
quoted-reflection result.

The status boundary is therefore:

- **current/proven:** typed `q*` quasiquotes exist, and one structural-to-exact
  contextual-method lowering path works;
- **experimental:** Macro-Paradise's raw untyped handler contract and bounded
  helper layer;
- **proposed/under discussion:** neutral `n*` authoring with multiple backends;
- **hypothetical/not requested:** a public raw-untyped `u*` family.

See [Architecture](ARCHITECTURE.md) for the plugin phase and
[External handler authoring](EXTERNAL_HANDLER_AUTHORING.md) for the current
experimental author-facing contract.
