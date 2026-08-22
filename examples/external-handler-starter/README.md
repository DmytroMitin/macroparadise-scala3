# External Handler Starter

This nested sbt build is a fixture-independent example of a precompiled
external marker, handler, and ordinary consumer. The supported entrypoint is
the repository-root task:

```text
sbt -batch verifyExternalHandlerAuthoringStarter
```

The checked-in `project/build.properties` pins `sbt.version=1.12.15` so the
ordinary runner, direct launcher, IntelliJ, and BSP select the same build-tool
version. The installed runner's own version is not the build version.

The root task supplies local packaged production plugin and `pluginApi` paths,
isolates the child sbt state under the repository `target/` directory, and runs
both explicit and compact positive prechecks. It preserves the P1-P7 explicit
fail-closed matrix and the focused C1-C6 compact matrix. M1-M9 then run paired
explicit/compact metadata-authoring failures and compare their category and
core diagnostic fields.

The projects are intentionally separate:

- `marker` packages `starter.marker.generateGreeting` with
  `@expander("starter.handler.GenerateGreetingHandler")`;
- `handler` packages `starter.handler.GenerateGreetingHandler` against only
  `pluginApi` plus the exact compiler/runtime universe;
- `consumer` explicitly imports `starter.marker.generateGreeting`, uses
  `@generateGreeting`, and ordinarily typechecks `new Greeter().generatedGreeting`;
- `precheck-fixtures/marker` and `precheck-fixtures/handler` exist only for the
  metadata/descriptor robustness lanes;
- `metadata-fixtures/handler-b` packages only handler B so the marker-A versus
  supplied-artifact-B case is real rather than simulated.

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
major. It derives the running self-contained plugin JAR from its code source,
selects the unique separate authoring `pluginApi` JAR from the handler compile
classpath, and derives marker class from the retained qualified annotation
expectation. It does not derive toolchain expectations from the observed
runtime.

Metadata failures report `failureStage`, marker/expected annotation identities,
metadata/expected handler identities, and marker/handler artifact paths. Both
command forms retain the same category and core fields for the same fault, and
every negative lane proves consumer compilation and expansion stayed stopped.

See
[`docs/EXTERNAL_HANDLER_AUTHORING.md`](../../docs/EXTERNAL_HANDLER_AUTHORING.md)
for the contract, precheck categories, limitations, and evidence model.
