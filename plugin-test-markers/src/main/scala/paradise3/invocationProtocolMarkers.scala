package paradise3

/** Test-only marker annotations for external invocation/outcome protocol fixtures.
  *
  * They intentionally have no `@expander` metadata. Each fixture registers one
  * precompiled handler explicitly so loading/registration behavior stays
  * separate from invocation and returned-outcome behavior.
  */
final class invocationThrows extends scala.annotation.StaticAnnotation
final class invocationLinkageError extends scala.annotation.StaticAnnotation
final class invocationNullOutcome extends scala.annotation.StaticAnnotation
final class invocationNotApplicable extends scala.annotation.StaticAnnotation
final class invocationEmptyRejected extends scala.annotation.StaticAnnotation
final class invocationNullRejectedDiagnostics extends scala.annotation.StaticAnnotation
final class invocationNullRejectedFallback extends scala.annotation.StaticAnnotation
final class invocationWrongFallback extends scala.annotation.StaticAnnotation
final class companionInvocationThrows extends scala.annotation.StaticAnnotation
final class companionInvocationLinkageError extends scala.annotation.StaticAnnotation
final class companionInvocationNullOutcome extends scala.annotation.StaticAnnotation
final class companionInvocationNotApplicable extends scala.annotation.StaticAnnotation
final class companionInvocationRejected extends scala.annotation.StaticAnnotation
final class companionInvocationWrongFallback extends scala.annotation.StaticAnnotation
