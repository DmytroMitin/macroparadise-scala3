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

  def parseConfiguration(
      options: List[String]
  ): Either[String, Option[SameModuleConfiguration]] =
    val relationships =
      options.collect:
        case option if option.startsWith("sameModuleHandler=") =>
          option.stripPrefix("sameModuleHandler=")
    val identities =
      options.collect:
        case option if option.startsWith("sameModuleSourceIdentity=") =>
          option.stripPrefix("sameModuleSourceIdentity=")

    (relationships, identities) match
      case (Nil, Nil) => Right(None)
      case (_ :: _ :: _, _) =>
        Left("the `sameModuleHandler=` option accepts exactly one explicit relationship")
      case (_, _ :: _ :: _) =>
        Left("the `sameModuleSourceIdentity=` option accepts exactly one source digest")
      case (Nil, _ :: Nil) =>
        Left("`sameModuleSourceIdentity=` requires one explicit `sameModuleHandler=` relationship")
      case (_ :: Nil, Nil) =>
        Left("`sameModuleHandler=` requires one distinct `sameModuleSourceIdentity=` compiler input")
      case (relationship :: Nil, identity :: Nil) =>
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
            yield Some(
              SameModuleConfiguration(
                annotationName,
                handlerClassName,
                markerIdentity,
                handlerIdentity,
                digest
              )
            )
          case _ =>
            Left(
              "invalid `sameModuleHandler=` option; expected `<annotationName>:<handlerClassName>:<markerSource>:<handlerSource>`"
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

  private def pathMatchesIdentity(
      normalizedSourcePath: String,
      identity: SourceIdentity
  ): Boolean =
    normalizedSourcePath == identity.value ||
      normalizedSourcePath.endsWith(s"/${identity.value}")
