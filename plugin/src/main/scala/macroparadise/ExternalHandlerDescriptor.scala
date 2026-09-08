package macroparadise

import paradise3.api.{ExpansionAdmission, ExpansionHandler}

import scala.util.control.NonFatal

private[macroparadise] final case class ExternalHandlerDescriptor(
    handlerClassName: String,
    annotationName: String,
    admissions: List[ExpansionAdmission]
)

private[macroparadise] trait LoadedExternalHandlerContract:
  def handlerClassName: String
  def annotationName: String

private[macroparadise] final case class LoadedExternalHandler(
    instance: ExpansionHandler,
    descriptor: ExternalHandlerDescriptor,
    metadataFailureAlreadyReported: Boolean = false
) extends LoadedExternalHandlerContract:
  def handlerClassName: String = descriptor.handlerClassName
  def annotationName: String = descriptor.annotationName

private[macroparadise] enum ExternalHandlerClassClassification:
  case Handler
  case Invalid

private[macroparadise] object ExternalHandlerClassClassification:
  def classify(handlerClass: Class[?]): ExternalHandlerClassClassification =
    if classOf[ExpansionHandler].isAssignableFrom(handlerClass) then
      ExternalHandlerClassClassification.Handler
    else ExternalHandlerClassClassification.Invalid

private[macroparadise] object ExternalHandlerDescriptor:
  final case class LoaderOwnership(
      requestedLoader: ClassLoader,
      handlerLoader: ClassLoader
  )

  final case class Failure(diagnostic: String)

  def capture(
      instance: ExpansionHandler,
      requestedLoader: ClassLoader
  ): Either[Failure, LoadedExternalHandler] =
    val handlerClass = instance.getClass
    val handlerClassName = handlerClass.getName
    val ownership = LoaderOwnership(requestedLoader, handlerClass.getClassLoader)

    for
      annotationName <- readAccessor(handlerClassName, "annotationName", ownership)(
        instance.annotationName
      )
      validatedAnnotationName <- validateAnnotationName(
        handlerClassName,
        annotationName,
        ownership
      )
      admissions <- readAccessor(handlerClassName, "admissions", ownership)(
        instance.admissions
      )
      validatedAdmissions <- validateAdmissions(
        handlerClassName,
        admissions,
        ownership
      )
    yield LoadedExternalHandler(
      instance,
      ExternalHandlerDescriptor(
        handlerClassName,
        validatedAnnotationName,
        validatedAdmissions
      )
    )

  private def validateAnnotationName(
      handlerClassName: String,
      annotationName: String,
      ownership: LoaderOwnership
  ): Either[Failure, String] =
    if annotationName == null then
      Left(invalidDeclaration(handlerClassName, "INVALID_HANDLER_ANNOTATION_NAME", "annotationName", ownership, "handler returned null"))
    else if annotationName.trim.isEmpty then
      Left(invalidDeclaration(handlerClassName, "INVALID_HANDLER_ANNOTATION_NAME", "annotationName", ownership, "handler returned an empty or whitespace-only annotation name"))
    else
      SyntacticAnnotationIdentity.fromDeclaredName(annotationName) match
        case Right(identity) => Right(identity.value)
        case Left(detail) =>
          Left(invalidDeclaration(handlerClassName, "INVALID_HANDLER_ANNOTATION_NAME", "annotationName", ownership, s"handler returned `$annotationName`; $detail"))

  private def validateAdmissions(
      handlerClassName: String,
      admissions: List[ExpansionAdmission],
      ownership: LoaderOwnership
  ): Either[Failure, List[ExpansionAdmission]] =
    val failure =
      if admissions == null then Some("handler returned null")
      else if admissions.isEmpty then Some("handler returned an empty admission list")
      else if admissions.exists(_ == null) then Some("handler returned a null admission")
      else if admissions.exists(value => value.targetKind == null || value.shapeProfile == null) then
        Some("handler returned an admission with a null target kind or shape profile")
      else if admissions.distinct.size != admissions.size then
        Some("handler returned duplicate admissions")
      else None
    failure match
      case Some(detail) =>
        Left(invalidDeclaration(handlerClassName, "INVALID_HANDLER_ADMISSIONS", "admissions", ownership, detail))
      case None => Right(admissions)

  private def readAccessor[A](
      handlerClassName: String,
      accessor: String,
      ownership: LoaderOwnership
  )(value: => A): Either[Failure, A] =
    try Right(value)
    catch
      case NonFatal(error) =>
        Left(
          invalidDeclaration(
            handlerClassName,
            "HANDLER_ACCESSOR_FAILURE",
            accessor,
            ownership,
            s"${error.getClass.getName}: ${Option(error.getMessage).getOrElse("")}"
          )
        )

  private def invalidDeclaration(
      handlerClassName: String,
      category: String,
      accessor: String,
      ownership: LoaderOwnership,
      detail: String
  ): Failure =
    Failure(
      ExternalHandlerDiagnostics.render(
        ExternalHandlerDiagnostics.Stage.Loading,
        category,
        "handler" -> handlerClassName,
        "accessor" -> accessor,
        "requestedLoader" -> ExternalHandlerDiagnostics.loaderIdentity(ownership.requestedLoader),
        "handlerLoader" -> ExternalHandlerDiagnostics.loaderIdentity(ownership.handlerLoader),
        "detail" -> detail
      )
    )
