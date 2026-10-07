package macroparadise.embedded

private[embedded] final case class EmbeddedSameModuleConfiguration(
    annotationName: String,
    producerSource: String
)

private[embedded] object EmbeddedSameModuleConfiguration:
  private val Prefix = "sameModuleEmbedded="

  def parse(options: List[String]): Either[String, Option[EmbeddedSameModuleConfiguration]] =
    options.collect { case option if option.startsWith(Prefix) => option.stripPrefix(Prefix) } match
      case Nil => Right(None)
      case _ :: _ :: _ =>
        Left("the `sameModuleEmbedded=` option accepts exactly one explicit producer binding")
      case payload :: Nil =>
        payload.split(":", -1).toList.map(_.trim) match
          case annotationName :: producerSource :: Nil =>
            for
              _ <- validateCanonical(annotationName)
              _ <- validateNormalizedPath(producerSource)
            yield Some(EmbeddedSameModuleConfiguration(annotationName, producerSource))
          case _ =>
            Left(
              "invalid `sameModuleEmbedded=` option; expected `<canonicalAnnotationName>:<producerSource>`"
            )

  private def validateCanonical(value: String): Either[String, Unit] =
    val segments = value.split("\\.", -1).toList
    if segments.size >= 2 && segments.forall(validIdentifier) then Right(())
    else Left("same-module embedded annotation name must be a qualified canonical class name")

  private def validIdentifier(value: String): Boolean =
    value.nonEmpty &&
      Character.isJavaIdentifierStart(value.codePointAt(0)) &&
      value.codePoints().skip(1).allMatch(Character.isJavaIdentifierPart(_))

  private def validateNormalizedPath(value: String): Either[String, Unit] =
    val segments = value.split("/", -1).toList
    val valid =
      value.nonEmpty &&
        !value.startsWith("/") &&
        !value.contains('\\') &&
        !value.contains(':') &&
        segments.size >= 2 &&
        segments.forall(segment => segment.nonEmpty && segment != "." && segment != "..")
    if valid then Right(())
    else Left("same-module embedded producer source must be a normalized relative path with a directory segment")
