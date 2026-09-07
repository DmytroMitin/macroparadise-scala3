package macroparadise

import scala.collection.mutable

private[macroparadise] final class ExternalHandlerContractRunCache(
    explicitHandlers: List[LoadedExternalHandlerContract]
):
  enum Origin:
    case Explicit, Discovered

  final case class Resolution(
      loadedHandler: Option[LoadedExternalHandlerContract],
      origin: Origin
  )

  private val explicitByClassName =
    explicitHandlers.iterator.map(handler => handler.handlerClassName -> handler).toMap

  private val discoveredByClassName =
    mutable.Map.empty[String, Option[LoadedExternalHandlerContract]]

  def resolve(
      handlerClassName: String
  )(load: => Option[LoadedExternalHandlerContract]): Resolution =
    explicitByClassName.get(handlerClassName) match
      case Some(handler) => Resolution(Some(handler), Origin.Explicit)
      case None =>
        Resolution(
          discoveredByClassName.getOrElseUpdate(handlerClassName, load),
          Origin.Discovered
        )

private[macroparadise] object ExternalHandlerContractBinding:
  final case class Failure(diagnostic: String)

  def validate(
      metadataAnnotationName: String,
      metadataHandlerClassName: String,
      loadedHandler: LoadedExternalHandlerContract,
      requestedLoader: ClassLoader
  ): Either[Failure, LoadedExternalHandlerContract] =
    if loadedHandler.annotationName == metadataAnnotationName then Right(loadedHandler)
    else
      Left(
        Failure(
          ExternalHandlerDiagnostics.render(
            ExternalHandlerDiagnostics.Stage.Loading,
            "METADATA_HANDLER_ANNOTATION_MISMATCH",
            "annotation" -> s"@$metadataAnnotationName",
            "metadataHandler" -> metadataHandlerClassName,
            "declaredAnnotation" -> s"@${loadedHandler.annotationName}",
            "loaderPolicy" -> "parent-first",
            "requestedLoader" -> ExternalHandlerDiagnostics.loaderIdentity(requestedLoader),
            "detail" -> s"metadata for `@$metadataAnnotationName` selects `$metadataHandlerClassName`, but its captured descriptor declares `@${loadedHandler.annotationName}`"
          )
        )
      )
