# Authoring modes and feature parity

This guide is the canonical map for choosing a Macro-Paradise authoring frontend, compilation topology, and build wiring. These are orthogonal choices:

- **Authoring frontend:** explicit `@expander` metadata plus a user-authored `ExpansionHandler`, or `@embeddedExpander` plus a companion `transform`.
- **Compilation topology:** producer code compiled before the consumer in another Scala compilation module, or one bounded producer relationship in the same Scala compilation module with consumers in different physical files.
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
| @expander | explicit marker/handler | bounded external Model A |
| @embeddedExpander | precompiled generated adapter | bounded embedded same-module |

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

### Explicit `@expander`, bounded same-module external Model A

```text
src/main/scala/demo/Marker.scala
src/main/scala/demo/Handler.scala
src/main/scala/demo/Usage.scala
```

- **Source rule:** marker, handler, and every consumer are separate physical files in one Scala compilation module.
- **Ownership:** the author owns marker and no-arg handler. The binding explicitly supplies marker source, handler source, annotation identity, and handler class.
- **sbt:** enable no-trigger `MacroParadiseSameModulePlugin`; configure `macroParadiseSameModuleBinding` with `macroParadiseSameModuleHandler`.
- **Manual wiring:** no separate complete manual same-module recipe is qualified. Use the AutoPlugin so source identity and compiler options stay coherent.
- **Name resolution:** the bounded trigger requires direct-qualified consumer spelling matching the configured canonical identity.
- **Multiplicity:** exactly one relationship, mutually exclusive with `macroParadiseSameModuleEmbeddedBinding` in the same enabled sbt project.
- **Availability:** Model A exists in released `0.1.1`; current main uses the source-built `0.2.0-SNAPSHOT` handler protocol. The APIs are distinct.

### Embedded frontend, bounded same-module

```text
src/main/scala/demo/EmbeddedAnnotations.scala
  (annotation class + companion transform)
src/main/scala/consumer/Usage.scala
```

- **Source rule:** exactly one configured producer source contains one matching embedded declaration and same-file companion. Every consumer is in a different physical source file.
- **Ownership:** the embedded producer plugin runs before `paradiseGen` only in this selected mode and avoids synthetic marker splitting. The main plugin resumes consumers against fresh current output.
- **sbt:** enable `MacroParadiseSameModulePlugin`; configure `macroParadiseSameModuleEmbeddedBinding` with `macroParadiseSameModuleEmbedded`. This mode adds the embedded producer compiler plugin. There are no packaged marker/handler roles.
- **Manual wiring:** no separate complete manual same-module recipe is qualified.
- **Name resolution:** use the fully qualified annotation or the bounded source-preceding package-level explicit unaliased import form.
- **Multiplicity:** exactly one relationship. External and embedded same-module settings are mutually exclusive in one enabled sbt project.
- **Availability:** unreleased source-built `0.2.0-SNAPSHOT` only.

See the [runnable same-module embedded recipe](../sbt-integration/README.md#bounded-same-module-embedded-recipe).

## Parity claims and limits

- **Shared expansion protocol: yes, on current `0.2.0-SNAPSHOT`.** Both frontends reach `ExpansionHandler.expand(ExpansionInput): ExpansionOutcome`; embedded uses its generated adapter.
- **Shared backend scheduler and transaction: yes within verified current source and tests.** Both use normal handler loading, staged rescanning, validation, and rollback.
- **Same backend target kinds: yes.** The intersection is the supported pre-Typer Class/Trait/Object grammar.
- **All program behavior equivalence proven: no.** Shared backend parity does not prove all program behavior equivalent across annotation programs, edge cases, build topologies, resolution environments, or packaging choices.
- **Authoring declaration parity: no.** Embedded requires an opted-in top-level final `StaticAnnotation` class, same-file companion, and exact `transform`; explicit authors implement the handler protocol.
- **Topology parity: no.** Embedded declaration and companion share one physical source. External same-module Model A uses separate marker and handler files. Consumers are separate files in both bounded cells.
- **Import resolution parity: no.** External Model A is direct-qualified. Embedded same-module also qualifies one bounded explicit-import form.
- **Same-module multi-binding support: no.** `macroParadiseSameModuleBinding` and `macroParadiseSameModuleEmbeddedBinding` are mutually exclusive and each accepts one binding.
- **Precompiled multi-producer support:** qualified for explicit producers through sequence-based local/module integration.
- **Precompiled multiple embedded declarations:** qualified within the declaration contract; it does not imply same-module multiplicity.
- **Full release parity: no.** Released 0.1.1 contains external-handler Model A and its earlier API. Embedded authoring and the current handler protocol are source-built 0.2.0-SNAPSHOT only. No remote 0.2.0 artifact exists.

Semantic parity means a shared underlying expansion mechanism and expressive outcomes within the intersection of supported inputs. It is not mathematical or observational equivalence for every authoring situation.

## Shared same-module lifecycle

1. One explicit sbt binding determines the producer source dependency.
2. A source-byte digest participates in Zinc incremental identity.
3. The consumer is suspended before mutation or handler loading while current producer output compiles.
4. A fresh current-output classloader resumes ordinary expansion.
5. Stale output, missing output, and dependency cycles fail closed.
6. Tested producer-body edits invalidate unchanged consumers without `clean`.

Not implemented or qualified: producer and consumer in the same physical source, multiple same-module bindings, dependency-graph solving, cycles, automatic discovery, native IntelliJ JPS, or direct Java gutter Run.

## Compiler, BSP, and IDE evidence

| Cell | Exact compiler-line evidence | Persistent BSP / IntelliJ qualification |
| --- | --- | --- |
| Explicit precompiled | Public boundaries pass 3.3.8, 3.8.4, 3.9.0 | No new all-line per-cell IDE claim is made here |
| Embedded precompiled | Four setup quadrants remain in all three public boundaries | No dedicated precompiled embedded IDE/BSP claim is added |
| External same-module Model A | CLI/Zinc passes 3.3.8, 3.8.4, 3.9.0 | Persistent BSP and sbt-delegated IntelliJ evidence is bounded to 3.3.8 and 3.8.4 |
| Embedded same-module | CLI/Zinc passes 3.3.8, 3.8.4, 3.9.0 | Persistent BSP: 3.8.4; IntelliJ sbt-shell Build/run: all three lines |

The qualified environment is JDK 25 and sbt 1.12.15. IntelliJ evidence uses sbt import, sbt-delegated Build, and `run` in the sbt shell. Native JPS and direct Java gutter Run are not qualified.

## Argument and API boundary

`@embeddedExpander` is not a new typed Scala 3 macro API. Annotation arguments remain pre-Typer syntax. Supported positional and named forms are decoded syntactically; Macro-Paradise does not evaluate arbitrary expressions, synthesize implicits, materialize constructor defaults, or provide typed name resolution. Both frontends remain bounded to the current Class/Trait/Object target grammar and exact compiler-line handler API.

Use the cell-specific runnable guides rather than copying generated adapter names or inventing manual same-module options.
