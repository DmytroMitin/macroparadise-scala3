package starter.metadata

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.{ExpansionInput, ExpansionOutcome, ParadiseAnnotationExpander}

abstract class AuthoringHandler extends ParadiseAnnotationExpander:
  final def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.NotApplicable

final class NotAHandler

final class HandlerA extends AuthoringHandler:
  val annotationName: String = "starter.metadata.handlerA"

final class DescriptorMismatchHandler extends AuthoringHandler:
  val annotationName: String = "starter.metadata.other"

final class StableHandler extends AuthoringHandler:
  val annotationName: String = "starter.metadata.current"

final class QualifiedMismatchHandler extends AuthoringHandler:
  val annotationName: String = "starter.beta.audit"

final class EmptyHandler extends AuthoringHandler:
  val annotationName: String = "starter.metadata.emptyMetadata"
