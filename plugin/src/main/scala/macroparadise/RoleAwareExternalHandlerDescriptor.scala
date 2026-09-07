package macroparadise

import paradise3.api.*

import scala.util.control.NonFatal

private[macroparadise] enum ExternalHandlerClassClassification:
  case LegacyHandler
  case RoleAwareHandler
  case InvalidNeither
  case InvalidAmbiguousBoth

private[macroparadise] object ExternalHandlerClassClassification:
  def classify(handlerClass: Class[?]): ExternalHandlerClassClassification =
    val legacy = classOf[ParadiseAnnotationExpander].isAssignableFrom(handlerClass)
    val roleAware =
      classOf[RoleAwareParadiseAnnotationExpander].isAssignableFrom(handlerClass)
    (legacy, roleAware) match
      case (true, false)  => LegacyHandler
      case (false, true)  => RoleAwareHandler
      case (false, false) => InvalidNeither
      case (true, true)   => InvalidAmbiguousBoth

private[macroparadise] final case class RoleAwareExternalHandlerDescriptor(
    handlerClassName: String,
    annotationName: String,
    targetAdmissions: List[RoleAwareTargetAdmission],
    compositionPolicy: ExpansionCompositionPolicy,
    oppositeCapability: RoleAwareOppositeCapability
)

private[macroparadise] final case class LoadedRoleAwareExternalHandler(
    instance: RoleAwareParadiseAnnotationExpander,
    descriptor: RoleAwareExternalHandlerDescriptor
) extends LoadedExternalHandlerContract:
  def handlerClassName: String = descriptor.handlerClassName
  def annotationName: String = descriptor.annotationName

