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

  private def pathMatchesIdentity(
      normalizedSourcePath: String,
      identity: SourceIdentity
  ): Boolean =
    normalizedSourcePath == identity.value ||
      normalizedSourcePath.endsWith(s"/${identity.value}")
