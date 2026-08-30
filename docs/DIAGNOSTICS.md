# Diagnostics and troubleshooting

The plugin aims to turn unsupported or malformed experimental inputs into
focused diagnostics rather than compiler crashes or partial output.

## Identify the failing stage

External-handler failures normally belong to one of these stages:

1. toolchain and artifact precheck;
2. marker metadata discovery;
3. handler loading and immutable descriptor capture;
4. target admission;
5. handler invocation;
6. raw or structured output validation;
7. composition and handled-annotation closure;
8. ordinary Scala typing after successful insertion.

Include the stage, exact compiler/JDK versions, and full diagnostic when
reporting a non-sensitive problem.

## Common failures

### JDK rejection during project load

The build requires JDK feature version 25. A bootstrap setting rejects an
unsupported JVM before ordinary `project/*.scala` helper compilation. In
IntelliJ IDEA, select JDK 25 under
`Settings | Build, Execution, Deployment | Build Tools | sbt | JVM | JRE`, set
the project SDK to JDK 25 as well, and refresh or reimport the sbt project. If
the import command starts with a Java 8 executable, the wrong sbt JVM is
selected; changing the Scala SDK or version is not a fix. There is no supported
bypass.

### Exact compiler mismatch

The plugin, handler API, handler, and consumer must use one exact supported
line: `3.3.8` or `3.8.4`. Rebuilding only one raw-tree participant with the
other line fails closed as an exact compiler mismatch.

### Missing handler

The loading diagnostic reports `handlerClasspathConfigured=false` and
`handlerClasspathEntries=0` when
`-P:macroparadise:handlerClasspath=<handler-jar-or-path-list>` is absent. If
entries were supplied but the selected class still cannot load, it reports the
configured entry count without dumping the producer's local paths.

The ordinary source compilation classpath, compiler-plugin loader classpath,
explicit external handler classpath, and marker metadata are separate roles.
In particular, `.dependsOn(handler)` can place handler classes on the ordinary
source classpath without making them visible to the isolated handler loader.
Confirm that the handler JAR and any required dependency JARs are supplied
through the explicit option. Marker metadata names a class; it does not package
or load the implementation by itself. The packaged precheck reports
`failureStage=handler-artifact` when the supplied handler JAR does not contain
the independently expected class, together with `markerIdentity`,
`metadataHandler`, `expectedHandler`, and `handlerArtifact`.

### Invalid marker handler metadata

The `@expander` value must be a canonical simple or dot-qualified JVM class
name. Empty, whitespace-only, or malformed names fail as
`INVALID_METADATA_HANDLER_CLASS_NAME` at
`failureStage=metadata-selection`; the precheck does not pass the malformed
value to generic JVM class loading or scan other classpaths for a guess.

### Annotation identity mismatch

The normal form is one unambiguous, source-preceding package-level explicit
import followed by the short annotation, such as `import a.b.identity` and
`@identity`, while the handler declares canonical identity `a.b.identity`.
Direct `@a.b.identity` syntax remains supported as a control or fallback. The
imported-short form is canonicalized syntactically before typer; it does not
enable renamed/aliased, wildcard, local/nested, given, export, shadowing-
dependent, package-object, or semantic resolution. Two matching explicit
imports produce a deterministic ambiguity diagnostic with both canonical
candidates. An unwitnessed short name retains the existing fail-closed
discovery behavior.

### Marker missing from the ordinary consumer classpath

The consumer must `.dependsOn(marker)`. The handler JAR supplied through
`handlerClasspath` does not add the marker to the ordinary compile classpath.
If the edge is missing, Dotty can report unresolved-import and cyclic-completion
diagnostics around the annotation. Treat both as ordinary build-graph or
type-resolution evidence, not as a Macro-Paradise-generated compiler cycle.

### Target rejected before invocation

The common profile admits a bounded top-level class. The restricted trait
profile has its own exact one-type-parameter envelope. Case classes, generic
classes under the common profile, nested/local definitions, objects, enums, and
unsupported trait shapes are rejected before handler execution.

### Malformed handler result

The plugin validates primary/companion identity, output ordering, duplicate and
package conflicts, nulls, supported structured roles, fallback identity, and
annotation preservation. It does not repair a malformed result. The original
class and leased companion are restored on failure.

### Ordinary typer diagnostic

After validated output is inserted, ordinary Scala typing remains authoritative.
Native typer errors are not rewritten as handler diagnostics. Check the
generated tree shape, names in scope, and exact target assumptions.

### Same-module stale output

General incremental same-module support remains false. Unreleased `main` has an
opt-in implementation of one explicit different-file Model A, qualified on
exact Scala 3.3.8 and 3.8.4 through CLI/Zinc and persistent sbt BSP. Confirm
that `scalacOptions` contains one `sameModuleHandler` relationship and one
`sameModuleSourceIdentity=sha256:...` input, and that the marker and handler
paths are distinct normalized relative paths beneath the configured source
root. Empty, missing, duplicate, absolute, or escaping paths fail closed.

The marker, handler, and consumer must be separate source files. Same-file
topologies and cycles produce focused unsupported-model diagnostics. On
unreleased `main`, the bounded topology is qualified in IntelliJ only for an
sbt-imported project with Build and Run delegated to sbt on JDK 25 and sbt
1.12.15, across exact Scala 3.3.8 and 3.8.4. Native JPS and broader same-module
topologies remain outside the claim; precompiled handlers remain the
broad/default supported experimental path.

### Precompiled-handler stale output

If the handler JAR is rebuilt at the same path but generated behavior does not
change, verify that the consumer `scalacOptions` include the build-only
`externalArtifactIdentity` derived from every explicit marker artifact and the
complete ordered effective handler expansion classpath. The `packageBin`
dependency provides ordering, not a content-sensitive Zinc input. A no-op build
should retain the identity; changing handler bytes, a handler dependency, or
marker binding metadata should change it and recompile the consumer without
`clean`.

## Useful commands

Canonical product gate:

```sh
sbt -batch verifyPublicProductBoundary
```

External-handler authoring and negative matrix:

```sh
sbt -batch verifyExternalHandlerAuthoringStarter
```

Packaged precheck usage, without loading marker or handler artifacts:

```sh
java -cp <plugin-and-exact-runtime-classpath> \
  macroparadise.ExternalHandlerPrecheckMain --help
```

The help distinguishes the ten-input explicit form from the seven-input compact
form. Compact mode derives the running self-contained plugin JAR, selects the
unique separate authoring `pluginApi` JAR from handler compile evidence, and
derives marker class; expected handler, annotation, Scala version, and JDK major
remain independent caller inputs. A runtime class loaded from a directory, a
non-file code source, or an ambiguous/missing authoring API path fails closed.

Argument, compact-derivation, and precheck failures exit with status 2 and
explicitly report that consumer compilation and expansion did not start.
Equivalent explicit and compact metadata faults retain the same category and
core authoring fields; `declaredAnnotation` is added when a loaded handler
descriptor disagrees with the marker identity.

Whitespace and patch sanity:

```sh
git diff --check
```

See [Support](../SUPPORT.md) for issue context and [Security](../SECURITY.md)
before sharing sensitive information.