private[macroparadise] object RoleAwareExternalHandlerDescriptor:
  final case class Failure(diagnostic: String)

  def capture(
      instance: RoleAwareParadiseAnnotationExpander,
      requestedLoader: ClassLoader
  ): Either[Failure, LoadedRoleAwareExternalHandler] =
    val handlerClass = instance.getClass
    val handlerClassName = handlerClass.getName
    val handlerLoader = handlerClass.getClassLoader

    for
      annotationName <- readAccessor(
        handlerClassName,
        "annotationName",
        requestedLoader,
        handlerLoader
      )(instance.annotationName)
      validatedAnnotationName <- validateAnnotationName(
        handlerClassName,
        annotationName,
        requestedLoader,
        handlerLoader
      )
      admissions <- readAccessor(
        handlerClassName,
        "targetAdmissions",
        requestedLoader,
        handlerLoader
      )(instance.targetAdmissions)
      validatedAdmissions <- validateAdmissions(
        handlerClassName,
        admissions,
        requestedLoader,
        handlerLoader
      )
      composition <- readAccessor(
        handlerClassName,
        "compositionPolicy",
        requestedLoader,
        handlerLoader
      )(instance.compositionPolicy)
      validatedComposition <- requireNonNull(
        handlerClassName,
        "NULL_COMPOSITION_POLICY",
        "compositionPolicy",
        composition,
        requestedLoader,
        handlerLoader
      )
      capability <- readAccessor(
        handlerClassName,
        "oppositeCapability",
        requestedLoader,
        handlerLoader
      )(instance.oppositeCapability)
      validatedCapability <- requireNonNull(
        handlerClassName,
        "NULL_OPPOSITE_CAPABILITY",
        "oppositeCapability",
        capability,
        requestedLoader,
        handlerLoader
      )
    yield LoadedRoleAwareExternalHandler(
      instance,
      RoleAwareExternalHandlerDescriptor(
        handlerClassName,
        validatedAnnotationName,
        validatedAdmissions,
        validatedComposition,
        validatedCapability
      )
    )

  private def validateAnnotationName(
      handlerClassName: String,
      value: String,
      requestedLoader: ClassLoader,
      handlerLoader: ClassLoader
  ): Either[Failure, String] =
    if value == null || value.trim.isEmpty then
      Left(
        invalidDeclaration(
          handlerClassName,
          "INVALID_HANDLER_ANNOTATION_NAME",
          "annotationName",
          requestedLoader,
          handlerLoader,
          "handler returned null, empty, or whitespace-only annotation name"
        )
      )
    else
      SyntacticAnnotationIdentity.fromDeclaredName(value) match
        case Right(identity) => Right(identity.value)
        case Left(detail) =>
          Left(
            invalidDeclaration(
              handlerClassName,
              "INVALID_HANDLER_ANNOTATION_NAME",
              "annotationName",
              requestedLoader,
              handlerLoader,
              s"handler returned `$value`; $detail"
            )
          )

  private def validateAdmissions(
      handlerClassName: String,
      values: List[RoleAwareTargetAdmission],
      requestedLoader: ClassLoader,
      handlerLoader: ClassLoader
  ): Either[Failure, List[RoleAwareTargetAdmission]] =
    val failure =
      if values == null then
        Some("NULL_TARGET_ADMISSIONS" -> "handler returned null")
      else if values.isEmpty then
        Some("EMPTY_TARGET_ADMISSIONS" -> "handler returned an empty admission list")
      else if values.exists(_ == null) then
        Some("NULL_TARGET_ADMISSION" -> "handler returned a null admission entry")
      else if values.distinct.size != values.size then
        Some("DUPLICATE_TARGET_ADMISSION" -> "handler returned duplicate admission entries")
      else if values.exists(_ != RoleAwareTargetAdmission.OrdinaryTopLevelObject) then
        Some("UNSUPPORTED_TARGET_ADMISSION" -> "only OrdinaryTopLevelObject is supported")
      else None

    failure match
      case Some((category, detail)) =>
        Left(
          invalidDeclaration(
            handlerClassName,
            category,
            "targetAdmissions",
            requestedLoader,
            handlerLoader,
            detail
          )
        )
      case None => Right(values)

  private def requireNonNull[A](
      handlerClassName: String,
      category: String,
      accessor: String,
      value: A,
      requestedLoader: ClassLoader,
      handlerLoader: ClassLoader
  ): Either[Failure, A] =
    if value == null then
      Left(
        invalidDeclaration(
          handlerClassName,
          category,
          accessor,
          requestedLoader,
          handlerLoader,
          "handler returned null"
        )
      )
    else Right(value)

  private def readAccessor[A](
      handlerClassName: String,
      accessor: String,
      requestedLoader: ClassLoader,
      handlerLoader: ClassLoader
  )(value: => A): Either[Failure, A] =
    try Right(value)
    catch
      case error: LinkageError =>
        Left(accessorFailure(handlerClassName, accessor, requestedLoader, handlerLoader, error))
      case NonFatal(error) =>
        Left(accessorFailure(handlerClassName, accessor, requestedLoader, handlerLoader, error))

  private def accessorFailure(
      handlerClassName: String,
      accessor: String,
      requestedLoader: ClassLoader,
      handlerLoader: ClassLoader,
      error: Throwable
  ): Failure =
    Failure(
      ExternalHandlerDiagnostics.render(
        ExternalHandlerDiagnostics.Stage.Loading,
        "HANDLER_DECLARATION_FAILURE",
        "handler" -> handlerClassName,
        "accessor" -> accessor,
        "loaderPolicy" -> "parent-first",
        "requestedLoader" -> ExternalHandlerDiagnostics.loaderIdentity(requestedLoader),
        "handlerLoader" -> ExternalHandlerDiagnostics.loaderIdentity(handlerLoader),
        "cause" -> error.getClass.getName,
        "message" -> ExternalHandlerDiagnostics.normalize(error.getMessage),
        "detail" -> s"role-aware external handler `$handlerClassName` failed while evaluating `$accessor`"
      )
    )

  private def invalidDeclaration(
      handlerClassName: String,
      category: String,
      accessor: String,
      requestedLoader: ClassLoader,
      handlerLoader: ClassLoader,
      detail: String
  ): Failure =
    Failure(
      ExternalHandlerDiagnostics.render(
        ExternalHandlerDiagnostics.Stage.Loading,
        category,
        "handler" -> handlerClassName,
        "accessor" -> accessor,
        "loaderPolicy" -> "parent-first",
        "requestedLoader" -> ExternalHandlerDiagnostics.loaderIdentity(requestedLoader),
        "handlerLoader" -> ExternalHandlerDiagnostics.loaderIdentity(handlerLoader),
        "detail" -> detail
      )
    )
