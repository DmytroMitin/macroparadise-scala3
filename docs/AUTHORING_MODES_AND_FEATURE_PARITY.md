# Authoring modes and feature parity

This guide is the canonical map for choosing a Macro-Paradise authoring frontend, compilation topology, and build wiring. These are orthogonal choices:

- **Authoring frontend:** explicit `@expander` metadata plus a user-authored `ExpansionHandler`, or `@embeddedExpander` plus a companion `transform`.
- **Compilation topology:** producer code compiled before the consumer in another Scala compilation module, or a bounded selected relationship in the same Scala compilation module with consumers in different physical files.
- **Build wiring:** source-built sbt AutoPlugins/integration helpers, or the documented complete manual precompiled wiring.
- **Distribution:** same-build local producer projects, resolver-installed producer role modules, immutable released `0.1.1`, or unreleased source-built `0.2.0-SNAPSHOT`.

A same sbt multi-project build is not one Scala compilation module. One Scala compilation module is not one physical source file. A derived marker JAR and handler JAR may come from one producer project, but they are separate generated artifacts with different classpath roles.

## Conceptual flow

```text
Explicit frontend
  @expander("pkg.Handler") marker
    -> metadata discovery
    -> user-authored ExpansionHandler.expand(input)

Embedded frontend
  @embeddedExpander annotation + same-file companion transform
    -> producer compiler plugin validates and lowers the declaration
    -> generated @expander metadata + generated no-arg ExpansionHandler adapter
    -> adapter.expand(input) delegates to the relocated companion transform

Both
  -> normal handler loading
  -> shared staged scheduler and transaction
  -> ExpansionOutcome / ExpansionEdit / ExpansionTransforms
```

The embedded lowering happens while compiling the producer, before Typer. Expansion happens later while compiling a consumer. In bounded same-module embedded mode those events occur in one compiler run with producer-dependent consumer units suspended until current producer output is available.

The generated adapter class name and relocated transform name are private implementation details. They are not user API, stable binary names, or values to put in sbt settings.

## Authoritative four-cell mode matrix

| Authoring frontend | Precompiled in other module | Same Scala module |
| --- | --- | --- |
| @expander | explicit marker/handler | singleton Model A, plus exactly two independent external bindings on unreleased source-built main |
| @embeddedExpander | precompiled generated adapter | bounded embedded singleton, plus exactly two independent bindings on unreleased source-built main |

### Explicit `@expander`, precompiled producer

- **Source rule:** marker and handler compile before consumers. The marker names the handler class; roles may be same-build projects or resolver-installed modules.
- **Ownership:** the author owns marker, handler, `annotationName`, and `expand`; consumers use the main compiler plugin.
- **sbt:** enable `MacroParadisePrecompiledPlugin`; use `MacroParadiseIntegration.precompiledProjects` for local projects or `macroParadiseMarkerModules` and `macroParadiseHandlerModules` for modules.
- **Manual wiring:** maintained and qualified for same-build local and resolver-installed producers. Include the compiler plugin, marker dependency, complete hidden handler closure, and content identity.
- **Multiplicity:** integration accepts sequences of marker and handler producers. This precompiled capability is independent of the one-binding same-module limit.
- **Availability:** released `0.1.1` provides the earlier external-handler API; current `ExpansionHandler` documentation describes source-built `0.2.0-SNAPSHOT`. Do not assume binary compatibility.

See [External handler authoring](EXTERNAL_HANDLER_AUTHORING.md).

### Embedded frontend, precompiled producer

- **Source rule:** each opted-in top-level final `StaticAnnotation` class and same-named companion `transform` share a package and physical source file. The producer compiles before consumers.
- **Ownership:** `MacroParadiseEmbeddedProducerPlugin` validates and lowers the declaration and derives separate marker and handler role JARs. Consumers use `MacroParadisePrecompiledPlugin`.
- **sbt:** use `MacroParadiseIntegration.precompiledEmbeddedProject` for a same-build producer or `precompiledEmbeddedModules` for resolver-installed roles.
- **Manual wiring:** maintained and qualified for both producer topologies. With sbt wiring, these are the four explicitly tested producer-topology x wiring quadrants.
- **Multiplicity:** a precompiled producer may contain multiple complete annotation/companion declarations. That does not authorize multiple same-module bindings.
- **Availability:** source-built `0.2.0-SNAPSHOT` only; `0.1.1` has no `embeddedExpander`, embedded producer plugin, or derived embedded roles.

See [Embedded producer authoring](EMBEDDED_PRODUCER_AUTHORING.md).

### Explicit `@expander`, bounded same-module external modes

