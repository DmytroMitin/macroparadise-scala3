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

The plugin, handler API, handler, and consumer must use
`3.8.5-RC1-bin-20260405-9478256-NIGHTLY`. Rebuilding only one raw-tree
participant with another compiler line is unsupported.

### Missing handler

Confirm the handler JAR exists and is supplied through
`handlerClasspath`. Marker metadata names a class; it does not package or load
the implementation by itself. The packaged precheck reports
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

Prefer an exact qualified source form. Imported simple names, aliases, and
wildcards are not semantically resolved. Two metadata-bearing classes with the
same simple name cause the simple identity to fail closed.

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

General incremental same-module support is not implemented. A clean build can
exercise the research prototype, but it is not an automatic correctness story
for handler-only edits. Use the precompiled-handler starter for supported
experimental evaluation.

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
form. Compact mode derives only the running plugin JAR, the parent-loaded
`pluginApi` JAR, and marker class; expected handler, annotation, Scala version,
and JDK major remain independent caller inputs. A runtime class loaded from a
directory, a non-file code source, or an API path absent from the handler
compile evidence fails closed.

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
