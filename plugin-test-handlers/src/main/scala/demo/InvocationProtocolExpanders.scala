package demo

import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Names.*
import paradise3.api.{
  ExpansionDiagnostic,
  ExpansionInput,
  ExpansionOutcome,
  ParadiseAnnotationExpander
}

private object InvocationProtocolTrees:
  def wrongNameFallback(input: ExpansionInput)(using Context): untpd.TypeDef =
    given dotty.tools.dotc.util.SourceFile = input.annotatedClass.source
    untpd.TypeDef(typeName("WrongRejectedFallback"), input.annotatedClass.rhs)

  def diagnostic(input: ExpansionInput, message: String)(using Context): ExpansionDiagnostic =
    ExpansionDiagnostic(message, input.annotatedClass.sourcePos)

final class InvocationThrowsExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "invocationThrows"

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    throw new IllegalStateException("fixture   ordinary\nfailure")

final class InvocationLinkageErrorExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "invocationLinkageError"

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    throw new NoClassDefFoundError("fixture/missing/InvocationDependency")

final class InvocationNullOutcomeExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "invocationNullOutcome"

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    null

final class InvocationNotApplicableExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "invocationNotApplicable"

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.NotApplicable

final class InvocationEmptyRejectedExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "invocationEmptyRejected"

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.Rejected(Nil, input.annotatedClass)

final class InvocationNullRejectedDiagnosticsExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "invocationNullRejectedDiagnostics"

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.Rejected(null, input.annotatedClass)

final class InvocationNullRejectedFallbackExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "invocationNullRejectedFallback"

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.Rejected(
      List(InvocationProtocolTrees.diagnostic(input, "fixture null fallback rejection")),
      null
    )

final class InvocationWrongFallbackExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "invocationWrongFallback"

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.Rejected(
      List(InvocationProtocolTrees.diagnostic(input, "fixture rejected target")),
      InvocationProtocolTrees.wrongNameFallback(input)
    )

final class CompanionInvocationThrowsExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "companionInvocationThrows"
  override val consumesExistingCompanion: Boolean = true

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    throw new IllegalArgumentException("companion fixture failure")

final class CompanionInvocationLinkageErrorExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "companionInvocationLinkageError"
  override val consumesExistingCompanion: Boolean = true

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    throw new NoSuchMethodError("fixture companion linkage")

final class CompanionInvocationNullOutcomeExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "companionInvocationNullOutcome"
  override val consumesExistingCompanion: Boolean = true

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    null

final class CompanionInvocationNotApplicableExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "companionInvocationNotApplicable"
  override val consumesExistingCompanion: Boolean = true

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.NotApplicable

final class CompanionInvocationRejectedExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "companionInvocationRejected"
  override val consumesExistingCompanion: Boolean = true

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.Rejected(
      List(InvocationProtocolTrees.diagnostic(input, "fixture companion rejection")),
      input.annotatedClass
    )

final class CompanionInvocationWrongFallbackExpander extends ParadiseAnnotationExpander:
  val annotationName: String = "companionInvocationWrongFallback"
  override val consumesExistingCompanion: Boolean = true

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.Rejected(
      List(InvocationProtocolTrees.diagnostic(input, "fixture companion rejection")),
      InvocationProtocolTrees.wrongNameFallback(input)
    )
