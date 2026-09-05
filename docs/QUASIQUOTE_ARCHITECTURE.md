# Quasiquote and pre-typer AST architecture

Macro-Paradise places exact Dotty untyped trees before ordinary typer. Authoring
those trees can nevertheless begin in a compiler-neutral representation. This
document distinguishes the current Scalameta-based downstream path, Scala 3
quoted reflection, and possible future public naming. Macro-Paradise does not
publish a quasiquote API or take a product dependency on another repository.

## Current compiler-neutral downstream path: Scalameta

[Quasiquotes for Scala 3](https://github.com/DmytroMitin/quasiquotes-scala3)
uses Scalameta (`scala.meta`) trees and quasiquotes as its compiler-neutral
definition-authoring representation. Scalameta describes source-like syntax;
it does not pretend to supply Dotty owners, resolved symbols, compiler context,
or other post-typer facts.

[AUXify for Scala 3](https://github.com/DmytroMitin/AUXify-scala3) is one real,
independent downstream consumer. Quasiquotes also exposes an exact-version
generated-origin bridge for four ordinary concrete Definition families. The
bounded downstream pipeline is:

```text
Scalameta definition or quasiquote
  -> Quasiquotes exact-compiler lowering bridge
  -> positioned Dotty untpd.DefDef or untpd.ValDef
  -> Macro-Paradise primary/companion placement helper
  -> Macro-Paradise pre-typer insertion
  -> ordinary Dotty typer
```

That path proves separation of responsibilities for the admitted generated
method/value families. Quasiquotes owns neutral construction and exact lowering;
Macro-Paradise owns target admission, placement, conflicts, rollback, and phase
timing; Dotty remains authoritative for typing. The Macro placement helper
knows only the returned raw `MemberDef`, so neither Macro production artifact
acquires a Scalameta or Quasiquotes dependency. This does not establish
arbitrary Scalameta lowering or a stable compiler-independent adapter API.

## Current Scala 3 quoted-reflection quasiquotes

The existing Quasiquotes typed families are:

- `qr` / `qq` for terms;
- `tqr` / `tqq` for types;
- `dqr` / `dqq` for definitions.

They operate in Scala 3's staged `Quotes` and `quotes.reflect` world. That is
the right representation for ordinary quoted macros and typed reflection. It
is not directly the tree representation that Macro-Paradise inserts before
ordinary typer. A `dqr` result must therefore not be described as directly
insertable by the Macro-Paradise phase.

## Possible future neutral naming

A future public neutral family has been discussed under provisional names such
as `nqr` / `nqq`, `ntqr` / `ntqq`, and `ndqr` / `ndqq`. Those names are not
shipped or promised. The architectural requirement matters more than the names:

```text
source-like neutral tree
  -> backend-specific lowering
       -> Quotes/reflection backend for quoted macros
       -> exact Dotty untpd backend for Macro-Paradise
  -> phase-appropriate use
```

The neutral model should preserve syntax and source provenance without
claiming typer knowledge. A tightly versioned backend may attach the exact
compiler details required by its target phase. Raw compiler construction stays
available as an experimental escape hatch when neutral coverage is incomplete.

## Hypothetical raw-untyped quasiquotes

A separate raw family could be imagined under names such as `uqr` / `uqq`,
`utqr` / `utqq`, and `udqr` / `udqq`. It is not planned public API. Scalameta
authoring plus exact backend lowering is the narrower current direction: it
avoids creating a second public source language tied directly to `untpd` while
retaining a compiler-coupled escape hatch for advanced handlers.

## Why Scala 2 felt like one world

Scala 2 quasiquotes were built around a broadly shared reflection/compiler
`Tree` universe. Similar source-like machinery could construct or match trees
at different attribution states, including annottees used by macro annotations.

Scala 3 has a stronger boundary between public, typed, staged reflection through
`Quotes` and compiler-internal phase trees through `untpd`, `tpd`, and compiler
`Context`. Macro-Paradise deliberately runs on the compiler-internal side before
typer so generated definitions participate in ordinary typing.

## Status boundary

- **current and proven in related projects:** Scalameta neutral definition
  authoring and exact generated-origin lowering for the bounded ordinary
  `DefDef`/`ValDef` families;
- **current in Macro-Paradise:** the raw untyped handler contract and bounded
  primary/companion concrete-member placement helpers;
- **current in Quasiquotes:** typed quoted-reflection `q*` families;
- **provisional:** public neutral `n*` naming and broader backend coverage;
- **hypothetical:** a public raw-untyped `u*` family.

See [Architecture](ARCHITECTURE.md) for the plugin phase and
[External handler authoring](EXTERNAL_HANDLER_AUTHORING.md) for the current
experimental author-facing contract.
