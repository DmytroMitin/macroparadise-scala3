package macroparadise.embedded

private[embedded] final case class EmbeddedSameModuleConfiguration(
    annotationName: String,
    producerSource: String
)

private[embedded] object EmbeddedSameModuleConfiguration:
  private val Prefix = "sameModuleEmbedded="

  def parse(options: List[String]): Either[String, Vector[EmbeddedSameModuleConfiguration]] =
    options.collect { case option if option.startsWith(Prefix) => option.stripPrefix(Prefix) } match
      case Nil => Right(Vector.empty)
      case values if values.size > 2 =>
        Left("the `sameModuleEmbedded=` option accepts at most two explicit producer bindings")
      case payload :: Nil =>
        parsePayload(payload).map(Vector(_))
      case payloads =>
        payloads.sorted
          .foldLeft[Either[String, Vector[EmbeddedSameModuleConfiguration]]](Right(Vector.empty)):
            case (acc, payload) =>
              for
                values <- acc
                parsed <- parsePairPayload(payload)
              yield values :+ parsed
          .map(_.sortBy(configuration => (configuration.annotationName, configuration.producerSource)))
          .flatMap(validatePair)

  private def parsePayload(payload: String): Either[String, EmbeddedSameModuleConfiguration] =
    payload.split(":", -1).toList.map(_.trim) match
      case annotationName :: producerSource :: Nil =>
        for
          _ <- validateCanonical(annotationName)
          _ <- validateNormalizedPath(producerSource)
        yield EmbeddedSameModuleConfiguration(annotationName, producerSource)
      case _ =>
        Left(
          "invalid `sameModuleEmbedded=` option; expected `<canonicalAnnotationName>:<producerSource>`"
        )

  private def parsePairPayload(payload: String): Either[String, EmbeddedSameModuleConfiguration] =
    val tokens = payload.split(":", -1).toList
    if tokens.exists(token => token != token.trim) then
      Left("invalid `sameModuleEmbedded=` option; relationship tokens must not contain surrounding whitespace")
    else parsePayload(payload)

  private def validatePair(
      configurations: Vector[EmbeddedSameModuleConfiguration]
  ): Either[String, Vector[EmbeddedSameModuleConfiguration]] =
    val duplicateAnnotations = duplicates(configurations.map(_.annotationName))
    val duplicateSources = duplicates(configurations.map(_.producerSource))
    if duplicateAnnotations.nonEmpty then
      Left(s"duplicate same-module annotation names: ${duplicateAnnotations.mkString(", ")}")
    else if duplicateSources.nonEmpty then
      Left(s"duplicate same-module producer source identities: ${duplicateSources.mkString(", ")}")
    else Right(configurations)

  private def duplicates(values: Vector[String]): Vector[String] =
    values.groupBy(identity).collect:
      case (value, entries) if entries.size > 1 => value
    .toVector.sorted

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
