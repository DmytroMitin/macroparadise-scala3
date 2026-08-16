# External Handler Starter

This nested sbt build is a fixture-independent example of a precompiled
external marker, handler, and ordinary consumer. The supported entrypoint is
the repository-root task:

```text
sbt -batch verifyExternalHandlerAuthoringStarter
```

The root task supplies local packaged production plugin and `pluginApi` paths,
isolates the child sbt state under the repository `target/` directory, and runs
both explicit and compact positive prechecks. It preserves the P1-P7 explicit
fail-closed matrix and adds the focused C1-C6 compact matrix.

The projects are intentionally separate:

- `marker` packages `starter.marker.generateGreeting` with
  `@expander("starter.handler.GenerateGreetingHandler")`;
- `handler` packages `starter.handler.GenerateGreetingHandler` against only
  `pluginApi` plus the exact compiler/runtime universe;
- `consumer` uses `@starter.marker.generateGreeting` and ordinarily typechecks
  `new Greeter().generatedGreeting`;
- `precheck-fixtures/marker` and `precheck-fixtures/handler` exist only for the
  metadata/descriptor mismatch negative lane.

Direct use of the nested build requires JDK 25 and explicit local artifact
properties:

```text
sbt -batch \
  -Dmacroparadise.starter.plugin=/absolute/path/to/plugin.jar \
  -Dmacroparadise.starter.pluginApi=/absolute/path/to/plugin-api.jar \
  clean verifyStarter verifyNegativeMatrix
```

Those paths are local packaged files, not published coordinates. Do not put the
production plugin or repository test fixtures on the handler's direct compile
classpath. The starter owns its task-local evidence trace; no undocumented
trace property is required. `verifyStarter` runs marker and handler compilation,
the zero-expansion precheck, consumer compilation, and `Hello, Greeter!` runtime
use in that order. If the precheck fails, consumer compilation does not start.

To inspect the packaged precheck inputs and artifact roles without running it,
use the production plugin and its exact runtime classpath:

```text
java -cp <plugin-and-exact-runtime-classpath> \
  macroparadise.ExternalHandlerPrecheckMain --help
```

Explicit mode repeats all ten logical inputs for maximum independent caller
expectations. Compact mode retains seven: marker, handler, handler compile
classpath, expected handler, expected annotation, exact Scala version, and JDK
major. It derives the running plugin and parent-loaded `pluginApi` JAR paths
from their code sources and derives marker class from the retained qualified
annotation expectation. It does not derive toolchain expectations from the
observed runtime.

See
[`docs/EXTERNAL_HANDLER_AUTHORING.md`](../../docs/EXTERNAL_HANDLER_AUTHORING.md)
for the contract, precheck categories, limitations, and evidence model.
