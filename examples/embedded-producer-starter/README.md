# Embedded producer starter

This copyable example demonstrates the preferred same-build embedded frontend
on current source-built `0.2.0-SNAPSHOT`. It is not part of the immutable
released `0.1.1` feature set, and no remote `0.2.0-SNAPSHOT` coordinates are
promised.

The example has a precompiled producer and an ordinary consumer:

```text
embedded-producer/
  @identityEmbedded
  @addGreeting(prefix)
  companion transform methods
core/
  annotated consumer classes
```

The producer is compiled once. The integration derives separate marker and handler roles:
only the marker role enters the consumer's ordinary compile classpath, while the complete
handler closure remains a compiler tool path.
The preferred same-build route requires no producer `publishLocal`.

## Before running

Use JDK 25 and sbt 1.12.15. From the Macro-Paradise source repository, install
the current exact-line product artifacts locally. For example, for Scala 3.8.4:

```sh
sbt -Dmacroparadise.exactScalaVersion=3.8.4 -batch "++3.8.4!"   "pluginApi/publishLocal"   "embeddedProducerPlugin/publishLocal"   "plugin/publishLocal"

cd sbt-integration
sbt -batch verifyIntegrationPolicy publishLocal
```

Return to this directory and run:

```sh
sbt -batch "core/run"
```

Expected output includes:

```text
PUBLIC_EMBEDDED_STARTER_PASS
```

The exact supported Scala lines are 3.3.8, 3.8.4, and 3.9.0. To select one
explicitly:

```sh
sbt -Dmacroparadise.example.scalaVersion=3.3.8 -batch "core/verifyEmbeddedStarter"
sbt -Dmacroparadise.example.scalaVersion=3.8.4 -batch "core/verifyEmbeddedStarter"
sbt -Dmacroparadise.example.scalaVersion=3.9.0 -batch "core/verifyEmbeddedStarter"
```

## What the source demonstrates

`identityEmbedded` is behavior-free and proves the wiring. `addGreeting` uses
`AnnotationApplication.fromInput(input)` to decode both positional and named
string-literal arguments, then places `generatedGreeting` in the annotated
class.

Annotation constructor arguments are pre-typer syntax, not instantiated JVM
annotation values. The example does not evaluate arbitrary expressions,
materialize defaults, synthesize implicits, or provide semantic typing.

Macro-Paradise generates an ordinary no-argument `ExpansionHandler` adapter and
existing `@expander` metadata. Users never name that adapter.
The generated adapter name is not a public contract, and the generated class is not a user API.

The same-module use is unsupported: the embedded declaration belongs to a
precompiled producer and the annotated consumer compiles afterward.

External handler authoring remains supported as a first-class modular alternative.

For the complete same-build, resolver-installed, AutoPlugin, and manual
recipes, see [Embedded producer authoring](../../docs/EMBEDDED_PRODUCER_AUTHORING.md).