```text
singleton: Marker.scala -> Handler.scala -> Usage.scala
pair A: MarkerA.scala -> HandlerA.scala -> ConsumerA.scala
pair B: MarkerB.scala -> HandlerB.scala -> ConsumerB.scala
```

- **Source rule:** singleton marker, handler, and every consumer are separate physical files. The pair has four globally distinct producer files and two distinct consumer files, for six distinct core files in one Scala module; each consumer unit selects one binding.
- **Ownership:** the author owns marker and no-arg handler. The binding explicitly supplies marker source, handler source, annotation identity, and handler class.
- **sbt:** enable no-trigger `MacroParadiseSameModulePlugin`. Configure singleton Model A with `macroParadiseSameModuleBinding`, or configure the pair with `macroParadiseSameModuleBindings := Seq(...)` containing exactly two `macroParadiseSameModuleHandler` records.
- **Manual wiring:** no separate complete manual same-module recipe is qualified. Use the AutoPlugin so source identity and compiler options stay coherent.
- **Name resolution:** both external same-module modes require direct-qualified consumer spelling matching the configured canonical identity. Pair mode excludes imported short, wildcard, alias, and inferred same-package short spellings.
- **Multiplicity:** singleton Model A selects one relationship. Pair mode configures exactly two independent relationships, while each physical consumer selects at most one. `macroParadiseSameModuleBinding`, `macroParadiseSameModuleBindings`, and `macroParadiseSameModuleEmbeddedBinding` are mutually exclusive.
- **Availability:** singleton Model A exists in released `0.1.1`; the exact-two pair exists only on unreleased source-built `0.2.0-SNAPSHOT`. Current main uses a handler protocol distinct from the released API.

