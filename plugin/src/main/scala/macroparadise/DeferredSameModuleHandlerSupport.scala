package macroparadise

private[macroparadise] object DeferredSameModuleHandlerSupport:
  final case class SourceIdentity private (value: String)

  object SourceIdentity:
    def parse(raw: String): Either[String, SourceIdentity] =
      val normalized = normalizePath(raw)
      if normalized.isEmpty then
        Left("dependency source identity must not be empty")
      else if !normalized.contains('/') then
        Left(
          s"dependency source identity `$raw` must contain a normalized path segment; basenames alone are ambiguous"
        )
      else
        Right(SourceIdentity(normalized))

  final case class SourceDigest private (value: String)

  object SourceDigest:
    private val Sha256 = "sha256:[0-9a-f]{64}".r

    def parse(raw: String): Either[String, SourceDigest] =
      raw match
        case Sha256() => Right(SourceDigest(raw))
        case _ =>
          Left(
            "same-module source identity must be `sha256:` followed by exactly 64 lowercase hexadecimal digits"
          )

  final case class SameModuleConfiguration(
      annotationName: String,
      handlerClassName: String,
      markerSourceIdentity: SourceIdentity,
      handlerSourceIdentity: SourceIdentity,
      sourceDigest: SourceDigest
  )

  enum SameModuleMode:
    case NoSameModule
    case ExternalSingleton
    case ExternalPair
    case EmbeddedSingleton
    case EmbeddedPair

  final case class ParsedSameModuleConfigurations(
      mode: SameModuleMode,
      configurations: Vector[SameModuleConfiguration]
  )

  private val EmbeddedAdapterSuffix = "__MacroParadiseEmbeddedExpansionHandler"

  def parseConfiguration(
      options: List[String]
  ): Either[String, Option[SameModuleConfiguration]] =
    val externalRelationships =
      options.collect:
        case option if option.startsWith("sameModuleHandler=") =>
          option.stripPrefix("sameModuleHandler=")
    val embeddedRelationships =
      options.collect:
        case option if option.startsWith("sameModuleEmbedded=") =>
          option.stripPrefix("sameModuleEmbedded=")
    val identities =
      options.collect:
        case option if option.startsWith("sameModuleSourceIdentity=") =>
          option.stripPrefix("sameModuleSourceIdentity=")

    (externalRelationships, embeddedRelationships, identities) match
      case (Nil, Nil, Nil) => Right(None)
      case (_ :: _ :: _, _, _) =>
        Left("the `sameModuleHandler=` option accepts exactly one explicit relationship")
      case (_, _ :: _ :: _, _) =>
        Left("the `sameModuleEmbedded=` option accepts exactly one explicit relationship")
      case (_, _, _ :: _ :: _) =>
        Left("the `sameModuleSourceIdentity=` option accepts exactly one source digest")
      case (_ :: Nil, _ :: Nil, _) =>
        Left("`sameModuleHandler=` and `sameModuleEmbedded=` are mutually exclusive")
      case (Nil, Nil, _ :: Nil) =>
        Left("`sameModuleSourceIdentity=` requires one explicit same-module relationship")
      case (_ :: Nil, Nil, Nil) =>
        Left("`sameModuleHandler=` requires one distinct `sameModuleSourceIdentity=` compiler input")
      case (Nil, _ :: Nil, Nil) =>
        Left("`sameModuleEmbedded=` requires one distinct `sameModuleSourceIdentity=` compiler input")
      case (relationship :: Nil, Nil, identity :: Nil) =>
        parseExternalConfiguration(relationship, identity).map(Some(_))
      case (Nil, relationship :: Nil, identity :: Nil) =>
        parseEmbeddedConfiguration(relationship, identity).map(Some(_))

  def parseConfigurations(
      options: List[String]
  ): Either[String, Vector[SameModuleConfiguration]] =
    parseModeConfigurations(options).map(_.configurations)

  def parseModeConfigurations(
      options: List[String]
  ): Either[String, ParsedSameModuleConfigurations] =
    val externalRelationships =
      options.collect:
        case option if option.startsWith("sameModuleHandler=") =>
          option.stripPrefix("sameModuleHandler=")
    val embeddedRelationships =
      options.collect:
        case option if option.startsWith("sameModuleEmbedded=") =>
          option.stripPrefix("sameModuleEmbedded=")
    if externalRelationships.size <= 1 && embeddedRelationships.size <= 1 then
      parseConfiguration(options).map: configuration =>
        val values = configuration.toVector
        val mode =
          if externalRelationships.nonEmpty then SameModuleMode.ExternalSingleton
          else if embeddedRelationships.nonEmpty then SameModuleMode.EmbeddedSingleton
          else SameModuleMode.NoSameModule
        ParsedSameModuleConfigurations(mode, values)
    else if externalRelationships.nonEmpty && embeddedRelationships.nonEmpty then
      Left("`sameModuleHandler=` and `sameModuleEmbedded=` are mutually exclusive")
    else if externalRelationships.size > 2 then
      Left("the `sameModuleHandler=` option accepts at most two explicit relationships")
    else if embeddedRelationships.size > 2 then
      Left("the `sameModuleEmbedded=` option accepts at most two explicit relationships")
    else if externalRelationships.size == 2 then
      parseBoundedRelationships(
        externalRelationships,
        options,
        "sameModuleHandler",
        parseBoundedExternalConfiguration
      ).flatMap(validateBoundedConfigurations).map: configurations =>
        ParsedSameModuleConfigurations(SameModuleMode.ExternalPair, configurations)
    else if embeddedRelationships.size == 2 then
      parseBoundedRelationships(
        embeddedRelationships,
        options,
        "sameModuleEmbedded",
        parseBoundedEmbeddedConfiguration
      ).flatMap(validateBoundedEmbeddedConfigurations).map: configurations =>
        ParsedSameModuleConfigurations(SameModuleMode.EmbeddedPair, configurations)
    else
      Right(ParsedSameModuleConfigurations(SameModuleMode.NoSameModule, Vector.empty))

  private def parseBoundedRelationships(
      relationships: List[String],
      options: List[String],
      optionName: String,
      parse: (String, String) => Either[String, SameModuleConfiguration]
  ): Either[String, Vector[SameModuleConfiguration]] =
    val identities =
      options.collect:
        case option if option.startsWith("sameModuleSourceIdentity=") =>
          option.stripPrefix("sameModuleSourceIdentity=")
    if identities.isEmpty then
      Left(s"`$optionName=` requires one distinct `sameModuleSourceIdentity=` compiler input")
    else if identities.size > 1 then
      Left("the `sameModuleSourceIdentity=` option accepts exactly one source digest")
    else
      relationships.sorted
        .foldLeft[Either[String, Vector[SameModuleConfiguration]]](Right(Vector.empty)):
          case (acc, relationship) =>
            for
              configurations <- acc
              configuration <- parse(relationship, identities.head)
            yield configurations :+ configuration

  private def parseBoundedExternalConfiguration(
      relationship: String,
      identity: String
  ): Either[String, SameModuleConfiguration] =
    val tokens = relationship.split(":", -1).toList
    if tokens.exists(token => token != token.trim) then
      Left("invalid `sameModuleHandler=` option; relationship tokens must not contain surrounding whitespace")
    else
      parseExternalConfiguration(relationship, identity).flatMap: configuration =>
        SyntacticAnnotationIdentity.fromDeclaredName(configuration.annotationName) match
          case Right(canonical) if canonical.isQualified =>
            SyntacticAnnotationIdentity.fromDeclaredName(configuration.handlerClassName) match
              case Right(handler) if handler.isQualified => Right(configuration)
              case _ =>
                Left(
                  s"invalid handler class name in bounded `sameModuleHandler=` set: expected a qualified canonical class name: ${configuration.handlerClassName}"
                )
          case _ =>
            Left(
              s"invalid annotation name in bounded `sameModuleHandler=` set: expected a qualified canonical annotation class name: ${configuration.annotationName}"
            )

  private def parseBoundedEmbeddedConfiguration(
      relationship: String,
      identity: String
  ): Either[String, SameModuleConfiguration] =
    val tokens = relationship.split(":", -1).toList
    if tokens.exists(token => token != token.trim) then
      Left("invalid `sameModuleEmbedded=` option; relationship tokens must not contain surrounding whitespace")
    else parseEmbeddedConfiguration(relationship, identity)

  private def validateBoundedConfigurations(
      configurations: Vector[SameModuleConfiguration]
  ): Either[String, Vector[SameModuleConfiguration]] =
    val duplicateAnnotations = duplicates(configurations.map(_.annotationName))
    val duplicateHandlers = duplicates(configurations.map(_.handlerClassName))
    val duplicateSources = duplicates(
      configurations.flatMap(configuration =>
        Vector(configuration.markerSourceIdentity.value, configuration.handlerSourceIdentity.value)
      )
    )
    if duplicateAnnotations.nonEmpty then
      Left(s"duplicate same-module annotation names: ${duplicateAnnotations.mkString(", ")}")
    else if duplicateHandlers.nonEmpty then
      Left(s"duplicate same-module handler class names: ${duplicateHandlers.mkString(", ")}")
    else if duplicateSources.nonEmpty then
      Left(s"duplicate same-module producer source identities: ${duplicateSources.mkString(", ")}")
    else Right(configurations)

  private def validateBoundedEmbeddedConfigurations(
      configurations: Vector[SameModuleConfiguration]
  ): Either[String, Vector[SameModuleConfiguration]] =
    val sorted = configurations.sortBy(configuration => (
      configuration.annotationName,
      configuration.handlerSourceIdentity.value
    ))
    val duplicateAnnotations = duplicates(sorted.map(_.annotationName))
    val duplicateSources = duplicates(sorted.map(_.handlerSourceIdentity.value))
    if duplicateAnnotations.nonEmpty then
      Left(s"duplicate same-module annotation names: ${duplicateAnnotations.mkString(", ")}")
    else if duplicateSources.nonEmpty then
      Left(s"duplicate same-module producer source identities: ${duplicateSources.mkString(", ")}")
    else Right(sorted)

  private def duplicates(values: Seq[String]): Vector[String] =
    values.groupBy(identity).collect:
      case (value, entries) if entries.size > 1 => value
    .toVector.sorted

  private def parseExternalConfiguration(
      relationship: String,
      identity: String
  ): Either[String, SameModuleConfiguration] =
    relationship.split(":", -1).toList.map(_.trim) match
      case annotationName :: handlerClassName :: markerSource :: handlerSource :: Nil
          if annotationName.nonEmpty && handlerClassName.nonEmpty =>
        for
          markerIdentity <- SourceIdentity
            .parse(markerSource)
            .left.map(message => s"invalid marker source in `sameModuleHandler=`: $message")
          handlerIdentity <- SourceIdentity
            .parse(handlerSource)
            .left.map(message => s"invalid handler source in `sameModuleHandler=`: $message")
          _ <-
            if markerIdentity != handlerIdentity then Right(())
            else Left("same-module marker and handler sources must be different files")
          digest <- SourceDigest.parse(identity)
        yield SameModuleConfiguration(
          annotationName,
          handlerClassName,
          markerIdentity,
          handlerIdentity,
          digest
        )
      case _ =>
        Left(
          "invalid `sameModuleHandler=` option; expected `<annotationName>:<handlerClassName>:<markerSource>:<handlerSource>`"
        )

  private def parseEmbeddedConfiguration(
      relationship: String,
      identity: String
  ): Either[String, SameModuleConfiguration] =
    relationship.split(":", -1).toList.map(_.trim) match
      case annotationName :: producerSource :: Nil =>
        for
          canonical <- SyntacticAnnotationIdentity
            .fromDeclaredName(annotationName)
            .left.map(message => s"invalid annotation name in `sameModuleEmbedded=`: $message")
          _ <-
            if canonical.isQualified then Right(())
            else Left("invalid annotation name in `sameModuleEmbedded=`: expected a qualified canonical annotation class name")
          producerIdentity <- SourceIdentity
            .parse(producerSource)
            .left.map(message => s"invalid producer source in `sameModuleEmbedded=`: $message")
          digest <- SourceDigest.parse(identity)
        yield SameModuleConfiguration(
          canonical.value,
          canonical.value + EmbeddedAdapterSuffix,
          producerIdentity,
          producerIdentity,
          digest
        )
      case _ =>
        Left(
          "invalid `sameModuleEmbedded=` option; expected `<canonicalAnnotationName>:<producerSource>`"
        )


  enum DependencyResolution:
    case Missing
    case Unique(normalizedPath: String)
    case Ambiguous(normalizedPaths: List[String])

  enum RunKind:
    case Initial
    case Resumed

  enum LoadReason:
    case ResumedRun
    case IncrementalFallback

  enum DeferredHandlerAction:
    case SuspendForCurrentRunDependency(normalizedPath: String)
    case LoadCompiledHandler(reason: LoadReason)
    case RejectSameFile(normalizedPath: String)
    case RejectMarkerConsumerSameFile(normalizedPath: String)
    case RejectAmbiguousDependency(normalizedPaths: List[String])

  def normalizePath(raw: String): String =
    raw
      .replace('\\', '/')
      .split('/')
      .foldLeft(Vector.empty[String]):
        case (segments, "" | ".") => segments
        case (segments, "..") if segments.nonEmpty && segments.last != ".." =>
          segments.dropRight(1)
        case (segments, segment) => segments :+ segment
      .mkString("/")

  def resolveDependency(
      identity: SourceIdentity,
      currentSourcePaths: List[String]
  ): DependencyResolution =
    val matches =
      currentSourcePaths
        .map(normalizePath)
        .filter(pathMatchesIdentity(_, identity))

    matches match
      case Nil => DependencyResolution.Missing
      case path :: Nil => DependencyResolution.Unique(path)
      case paths => DependencyResolution.Ambiguous(paths.sorted)

  def decide(
      runKind: RunKind,
      normalizedConsumerPath: String,
      dependencyResolution: DependencyResolution
  ): DeferredHandlerAction =
    runKind match
      case RunKind.Resumed =>
        DeferredHandlerAction.LoadCompiledHandler(LoadReason.ResumedRun)
      case RunKind.Initial =>
        dependencyResolution match
          case DependencyResolution.Missing =>
            DeferredHandlerAction.LoadCompiledHandler(LoadReason.IncrementalFallback)
          case DependencyResolution.Unique(path)
              if path == normalizePath(normalizedConsumerPath) =>
            DeferredHandlerAction.RejectSameFile(path)
          case DependencyResolution.Unique(path) =>
            DeferredHandlerAction.SuspendForCurrentRunDependency(path)
          case DependencyResolution.Ambiguous(paths) =>
            DeferredHandlerAction.RejectAmbiguousDependency(paths)

  def decide(
      runKind: RunKind,
      normalizedConsumerPath: String,
      markerSourceIdentity: SourceIdentity,
      handlerResolution: DependencyResolution
  ): DeferredHandlerAction =
    if runKind == RunKind.Initial && pathMatchesIdentity(
        normalizePath(normalizedConsumerPath),
        markerSourceIdentity
      )
    then
      DeferredHandlerAction.RejectMarkerConsumerSameFile(
        normalizePath(normalizedConsumerPath)
      )
    else decide(runKind, normalizedConsumerPath, handlerResolution)

  private[macroparadise] def pathMatchesIdentity(
      normalizedSourcePath: String,
      identity: SourceIdentity
  ): Boolean =
    normalizedSourcePath == identity.value ||
      normalizedSourcePath.endsWith(s"/${identity.value}")
