package paradise3

/** Test-only precompiled marker annotations for raw-output validation fixtures.
  *
  * They intentionally have no `@expander` metadata: integration tests register
  * each malformed handler explicitly with `handler=...`.
  */
final class malformedEmptyOutput extends scala.annotation.StaticAnnotation
final class malformedMissingPrimary extends scala.annotation.StaticAnnotation
final class malformedDuplicatePrimary extends scala.annotation.StaticAnnotation
final class malformedConflictingAdditional extends scala.annotation.StaticAnnotation
final class malformedLateCompanion extends scala.annotation.StaticAnnotation
