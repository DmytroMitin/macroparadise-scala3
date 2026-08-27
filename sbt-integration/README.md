# Source-built sbt integration

This directory contains the first opt-in sbt integration slice for precompiled
Macro-Paradise markers and handlers. It is built in sbt 1.x's Scala 2.12
plugin universe and has no Scala 3 compiler or runtime dependency of its own.

The integration selects the compiler plugin and authoring API with
`CrossVersion.full`, keeps published handlers in a hidden configuration, and
derives Zinc compiler-option identity from every explicit marker artifact plus
the complete ordered effective handler expansion classpath. Marker projects
remain explicit ordinary dependencies of the consumer.

For local projects, enable `MacroParadisePrecompiledPlugin`, set the product
version, and either configure published marker and handler modules or use
`MacroParadiseIntegration.precompiledProjects(marker, handler)`. The helper
returns settings only: the consumer must still declare `.dependsOn(marker)`.
Manual `scalacOptions` wiring remains an escape hatch.

This module is source-built and unreleased. No remote sbt-plugin coordinate is
claimed, and the current slice does not add same-module handler support.

Verify it independently with:

```sh
sbt -batch verifyIntegrationPolicy test scripted packageSrc packageDoc
```
