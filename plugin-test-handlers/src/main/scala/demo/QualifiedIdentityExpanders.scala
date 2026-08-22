package demo

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.{ExpansionInput, ExpansionOutcome, ParadiseAnnotationExpander}
import paradise3.api.helpers.ExpansionHelpers

private trait QualifiedStringMethodExpander extends ParadiseAnnotationExpander:
  protected def methodName: String
  protected def prefix: String

  final def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionHelpers.withAnnotatedClassView(input): view =>
      ExpansionHelpers.addStringMethodToClass(
        input,
        methodName,
        s"$prefix:${view.className}"
      )

final class QualifiedOneAuditExpander extends QualifiedStringMethodExpander:
  val annotationName: String = "qualifiedone.audit"
  protected val methodName: String = "qualifiedOneAuditName"
  protected val prefix: String = "one"

final class QualifiedTwoAuditExpander extends QualifiedStringMethodExpander:
  val annotationName: String = "qualifiedtwo.audit"
  protected val methodName: String = "qualifiedTwoAuditName"
  protected val prefix: String = "two"

final class LegacySimpleAuditExpander extends QualifiedStringMethodExpander:
  val annotationName: String = "audit"
  protected val methodName: String = "legacySimpleAuditName"
  protected val prefix: String = "legacy"

final class DuplicateQualifiedOneAuditExpander extends QualifiedStringMethodExpander:
  val annotationName: String = "qualifiedone.audit"
  protected val methodName: String = "duplicateQualifiedOneAuditName"
  protected val prefix: String = "duplicate"

final class WrongQualifiedBindingExpander extends QualifiedStringMethodExpander:
  val annotationName: String = "qualifiedtwo.audit"
  protected val methodName: String = "wrongQualifiedBindingName"
  protected val prefix: String = "wrong"

final class QualifiedGenLookalikeExpander extends QualifiedStringMethodExpander:
  val annotationName: String = "qualifiedlookalike.gen"
  protected val methodName: String = "qualifiedGenName"
  protected val prefix: String = "qualified-gen"
