package paradise3

/** Test-only marker annotations for hostile structured-outcome fixtures.
  *
  * They intentionally have no `@expander` metadata. Each integration test
  * registers exactly one precompiled handler so malformed returned values are
  * exercised through the real external invocation boundary.
  */
final class structuredNullOutput extends scala.annotation.StaticAnnotation
final class structuredNullPrimary extends scala.annotation.StaticAnnotation
final class structuredNullCompanionOption extends scala.annotation.StaticAnnotation
final class structuredNullCompanion extends scala.annotation.StaticAnnotation
final class structuredNullAdditionalList extends scala.annotation.StaticAnnotation
final class structuredNullAdditionalElement extends scala.annotation.StaticAnnotation
final class structuredUnknownAdditional extends scala.annotation.StaticAnnotation
final class structuredTopLevelConflict extends scala.annotation.StaticAnnotation