The pair emits deterministically sorted repeated `sameModuleHandler` options and
one aggregate source-byte digest. An A-v1 to A-v2 producer edit without `clean`
retains B-v1. Producer edges, one-unit A+B ambiguity, and dynamically introduced
cross-binding annotations fail closed. See the [complete bounded pair recipe](../sbt-integration/README.md#bounded-two-independent-external-same-module-recipe).

### Embedded frontend, bounded same-module

This bounded embedded same-module cell has a singleton configuration and one
exact-two independent exception on source-built main.

```text
singleton: demo/EmbeddedAnnotations.scala -> consumer/Usage.scala
pair A:    demo/ProducerA.scala           -> consumer/ConsumerA.scala (Main co-located)
pair B:    demo/ProducerB.scala           -> consumer/ConsumerB.scala
```

- **Source rule:** singleton configures one producer/consumer relationship. Pair mode configures exactly two independent relationships in four physical files. Each producer file contains one matching embedded declaration and same-file companion; each consumer is separate and selects at most one binding.
- **Ownership:** the embedded producer plugin runs before `paradiseGen` only in this selected mode and avoids synthetic marker splitting. The main plugin resumes consumers against fresh current output.
- **sbt:** enable `MacroParadiseSameModulePlugin`; configure singleton `macroParadiseSameModuleEmbeddedBinding`, or pair `macroParadiseSameModuleEmbeddedBindings := Seq(...)` with `macroParadiseSameModuleEmbedded`. This mode adds the embedded producer compiler plugin. There are no packaged marker/handler roles.
- **Manual wiring:** no separate complete manual same-module recipe is qualified.
- **Name resolution:** use the fully qualified annotation or the bounded source-preceding package-level explicit unaliased import form.
- **Multiplicity:** one relationship or exactly two independent embedded relationships. I1, E1, E2, and I2 are four mutually exclusive setting families. Three or more embedded bindings, mixed modes, producer graphs, and cross-binding composition remain unsupported.
- **Availability:** unreleased source-built `0.2.0-SNAPSHOT` only.

See the [runnable singleton recipe](../sbt-integration/README.md#bounded-same-module-embedded-recipe)
and [exact-two embedded recipe](../sbt-integration/README.md#bounded-two-independent-embedded-same-module-recipe).

## Parity claims and limits

- **Shared expansion protocol: yes, on current `0.2.0-SNAPSHOT`.** Both frontends reach `ExpansionHandler.expand(ExpansionInput): ExpansionOutcome`; embedded uses its generated adapter.
- **Shared backend scheduler and transaction: yes within verified current source and tests.** Both use normal handler loading, staged rescanning, validation, and rollback.
- **Same backend target kinds: yes.** The intersection is the supported pre-Typer Class/Trait/Object grammar.
- **All program behavior equivalence proven: no.** Shared backend parity does not prove all program behavior equivalent across annotation programs, edge cases, build topologies, resolution environments, or packaging choices.
- **Authoring declaration parity: no.** Embedded requires an opted-in top-level final `StaticAnnotation` class, same-file companion, and exact `transform`; explicit authors implement the handler protocol.
- **Topology parity: no.** Embedded declaration and companion share one physical source. External same-module Model A uses separate marker and handler files. Consumers are separate files in both bounded cells.
- **Import resolution parity: no.** External Model A is direct-qualified. Embedded same-module also qualifies one bounded explicit-import form.
- **General same-module multi-binding support: no.** The only exceptions are the exact-two independent external pair and exact-two independent embedded pair on source-built main. Three or more bindings, mixed external/embedded settings, producer graphs, and cross-binding composition remain unsupported.
- **Release and embedded limits:** Released 0.1.1 has no exact-two mode or embedded frontend. The source-built embedded exception is exactly two, not arbitrary multiplicity.
- **External pair release limit:** Released 0.1.1 has no exact-two pair.
- **Precompiled multi-producer support:** qualified for explicit producers through sequence-based local/module integration.
- **Precompiled multiple embedded declarations:** qualified within the declaration contract; it does not imply same-module multiplicity.
- **Full release parity: no.** Released 0.1.1 contains external-handler Model A and its earlier API. Embedded authoring and the current handler protocol are source-built 0.2.0-SNAPSHOT only. No remote 0.2.0 artifact exists.

Semantic parity means a shared underlying expansion mechanism and expressive outcomes within the intersection of supported inputs. It is not mathematical or observational equivalence for every authoring situation.

## Shared same-module lifecycle

1. A singleton configures one relationship; either exact-two pair configures two independent relationships, with one selected relationship per consumer unit.
2. One aggregate source-byte digest participates in Zinc incremental identity.
3. The selected consumer is suspended before mutation or handler loading while current producer output compiles.
4. A fresh current-output classloader resumes ordinary expansion for that unit; one processor instance does not execute both independent deferred handlers in one unit.
5. Stale output, missing output, producer edges, cross-binding composition, and dependency cycles fail closed.
6. Tested producer-body edits invalidate unchanged consumers without `clean`.

Not implemented or qualified: producer and consumer in the same physical source, arbitrary multiplicity including three or more embedded bindings, mixed external/embedded mode, dependency-graph solving, cycles, automatic discovery, native IntelliJ JPS, or direct Java gutter Run. A separate fifth initially-unsuspended source that depends on both embedded consumers is also unqualified.

## Compiler, BSP, and IDE evidence

| Cell | Exact compiler-line evidence | Persistent BSP / IntelliJ qualification |
| --- | --- | --- |
| Explicit precompiled | Public boundaries pass 3.3.8, 3.8.4, 3.9.0 | No new all-line per-cell IDE claim is made here |
| Embedded precompiled | Four setup quadrants remain in all three public boundaries | No dedicated precompiled embedded IDE/BSP claim is added |
| External same-module singleton Model A | CLI/Zinc passes 3.3.8, 3.8.4, 3.9.0 | Persistent BSP and sbt-delegated IntelliJ evidence is bounded to 3.3.8 and 3.8.4 |
| Exactly two independent external bindings | CLI/Zinc is qualified on exact 3.3.8, 3.8.4, and 3.9.0 | persistent sbt BSP is qualified on 3.8.4; pair IntelliJ, native JPS, and direct Java gutter Run remain unqualified |
| Embedded same-module singleton | CLI/Zinc passes 3.3.8, 3.8.4, 3.9.0 | Persistent BSP: 3.8.4; IntelliJ sbt-shell Build/run: all three lines |
| Exactly two independent embedded bindings | CLI/Zinc is qualified on exact 3.3.8, 3.8.4, and 3.9.0 | persistent sbt BSP is qualified on 3.8.4; pair IntelliJ, native JPS, and direct Java gutter Run remain unqualified |

The qualified environment is JDK 25 and sbt 1.12.15. IntelliJ evidence uses sbt import, sbt-delegated Build, and `run` in the sbt shell. Native JPS and direct Java gutter Run are not qualified.

## Argument and API boundary

`@embeddedExpander` is not a new typed Scala 3 macro API. Annotation arguments remain pre-Typer syntax. Supported positional and named forms are decoded syntactically; Macro-Paradise does not evaluate arbitrary expressions, synthesize implicits, materialize constructor defaults, or provide typed name resolution. Both frontends remain bounded to the current Class/Trait/Object target grammar and exact compiler-line handler API.

Use the cell-specific runnable guides rather than copying generated adapter names or inventing manual same-module options.
