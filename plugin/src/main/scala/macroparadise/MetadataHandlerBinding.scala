package macroparadise

import scala.collection.mutable

private[macroparadise] final case class MetadataHandlerBinding(
    metadataAnnotationName: String,
    metadataHandlerClassName: String,
    loadedHandler: LoadedExternalHandler
)

private[macroparadise] object MetadataHandlerBinding:
  final case class Failure(diagnostic: String)

  def validate(
      metadataAnnotationName: String,
      metadataHandlerClassName: String,
      loadedHandler: LoadedExternalHandler,
      requestedLoader: ClassLoader
  ): Either[Failure, MetadataHandlerBinding] =
    val descriptor = loadedHandler.descriptor
    if descriptor.annotationName == metadataAnnotationName then
      Right(
        MetadataHandlerBinding(
          metadataAnnotationName,
          metadataHandlerClassName,
          loadedHandler
        )
      )
    else
      Left(
        Failure(
          ExternalHandlerDiagnostics.render(
            ExternalHandlerDiagnostics.Stage.Loading,
            "METADATA_HANDLER_ANNOTATION_MISMATCH",
            "annotation" -> s"@$metadataAnnotationName",
            "metadataHandler" -> metadataHandlerClassName,
            "declaredAnnotation" -> s"@${descriptor.annotationName}",
            "loaderPolicy" -> "parent-first",
            "requestedLoader" -> ExternalHandlerDiagnostics.loaderIdentity(
              requestedLoader
            ),
            "detail" -> s"metadata for `@$metadataAnnotationName` selects `$metadataHandlerClassName`, but its captured descriptor declares `@${descriptor.annotationName}`"
          )
        )
      )

private[macroparadise] final class MetadataHandlerRunCache(
    explicitHandlers: List[LoadedExternalHandler]
):
  enum Origin:
    case Explicit, Discovered

  final case class Resolution(
      loadedHandler: Option[LoadedExternalHandler],
      origin: Origin
  )

  private val explicitByClassName =
    explicitHandlers.iterator
      .map(handler => handler.descriptor.handlerClassName -> handler)
      .toMap

  private val discoveredByClassName =
    mutable.Map.empty[String, Option[LoadedExternalHandler]]

  def resolve(
      handlerClassName: String
  )(load: => Option[LoadedExternalHandler]): Resolution =
    explicitByClassName.get(handlerClassName) match
      case Some(handler) =>
        Resolution(Some(handler), Origin.Explicit)
      case None =>
        Resolution(
          discoveredByClassName.getOrElseUpdate(
            handlerClassName,
            load
          ),
          Origin.Discovered
        )
