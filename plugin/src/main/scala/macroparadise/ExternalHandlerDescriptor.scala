package macroparadise

import paradise3.api.{
  ExpansionCompositionPolicy,
  ExpansionTargetProfile,
  ParadiseAnnotationExpander
}

import scala.util.control.NonFatal

private[macroparadise] final case class ExternalHandlerDescriptor(
    handlerClassName: String,
    annotationName: String,
    targetProfile: ExpansionTargetProfile,
    compositionPolicy: ExpansionCompositionPolicy,
    consumesExistingCompanion: Boolean
)

private[macroparadise] final case class LoadedExternalHandler(
    instance: ParadiseAnnotationExpander,
    descriptor: ExternalHandlerDescriptor,
    metadataFailureAlreadyReported: Boolean = false
)

private[macroparadise] object ExternalHandlerDescriptor:
  final case class LoaderOwnership(
      requestedLoader: ClassLoader,
      handlerLoader: ClassLoader
  )

  final case class Failure(diagnostic: String)

  def capture(
      instance: ParadiseAnnotationExpander,
      requestedLoader: ClassLoader
  ): Either[Failure, LoadedExternalHandler] =
    val handlerClass = instance.getClass
    val handlerClassName = handlerClass.getName
    val ownership = LoaderOwnership(requestedLoader, handlerClass.getClassLoader)

    for
      annotationName <- readAccessor(
        handlerClassName,
        "annotationName",
        ownership
      )(instance.annotationName)
      validatedAnnotationName <- validateAnnotationName(
        handlerClassName,
        annotationName,
        ownership
      )
      targetProfile <- readAccessor(
        handlerClassName,
        "targetProfile",
        ownership
      )(instance.targetProfile)
      validatedTargetProfile <- validateTargetProfile(
        handlerClassName,
        targetProfile,
        ownership
      )
      compositionPolicy <- readAccessor(
        handlerClassName,
        "compositionPolicy",
        ownership
      )(instance.compositionPolicy)
      validatedCompositionPolicy <- validateCompositionPolicy(
        handlerClassName,
        compositionPolicy,
        ownership
      )
      consumesExistingCompanion <- readAccessor(
        handlerClassName,
        "consumesExistingCompanion",
        ownership
      )(instance.consumesExistingCompanion)
    yield LoadedExternalHandler(
      instance,
      ExternalHandlerDescriptor(
        handlerClassName,
        validatedAnnotationName,
        validatedTargetProfile,
        validatedCompositionPolicy,
        consumesExistingCompanion
      )
    )

  private def validateAnnotationName(
      handlerClassName: String,
      annotationName: String,
      ownership: LoaderOwnership
  ): Either[Failure, String] =
    if annotationName == null then
      Left(
        invalidDeclaration(
          handlerClassName,
          "INVALID_HANDLER_ANNOTATION_NAME",
          "annotationName",
          ownership,
          "handler returned null"
        )
      )
    else if annotationName.trim.isEmpty then
      Left(
        invalidDeclaration(
          handlerClassName,
          "INVALID_HANDLER_ANNOTATION_NAME",
          "annotationName",
          ownership,
          "handler returned an empty or whitespace-only annotation name"
        )
      )
    else
      SyntacticAnnotationIdentity.fromDeclaredName(annotationName) match
        case Right(identity) => Right(identity.value)
        case Left(detail) =>
          Left(
            invalidDeclaration(
              handlerClassName,
              "INVALID_HANDLER_ANNOTATION_NAME",
              "annotationName",
              ownership,
              s"handler returned `$annotationName`; $detail"
            )
          )

  private def validateTargetProfile(
      handlerClassName: String,
      targetProfile: ExpansionTargetProfile,
      ownership: LoaderOwnership
  ): Either[Failure, ExpansionTargetProfile] =
    if targetProfile == null then
      Left(
        invalidDeclaration(
          handlerClassName,
          "NULL_TARGET_PROFILE",
          "targetProfile",
          ownership,
          "handler returned null"
        )
      )
    else Right(targetProfile)

  private def validateCompositionPolicy(
      handlerClassName: String,
      compositionPolicy: ExpansionCompositionPolicy,
      ownership: LoaderOwnership
  ): Either[Failure, ExpansionCompositionPolicy] =
    if compositionPolicy == null then
      Left(
        invalidDeclaration(
          handlerClassName,
          "NULL_COMPOSITION_POLICY",
          "compositionPolicy",
          ownership,
          "handler returned null"
        )
      )
    else Right(compositionPolicy)

  private def readAccessor[A](
      handlerClassName: String,
      accessor: String,
      ownership: LoaderOwnership
  )(value: => A): Either[Failure, A] =
    try Right(value)
    catch
      case error: LinkageError =>
        Left(accessorFailure(handlerClassName, accessor, ownership, error))
      case NonFatal(error) =>
        Left(accessorFailure(handlerClassName, accessor, ownership, error))

  private def accessorFailure(
      handlerClassName: String,
      accessor: String,
      ownership: LoaderOwnership,
      error: Throwable
  ): Failure =
    Failure(
      ExternalHandlerDiagnostics.render(
        ExternalHandlerDiagnostics.Stage.Loading,
        "HANDLER_DECLARATION_FAILURE",
        "handler" -> handlerClassName,
        "accessor" -> accessor,
        "loaderPolicy" -> "parent-first",
        "requestedLoader" -> ExternalHandlerDiagnostics.loaderIdentity(
          ownership.requestedLoader
        ),
        "handlerLoader" -> ExternalHandlerDiagnostics.loaderIdentity(
          ownership.handlerLoader
        ),
        "cause" -> error.getClass.getName,
        "message" -> ExternalHandlerDiagnostics.normalize(error.getMessage),
        "detail" -> s"external annotation handler `$handlerClassName` failed while evaluating `$accessor`"
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
        "loaderPolicy" -> "parent-first",
        "requestedLoader" -> ExternalHandlerDiagnostics.loaderIdentity(
          ownership.requestedLoader
        ),
        "handlerLoader" -> ExternalHandlerDiagnostics.loaderIdentity(
          ownership.handlerLoader
        ),
        "detail" -> detail
      )
    )
