package macroparadise

import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.ast.Trees.*
import dotty.tools.dotc.ast.Trees
import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags.{Enum, Param, Trait}
import dotty.tools.dotc.core.Names.*
import dotty.tools.dotc.plugins.PluginPhase
import dotty.tools.dotc.report
import dotty.tools.dotc.util.SrcPos
import paradise3.api.{
  AnnotatedClassView,
  ExpansionCompositionPolicy as ExternalExpansionCompositionPolicy,
  ExpansionInput as ExternalExpansionInput,
  ExpansionOutcome as ExternalExpansionOutcome,
  ExpansionTargetProfile as ExternalExpansionTargetProfile,
  ParadiseAnnotationExpander as ExternalParadiseAnnotationExpander
}

import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.{Files, InvalidPathException, Path, StandardOpenOption}
import scala.util.control.NonFatal

private final class ExternalHandlerInvocationTrace private (path: Option[Path]):
  def record(handlerClass: String, annotationName: String, className: String): Unit =
    path.foreach: tracePath =>
      try
        Files.writeString(
          tracePath,
          s"handler=$handlerClass annotation=$annotationName class=$className\n",
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND
        )
      catch
        case NonFatal(_) => ()

private object ExternalHandlerInvocationTrace:
  def fromPath(path: Option[Path]): ExternalHandlerInvocationTrace =
    ExternalHandlerInvocationTrace(path)

private object ExternalHandlerLoading:
  final case class MetadataDiscoveryResult(
      handlers: List[LoadedExternalHandler],
      legacySimpleRequests: Set[ExplicitImportAnnotationIdentityRequest]
  )

  private val BuiltInAnnotationNames = Set("gen", "debug")

  final case class DeferredSameModuleHandler(
      annotationName: String,
      handlerClassName: String,
      markerSourceIdentity: DeferredSameModuleHandlerSupport.SourceIdentity,
      handlerSourceIdentity: DeferredSameModuleHandlerSupport.SourceIdentity,
      sourceDigest: DeferredSameModuleHandlerSupport.SourceDigest
  )

  final case class LoadedHandlers(
      explicit: List[LoadedExternalHandler],
      handlerClasspath: List[String],
      handlerLoader: ClassLoader,
      metadataReader: AnnotationMetadataReader,
      metadataHandlerRunCache: MetadataHandlerRunCache,
      deferredSameModule: Option[DeferredSameModuleHandler],
      invocationTrace: ExternalHandlerInvocationTrace,
      ownedLoaders: List[URLClassLoader]
  ) extends AutoCloseable:
    override def close(): Unit =
      RunLocalResourceScope.closeAll(
        ownedLoaders
        .foldLeft(List.empty[URLClassLoader]): (distinct, loader) =>
          if distinct.exists(_ eq loader) then distinct else loader :: distinct
      )

  enum DeferredLoadResult:
    case Available(handler: LoadedExternalHandler)
    case Unavailable
    case Invalid

  def load(options: List[String])(using Context): LoadedHandlers =
    val invocationTrace =
      ExternalHandlerInvocationTrace.fromPath(
        TraceFileOption.parse(options, "externalHandlerInvocationTrace")
      )
    val metadataTrace =
      MetadataReaderTrace.fromPath(
        TraceFileOption.parse(options, "metadataReaderTrace")
      )
    val rawHandlerClassNames =
      options.collect:
        case option if option.startsWith("handler=") =>
          option.stripPrefix("handler=")

    rawHandlerClassNames.filter(_.trim.isEmpty).foreach: _ =>
      report.error("empty external annotation handler option `handler=`")

    val handlerClassNames =
      rawHandlerClassNames.map(_.trim).filter(_.nonEmpty)

    val handlerClasspath =
      options.collect:
        case option if option.startsWith("handlerClasspath=") =>
          option.stripPrefix("handlerClasspath=")
      .flatMap(_.split(File.pathSeparator).toList)
      .map(_.trim)
      .filter(_.nonEmpty)

    val pluginLoader = classOf[MacroParadisePlugin].getClassLoader
    val loader = handlerLoader(handlerClasspath)
    val metadataLoader = compilerClasspathMetadataLoader(pluginLoader)
    val ownedLoaders =
      List(loader, metadataLoader).collect:
        case urlLoader: URLClassLoader if urlLoader ne pluginLoader => urlLoader

    StructuredMetadataDistributionContract.parseAndValidate(options) match
      case Left(message) =>
        invalidStructuredMetadataConfiguration(
          message,
          pluginLoader,
          invocationTrace,
          ownedLoaders
        )
      case Right(structuredMetadataPaths) =>
        val metadataReader =
          structuredMetadataPaths match
            case None =>
              Right(
                AnnotationMetadataReader.production(
                  metadataLoader,
                  Nil,
                  metadataTrace
                )
              )
            case Some(paths) =>
              StructuredMetadataDistributionContract.auditRuntime(
                pluginLoader,
                loader
              ) match
                case StructuredMetadataDistributionContract.InspectorAudit.Ready(
                      evidence
                    ) =>
                  report.echo(
                    s"[structured-metadata] validated experimental distribution: inputs=${paths.map(_.value).mkString(",")} ${evidence.head}"
                  )
                  Right(
                    AnnotationMetadataReader.production(
                      metadataLoader,
                      paths,
                      metadataTrace
                    )
                  )
                case StructuredMetadataDistributionContract.InspectorAudit.Unavailable(
                      message
                    ) =>
                  report.warning(
                    s"experimental structured metadata reader unavailable; controlled string compatibility fallback remains enabled: $message"
                  )
                  Right(
                    AnnotationMetadataReader.production(
                      metadataLoader,
                      paths,
                      metadataTrace
                    )
                  )
                case StructuredMetadataDistributionContract.InspectorAudit.Invalid(
                      message
                    ) =>
                  Left(message)

        metadataReader match
          case Left(message) =>
            invalidStructuredMetadataConfiguration(
              message,
              pluginLoader,
              invocationTrace,
              ownedLoaders
            )
          case Right(reader) =>
            val deferredSameModule = parseDeferredSameModuleHandler(options)
            val explicitHandlers =
              validateUniqueHandlers(
                handlerClassNames.flatMap(loadHandler(_, loader, handlerClasspath))
              )
            LoadedHandlers(
              explicit = explicitHandlers,
              handlerClasspath = handlerClasspath,
              handlerLoader = loader,
              metadataReader = reader,
              metadataHandlerRunCache = MetadataHandlerRunCache(
                explicitHandlers
              ),
              deferredSameModule = deferredSameModule,
              invocationTrace = invocationTrace,
              ownedLoaders = ownedLoaders
            )

  private def invalidStructuredMetadataConfiguration(
      message: String,
      pluginLoader: ClassLoader,
      invocationTrace: ExternalHandlerInvocationTrace,
      ownedLoaders: List[URLClassLoader]
  )(using Context): LoadedHandlers =
    val diagnostic =
      s"invalid experimental structured metadata configuration: $message"
    report.error(diagnostic)
    LoadedHandlers(
      explicit = Nil,
      handlerClasspath = Nil,
      handlerLoader = pluginLoader,
      metadataReader =
        UnavailableStructuredAnnotationMetadataReader(diagnostic),
      metadataHandlerRunCache = MetadataHandlerRunCache(Nil),
      deferredSameModule = None,
      invocationTrace = invocationTrace,
      ownedLoaders = ownedLoaders
    )

  private object TraceFileOption:
    def parse(options: List[String], optionName: String)(using Context): Option[Path] =
      val prefix = s"$optionName="
      val rawValues =
        options.collect:
          case option if option.startsWith(prefix) => option.stripPrefix(prefix)

      if rawValues.size > 1 then
        report.error(s"the test/evidence `$optionName=` option accepts exactly one path")
        None
      else
        rawValues.headOption match
          case None => None
          case Some(rawValue) if rawValue.trim.isEmpty =>
            report.error(s"empty test/evidence trace option `$optionName=`")
            None
          case Some(rawValue) =>
            try Some(Path.of(rawValue.trim))
            catch
              case error: InvalidPathException =>
                report.error(
                  s"invalid test/evidence trace option `$optionName=`: ${error.getMessage}"
                )
                None

  private def parseDeferredSameModuleHandler(
      options: List[String]
  )(using Context): Option[DeferredSameModuleHandler] =
    DeferredSameModuleHandlerSupport.parseConfiguration(options) match
      case Left(message) =>
        report.error(s"invalid experimental same-module configuration: $message")
        None
      case Right(None) => None
      case Right(Some(configuration)) =>
        Some(
          DeferredSameModuleHandler(
            configuration.annotationName,
            configuration.handlerClassName,
            configuration.markerSourceIdentity,
            configuration.handlerSourceIdentity,
            configuration.sourceDigest
          )
        )

  def discoverMetadataHandlers(
      annotationRequests: Set[ExplicitImportAnnotationIdentityRequest],
      loaded: LoadedHandlers
  )(using Context): MetadataDiscoveryResult =
    val classCache = loaded.metadataHandlerRunCache
    val emittedDiscoveredClassNames = scala.collection.mutable.Set.empty[String]
    val legacySimpleRequests = scala.collection.mutable.Set.empty[ExplicitImportAnnotationIdentityRequest]

    val handlers =
      annotationRequests.toList
        .sortBy(request => (request.annotationName, request.importedShortName.getOrElse("")))
        .filterNot(request => BuiltInAnnotationNames.contains(request.annotationName))
        .flatMap: request =>
          val lookupAnnotationName = request.annotationName
          val hasCanonicalExplicit =
            loaded.explicit.exists(_.descriptor.annotationName == request.annotationName)
          val hasLegacySimpleExplicit =
            !hasCanonicalExplicit && request.importedShortName.exists: shortName =>
              loaded.explicit.exists(_.descriptor.annotationName == shortName)
          loaded.metadataReader.findExpanderClass(lookupAnnotationName) match
            case MetadataLookupResult.Found(className) =>
              val resolution =
                classCache.resolve(className)(
                  loadHandler(className, loaded.handlerLoader, loaded.handlerClasspath)
                )
              resolution.loadedHandler match
                case Some(handler) =>
                  val bindingAnnotationName =
                    request.importedShortName match
                      case Some(shortName)
                          if !hasCanonicalExplicit &&
                            !handler.descriptor.annotationName.contains('.') =>
                        legacySimpleRequests += request
                        shortName
                      case _ => lookupAnnotationName
                  MetadataHandlerBinding.validate(
                    bindingAnnotationName,
                    className,
                    handler,
                    loaded.handlerLoader
                  ) match
                    case Left(failure) =>
                      report.error(failure.diagnostic)
                      List(invalidMetadataHandler(bindingAnnotationName))
                    case Right(binding) =>
                      resolution.origin match
                        case classCache.Origin.Explicit =>
                          Nil
                        case classCache.Origin.Discovered
                            if emittedDiscoveredClassNames.add(className) =>
                          List(binding.loadedHandler)
                        case classCache.Origin.Discovered =>
                          Nil
                case None =>
                  List(invalidMetadataHandler(lookupAnnotationName))
            case MetadataLookupResult.Failed(message) =>
              report.error(
                ExternalHandlerDiagnostics.render(
                  ExternalHandlerDiagnostics.Stage.Discovery,
                  "METADATA_DISCOVERY_FAILURE",
                  "annotation" -> s"@$lookupAnnotationName",
                  "detail" -> message
                )
              )
              List(invalidMetadataHandler(lookupAnnotationName))
            case MetadataLookupResult.NotFound =>
              if hasLegacySimpleExplicit then
                legacySimpleRequests += request
              Nil

    MetadataDiscoveryResult(handlers, legacySimpleRequests.toSet)

  def validateUniqueHandlers(
      handlers: List[LoadedExternalHandler]
  )(using Context): List[LoadedExternalHandler] =
    val seenExternal = scala.collection.mutable.Set.empty[String]
    val uniqueHandlers = List.newBuilder[LoadedExternalHandler]

    handlers.foreach: handler =>
      val annotationName = handler.descriptor.annotationName
      if BuiltInAnnotationNames.contains(annotationName) then
        report.error(
          ExternalHandlerDiagnostics.render(
            ExternalHandlerDiagnostics.Stage.Loading,
            "DUPLICATE_HANDLER_REGISTRATION",
            "annotation" -> s"@$annotationName",
            "handler" -> handler.descriptor.handlerClassName,
            "conflict" -> "built-in handler",
            "detail" -> s"duplicate annotation handler registration for `$annotationName`"
          )
        )
      else if seenExternal.contains(annotationName) then
        report.error(
          ExternalHandlerDiagnostics.render(
            ExternalHandlerDiagnostics.Stage.Loading,
            "DUPLICATE_HANDLER_REGISTRATION",
            "annotation" -> s"@$annotationName",
            "handler" -> handler.descriptor.handlerClassName,
            "conflict" -> "another external handler",
            "detail" -> s"duplicate annotation handler registration for `$annotationName`"
          )
        )
      else
        seenExternal += annotationName
        uniqueHandlers += handler

    uniqueHandlers.result()

  private final class InvalidMetadataAnnotationExpander(val annotationName: String) extends ExternalParadiseAnnotationExpander:
    def expand(input: ExternalExpansionInput)(using Context): ExternalExpansionOutcome =
      ExternalExpansionOutcome.Rejected(Nil, input.annotatedClass)

  private def invalidMetadataHandler(annotationName: String): LoadedExternalHandler =
    val instance = InvalidMetadataAnnotationExpander(annotationName)
    LoadedExternalHandler(
      instance,
      ExternalHandlerDescriptor(
        handlerClassName = instance.getClass.getName,
        annotationName = annotationName,
        targetProfile = ExternalExpansionTargetProfile.CommonClassOnly,
        compositionPolicy = ExternalExpansionCompositionPolicy.StandaloneOnly,
        consumesExistingCompanion = false
      ),
      metadataFailureAlreadyReported = true
    )

  private def handlerLoader(classpath: List[String]): ClassLoader =
    // ASSUMPTION
    // Explicit handler classpath entries are loaded with the plugin/API loader as
    // parent, so the handler and plugin share `paradise3.api` class identity.
    if classpath.isEmpty then classOf[MacroParadisePlugin].getClassLoader
    else
      val urls = classpath.map(path => new File(path).toURI.toURL).toArray
      URLClassLoader(urls, classOf[MacroParadisePlugin].getClassLoader)

  private def compilerClasspathMetadataLoader(
      pluginLoader: ClassLoader
  )(using context: Context): ClassLoader =
    // ASSUMPTION
    // Runtime-visible marker metadata is an ordinary compile-classpath input,
    // while handler implementation loading remains restricted to the explicit
    // handlerClasspath. Keeping separate loaders makes a discovered marker
    // unable to smuggle its handler into the invocation loader.
    val paths =
      context.settings.classpath.value
        .split(File.pathSeparator)
        .toList
        .map(_.trim)
        .filter(_.nonEmpty)
        .distinct
    if paths.isEmpty then pluginLoader
    else URLClassLoader(paths.map(path => File(path).toURI.toURL).toArray, pluginLoader)

  def deferredHandlerLoader(handlerClasspath: List[String]): ClassLoader =
    handlerLoader(handlerClasspath)

  def loadDeferred(
      deferred: DeferredSameModuleHandler,
      loader: ClassLoader
  )(using Context): DeferredLoadResult =
    try
      val instance = loader.loadClass(deferred.handlerClassName).getConstructor().newInstance()
      instance match
        case expander: ExternalParadiseAnnotationExpander =>
          captureDescriptor(expander, loader) match
            case Some(loaded) if loaded.descriptor.annotationName == deferred.annotationName =>
              DeferredLoadResult.Available(loaded)
            case Some(loaded) =>
              report.error(
                s"experimental same-module handler `${deferred.handlerClassName}` claims `${loaded.descriptor.annotationName}` instead of configured annotation `${deferred.annotationName}`"
              )
              DeferredLoadResult.Invalid
            case None =>
              DeferredLoadResult.Invalid
        case other =>
          report.error(
            s"experimental same-module handler `${deferred.handlerClassName}` does not implement paradise3.api.ParadiseAnnotationExpander; got `${other.getClass.getName}`"
          )
          DeferredLoadResult.Invalid
    catch
      case _: ClassNotFoundException =>
        DeferredLoadResult.Unavailable
      case error: InvocationTargetException =>
        val cause = Option(error.getCause).getOrElse(error)
        val message = Option(cause.getMessage).getOrElse(cause.getClass.getName)
        report.error(
          s"experimental same-module handler `${deferred.handlerClassName}` failed to instantiate or initialize: $message"
        )
        DeferredLoadResult.Invalid
      case NonFatal(error) =>
        report.error(
          s"could not load experimental same-module handler `${deferred.handlerClassName}`: ${error.getMessage}"
        )
        DeferredLoadResult.Invalid

  def closeDeferredLoader(loader: ClassLoader): Unit =
    loader match
      case urlLoader: URLClassLoader => urlLoader.close()
      case _ =>

  def loaderIdentity(loader: ClassLoader): String =
    s"${loader.getClass.getName}@${System.identityHashCode(loader).toHexString}"

  private def loadHandler(
      className: String,
      loader: ClassLoader,
      handlerClasspath: List[String]
  )(using Context): Option[LoadedExternalHandler] =
    try
      val handlerClass = loader.loadClass(className)
      val instance = handlerClass.getConstructor().newInstance()
      instance match
        case expander: ExternalParadiseAnnotationExpander =>
          captureDescriptor(expander, loader)
        case other =>
          report.error(ExternalHandlerDiagnostics.typeMismatch(className, other.getClass, loader))
          None
    catch
      case error: InvocationTargetException =>
        val cause = Option(error.getCause).getOrElse(error)
        report.error(
          ExternalHandlerDiagnostics.render(
            ExternalHandlerDiagnostics.Stage.Loading,
            "CONSTRUCTOR_FAILURE",
            "handler" -> className,
            "loaderPolicy" -> "parent-first",
            "requestedLoader" -> ExternalHandlerDiagnostics.loaderIdentity(loader),
            "cause" -> cause.getClass.getName,
            "message" -> ExternalHandlerDiagnostics.normalize(cause.getMessage),
            "detail" -> s"external annotation handler `$className` failed to instantiate or initialize"
          )
        )
        None
      case error: LinkageError =>
        report.error(
          ExternalHandlerDiagnostics.render(
            ExternalHandlerDiagnostics.Stage.Loading,
            "LINKAGE_ERROR",
            "handler" -> className,
            "loaderPolicy" -> "parent-first",
            "requestedLoader" -> ExternalHandlerDiagnostics.loaderIdentity(loader),
            "cause" -> error.getClass.getName,
            "message" -> ExternalHandlerDiagnostics.normalize(error.getMessage),
            "detail" -> s"could not load external annotation handler `$className`"
          )
        )
        None
      case NonFatal(error) =>
        report.error(
          ExternalHandlerDiagnostics.handlerLoadFailure(
            className,
            error,
            handlerClasspath
          )
        )
        None

  private def captureDescriptor(
      expander: ExternalParadiseAnnotationExpander,
      loader: ClassLoader
  )(using Context): Option[LoadedExternalHandler] =
    ExternalHandlerDescriptor.capture(expander, loader) match
      case Right(loaded) => Some(loaded)
      case Left(failure) =>
        report.error(failure.diagnostic)
        None

final case class ParadiseGenPhase(options: List[String]) extends PluginPhase:
  import DeferredSameModuleHandlerSupport.*

  private var activeExternalHandlers: Option[ExternalHandlerLoading.LoadedHandlers] = None
  private lazy val privateObjectParticipant =
    PrivateObjectPrimaryTransform.fromOptions(options)

  override val phaseName = "paradiseGen"
  override val description =
    "expands narrow top-level built-in annotations before typer"

  override def runsAfter = Set("parser")
  override def runsBefore = Set("typer")

  override def runOn(units: List[CompilationUnit])(using ctx: Context): List[CompilationUnit] =
    RunLocalResourceScope.use(ExternalHandlerLoading.load(options)): loaded =>
      require(
        activeExternalHandlers.isEmpty,
        "macroparadise phase run-local handlers are already active"
      )
      activeExternalHandlers = Some(loaded)
      try super.runOn(units)
      finally activeExternalHandlers = None

  private def externalHandlers: ExternalHandlerLoading.LoadedHandlers =
    activeExternalHandlers.getOrElse:
      throw IllegalStateException(
        "macroparadise handler state is unavailable outside PluginPhase.runOn"
      )

  override def run(using ctx: Context): Unit =
    val unit = ctx.compilationUnit
    externalHandlers.deferredSameModule match
      case Some(deferred)
          if ParadiseTreeRewrite.containsTopLevelClassAnnotation(
            unit.untpdTree,
            deferred.annotationName
          ) =>
        handleDeferredConsumer(unit, deferred)
      case _ =>
        unit.untpdTree = ParadiseTreeRewrite.rewriteUnit(
          unit,
          externalHandlers,
          privateObjectParticipant
        )

  private def handleDeferredConsumer(
      unit: CompilationUnit,
      deferred: ExternalHandlerLoading.DeferredSameModuleHandler
  )(using ctx: Context): Unit =
    val runKind = if ctx.run.isCompilingSuspended then RunKind.Resumed else RunKind.Initial
    val consumerPath = normalizePath(unit.source.file.path)
    val dependencyResolution =
      if runKind == RunKind.Resumed then DependencyResolution.Missing
      else
        // ASSUMPTION
        // Reading current run source paths does not mutate or process any other unit.
        // `ParadiseGenPhase.run` itself remains strictly per-unit.
        resolveDependency(
          deferred.handlerSourceIdentity,
          ctx.run.units.map(_.source.file.path)
        )

    decide(
      runKind,
      consumerPath,
      deferred.markerSourceIdentity,
      dependencyResolution
    ) match
      case DeferredHandlerAction.SuspendForCurrentRunDependency(dependencyPath) =>
        report.echo(
          s"[same-module-handler] run=initial unit=${unit.source.file.name} dependency=$dependencyPath action=suspend-before-load-and-mutation"
        )
        // ASSUMPTION
        // The normalized dependency identifies a different current-run unit that
        // can finish independently while this consumer is suspended.
        //
        // NEEDS VERIFICATION
        // MAY DEPEND ON SCALA VERSION
        // `CompilationUnit.suspend` is a compiler-internal API. On the pinned
        // compiler build, `Phase.runOn` catches the exception and a fresh `Run` reparses
        // and recompiles the suspended unit.
        CompilationUnitSuspension.suspend(
          unit,
          s"waiting for same-module handler ${deferred.handlerClassName} from ${deferred.handlerSourceIdentity.value}"
        )
      case DeferredHandlerAction.LoadCompiledHandler(reason) =>
        loadAndRewriteDeferredConsumer(unit, deferred, reason)
      case DeferredHandlerAction.RejectSameFile(path) =>
        report.error(
          s"experimental same-module handler `${deferred.handlerClassName}` cannot be defined and used in the same source file `$path`; only explicit different-file Model A is implemented",
          unit.untpdTree.sourcePos
        )
      case DeferredHandlerAction.RejectMarkerConsumerSameFile(path) =>
        report.error(
          s"experimental same-module marker `${deferred.annotationName}` and its consumer cannot share source file `$path`; only separate marker, handler, and consumer files are implemented",
          unit.untpdTree.sourcePos
        )
      case DeferredHandlerAction.RejectAmbiguousDependency(paths) =>
        report.error(
          s"experimental same-module handler source `${deferred.handlerSourceIdentity.value}` is ambiguous in the current run; matched: ${paths.mkString(", ")}",
          unit.untpdTree.sourcePos
        )

  private def loadAndRewriteDeferredConsumer(
      unit: CompilationUnit,
      deferred: ExternalHandlerLoading.DeferredSameModuleHandler,
      reason: LoadReason
  )(using ctx: Context): Unit =
    val loader =
      ExternalHandlerLoading.deferredHandlerLoader(
        externalHandlers.handlerClasspath
      )
    try
      ExternalHandlerLoading.loadDeferred(deferred, loader) match
        case ExternalHandlerLoading.DeferredLoadResult.Available(handler) =>
          logDeferredAttempt(unit, deferred, loader, handler, reason, "available")
          val loadedForUnit =
            externalHandlers.copy(
              explicit = ExternalHandlerLoading.validateUniqueHandlers(
                externalHandlers.explicit :+ handler
              )
            )
          unit.untpdTree = ParadiseTreeRewrite.rewriteUnit(
            unit,
            loadedForUnit,
            privateObjectParticipant
          )
        case ExternalHandlerLoading.DeferredLoadResult.Unavailable =>
          logDeferredAttempt(unit, deferred, loader, null, reason, "unavailable")
          reason match
            case LoadReason.ResumedRun =>
              report.error(
                s"experimental same-module handler `${deferred.handlerClassName}` is unavailable after current-run dependency `${deferred.handlerSourceIdentity.value}` completed",
                unit.untpdTree.sourcePos
              )
            case LoadReason.IncrementalFallback =>
              report.error(
                s"experimental same-module handler source `${deferred.handlerSourceIdentity.value}` was not found in the current run and handler `${deferred.handlerClassName}` is unavailable from current compilation outputs",
                unit.untpdTree.sourcePos
              )
        case ExternalHandlerLoading.DeferredLoadResult.Invalid =>
          logDeferredAttempt(unit, deferred, loader, null, reason, "invalid")
    finally
      ExternalHandlerLoading.closeDeferredLoader(loader)
      report.echo(
        s"[same-module-handler] run=${runLabel(reason)} unit=${unit.source.file.name} handlerLoader=${ExternalHandlerLoading.loaderIdentity(loader)} loaderClosed=true"
      )

  private def logDeferredAttempt(
      unit: CompilationUnit,
      deferred: ExternalHandlerLoading.DeferredSameModuleHandler,
      handlerLoader: ClassLoader,
      handler: LoadedExternalHandler | Null,
      reason: LoadReason,
      result: String
  )(using ctx: Context): Unit =
    val pluginLoader = classOf[MacroParadisePlugin].getClassLoader
    val apiLoader = classOf[ExternalParadiseAnnotationExpander].getClassLoader
    val handlerApiLoader =
      if handler == null then "not-loaded"
      else
        val apiClassFromHandlerLoader =
          handler.instance.getClass.getClassLoader.loadClass(
            classOf[ExternalParadiseAnnotationExpander].getName
          )
        ExternalHandlerLoading.loaderIdentity(apiClassFromHandlerLoader.getClassLoader)
    report.echo(
      s"[same-module-handler] run=${runLabel(reason)} unit=${unit.source.file.name} unitId=${System.identityHashCode(unit).toHexString} treeId=${System.identityHashCode(unit.untpdTree).toHexString} handler=${deferred.handlerClassName} result=$result pluginLoader=${ExternalHandlerLoading.loaderIdentity(pluginLoader)} apiLoader=${ExternalHandlerLoading.loaderIdentity(apiLoader)} handlerLoader=${ExternalHandlerLoading.loaderIdentity(handlerLoader)} handlerApiLoader=$handlerApiLoader"
    )

  private def runLabel(reason: LoadReason): String =
    reason match
      case LoadReason.ResumedRun => "resumed"
      case LoadReason.IncrementalFallback => "initial-incremental-fallback"

private[macroparadise] object DiagnosticPositionPolicy:
  def mostSpecific(currentAnnotation: Option[untpd.Tree], fallback: => SrcPos)(using Context): SrcPos =
    currentAnnotation
      .flatMap(Option(_))
      .map(_.sourcePos)
      .filter(_.span.exists)
      .getOrElse(fallback)

private object ParadiseTreeRewrite:
  import untpd.*
  import RoleAwareTransactionKernel.*

  private final case class TopLevelRewriteContext(names: Set[String])

  private final case class MatchingAnnotation(annotation: Tree, expander: AnnotationExpander)

  private type TargetAdmissionProfile = LegacyShapeProfile
  private val TargetAdmissionProfile = LegacyShapeProfile

  private enum CompositionPolicy:
    case StandaloneOnly, SourceOrdered

  def containsTopLevelClassAnnotation(
      tree: Tree,
      annotationName: String
  )(using Context): Boolean =
    tree match
      case pkg: PackageDef =>
        pkg.stats.exists:
          case typeDef: TypeDef =>
            Trees.mods(typeDef).annotations.exists(
              HandledAnnotations.annotationName(_).contains(annotationName)
            )
          case _ => false
      case _ => false

  private trait AnnotationExpander:
    def annotationName: String
    def canonicalHandlerIdentity: String =
      s"macroparadise.builtin.$annotationName"
    def consumesExistingCompanion: Boolean = false
    def targetAdmissionProfile: TargetAdmissionProfile =
      TargetAdmissionProfile.CommonClassOnly
    def compositionPolicy: CompositionPolicy =
      CompositionPolicy.StandaloneOnly
    def validationOrigin: String = s"built-in handler @${annotationName}"
    def outputValidationDiagnostic(category: String, detail: String): String =
      detail
    def admissionRejection(
        view: AnnotatedClassView
    )(using Context): Option[AnnotatedClassAdmission.Rejection] = None
    def expand(input: ExpansionInput)(using Context): ExpansionResult

  private final case class ExpansionInput(
      annotatedClass: TypeDef,
      existingCompanion: Option[ModuleDef],
      topLevel: TopLevelRewriteContext,
      currentAnnotation: Option[Tree]
  )

  private enum ExpansionResult:
    case Expanded(trees: List[Tree])
    case Structured(output: paradise3.api.StructuredExpansionOutput)
    case Rejected(diagnostics: List[ExpansionDiagnostic], fallback: TypeDef)

  private enum ValidatedExpansionResult:
    case Expanded(
        trees: List[Tree],
        furtherExpansionDrafts: List[InternalFurtherExpansionRequests.Draft]
    )
    case Rejected(diagnostics: List[ExpansionDiagnostic], fallback: TypeDef)

  private final case class ExpansionDiagnostic(message: String, pos: SrcPos)

  // The coordinator owns these output lanes while annotations are applied in
  // source order. Raw annotations stay on the transaction's current primary;
  // after each step the
  // shared matching authority filters the complete currently handled sequence
  // and proves it equals the expected original remainder. Unhandled annotations
  // remain outside that closure and continue to typer/later phases unchanged.
  private final case class CompositionState(
      transaction: LegacyTransaction,
      generatedQueue: List[FurtherExpansionRequest],
      seenGeneratedStates: List[FurtherExpansionEffectiveState],
      generatedSteps: Int,
      recentTrace: Vector[FurtherExpansionTraceEntry],
      nextTraceStep: Int
  ):
    def currentClass: TypeDef = transaction.currentPrimary
    def currentCompanion: Option[ModuleDef] = transaction.currentCompanion
    def additionalTopLevelTrees: List[Tree] =
      transaction.additionalTopLevelTrees

    def outputTrees: List[Tree] =
      transaction.outputTrees

    def appendTrace(entry: FurtherExpansionTraceEntry): CompositionState =
      copy(
        recentTrace = (recentTrace :+ entry).takeRight(FurtherExpansionTraceLimit),
        nextTraceStep = nextTraceStep + 1
      )

  private final case class FurtherExpansionRequest(
      annotationName: String,
      canonicalHandlerIdentity: String,
      rawApplication: Tree,
      requestedBy: String
  ):
    lazy val syntax: PositionIndependentStructure =
      PositionIndependentStructure.tree(rawApplication)

  private final case class FurtherExpansionEffectiveState(
      lineage: TransactionTargetId,
      canonicalHandlerIdentity: String,
      requestSyntax: PositionIndependentStructure,
      targetState: PositionIndependentStructure
  )

  private final case class FurtherExpansionTraceEntry(
      step: Int,
      provenance: String,
      annotationName: String,
      canonicalHandlerIdentity: String,
      targetName: String,
      requestedBy: Option[String]
  ):
    def render: String =
      requestedBy match
        case Some(generator) =>
          s"step $step: $provenance @$annotationName by $generator on $targetName"
        case None =>
          s"step $step: $provenance @$annotationName handler=$canonicalHandlerIdentity on $targetName"

  private val FurtherExpansionStepBudget = 32
  private val FurtherExpansionTraceLimit = 8

  private def builtInExpanders: List[AnnotationExpander] =
    // ASSUMPTION
    // This is a tiny internal built-in expander list, not a public registry or
    // configurable handler-loading mechanism.
    List(GenAnnotationExpander, DebugAnnotationExpander)

  private def expanders(externalExpanders: List[AnnotationExpander]): List[AnnotationExpander] =
    builtInExpanders ++ externalExpanders

  private object GenAnnotationExpander extends AnnotationExpander:
    val annotationName: String = "gen"
    override val compositionPolicy: CompositionPolicy =
      CompositionPolicy.SourceOrdered
    override val consumesExistingCompanion: Boolean = true
    override def admissionRejection(
        view: AnnotatedClassView
    )(using Context): Option[AnnotatedClassAdmission.Rejection] =
      AnnotatedClassAdmission.genRejection(view)

    def expand(input: ExpansionInput)(using Context): ExpansionResult =
      validate(input) match
        case diagnostic :: rest =>
          ExpansionResult.Rejected(diagnostic :: rest, input.annotatedClass)
        case Nil =>
          ExpansionResult.Expanded(expandAnnotatedClass(input.annotatedClass, input.existingCompanion, input.currentAnnotation))

    private def validate(input: ExpansionInput)(using Context): List[ExpansionDiagnostic] =
      val siblingName = siblingClassName(input.annotatedClass.name).toString
      if input.topLevel.names.contains(siblingName) then
        List(
          ExpansionDiagnostic(
            s"generated sibling `$siblingName` already exists; @gen cannot generate sibling for `${input.annotatedClass.name}` because this case is currently unsupported",
            input.annotatedClass.sourcePos
          )
        )
      else Nil

    private def expandAnnotatedClass(
        typeDef: TypeDef,
        existingCompanion: Option[ModuleDef],
        currentAnnotation: Option[Tree]
    )(using Context): List[Tree] =
      typeDef.rhs match
        case template: Template =>
          val strippedClass = HandledAnnotations.stripCurrent(typeDef, currentAnnotation)
          val generatedMethod = makeGeneratedHello(typeDef.source)

          // ASSUMPTION
          // Rewriting the untyped Template body before typer is early enough for the
          // generated method to participate in ordinary typing in the same run.
          //
          // MAY DEPEND ON SCALA VERSION
          // `Template.body` shape and `untpd` copy helpers are compiler-internal.
          val rewrittenTemplate =
            cpy.Template(template)(
              template.constr,
              template.parentsOrDerived(using summon[Context]),
              template.derived,
              template.self,
              template.body(using summon[Context]) :+ generatedMethod
            )

          val rewrittenClass =
            cpy.TypeDef(strippedClass)(strippedClass.name, rewrittenTemplate)

          // ASSUMPTION
          // Generating or merging the companion in the same pre-typer rewrite is early
          // enough for ordinary user code to resolve `User.generatedFactory(...)`.
          val generatedCompanion =
            existingCompanion match
              case Some(companion) =>
                mergeGeneratedCompanion(companion, typeDef.name)
              case None =>
                makeGeneratedCompanion(typeDef.name, typeDef.source)

          // NEEDS VERIFICATION
          // Existing companions are only merged in the narrow same-scope top-level case
          // handled by `takeExistingCompanion`.
          //
          // ASSUMPTION
          // No conflicting sibling class named `UserMeta` exists in the same scope.
          val generatedSibling = makeGeneratedSibling(typeDef.name, typeDef.source)

          // ASSUMPTION
          // Keep generated definition ordering stable as:
          //   1. rewritten class
          //   2. generated companion
          //   3. generated sibling
          // This deterministic order is part of the current same-run visibility story.
          List(rewrittenClass, generatedCompanion, generatedSibling)
        case _ =>
          List(typeDef)

    private def makeGeneratedHello(source: dotty.tools.dotc.util.SourceFile)(using Context): DefDef =
      given dotty.tools.dotc.util.SourceFile = source

      // ASSUMPTION
      // The current milestone expects an in-scope constructor parameter named `name`.
      // This works for both `class User(val name: String)` and `class User(name: String)`,
      // but it does not generalize to arbitrary parameter names or shapes.
      val helloPrefix = Literal(Constant("hello "))
      val plusCall = Select(helloPrefix, termName("+"))
      val methodBody = Apply(plusCall, Ident(termName("name")))

      DefDef(
        termName("generatedHello"),
        Nil,
        Ident(typeName("String")),
        methodBody
      )

    private def makeGeneratedCompanion(
        className: TypeName,
        source: dotty.tools.dotc.util.SourceFile
    )(using Context): ModuleDef =
      given dotty.tools.dotc.util.SourceFile = source

      val generatedFactory = makeGeneratedFactory(className)
      val companionTemplate = makeTemplate(source, List(generatedFactory))

      ModuleDef(className.toTermName, companionTemplate)

    private def mergeGeneratedCompanion(
        existingCompanion: ModuleDef,
        className: TypeName
    )(using Context): ModuleDef =
      val existingTemplate = existingCompanion.impl
      val existingBody = existingTemplate.body(using summon[Context])

      // ASSUMPTION
      // If the user already defines `generatedFactory`, preserving it and skipping the
      // hardcoded generated version is the least surprising deterministic behavior for now.
      val mergedBody =
        if companionHasGeneratedFactory(existingCompanion) then existingBody
        else existingBody :+ makeGeneratedFactory(className)

      val mergedTemplate =
        cpy.Template(existingTemplate)(
          existingTemplate.constr,
          existingTemplate.parentsOrDerived(using summon[Context]),
          existingTemplate.derived,
          existingTemplate.self,
          mergedBody
        )

      cpy.ModuleDef(existingCompanion)(existingCompanion.name, mergedTemplate)

    private def makeGeneratedFactory(className: TypeName)(using Context): DefDef =
      val paramType = Ident(typeName("String"))
      val nameParam = ValDef(termName("name"), paramType, EmptyTree).withMods(Modifiers(Param))
      val newUserCall =
        Apply(
          Select(New(Ident(className)), termName("<init>")),
          List(Ident(termName("name")))
        )

      DefDef(
        termName("generatedFactory"),
        List(List(nameParam)),
        Ident(className),
        newUserCall
      )

    private def companionHasGeneratedFactory(existingCompanion: ModuleDef)(using Context): Boolean =
      existingCompanion.impl.body(using summon[Context]).exists:
        case defDef: DefDef => defDef.name == termName("generatedFactory")
        case _ => false

    private def makeGeneratedSibling(
        className: TypeName,
        source: dotty.tools.dotc.util.SourceFile
    )(using Context): TypeDef =
      given dotty.tools.dotc.util.SourceFile = source

      val siblingName = siblingClassName(className)
      val siblingTemplate = makeTemplate(source, Nil)

      TypeDef(siblingName, siblingTemplate)

    private def siblingClassName(className: TypeName)(using Context): TypeName =
      typeName(s"${className.toString}Meta")

    private def makeTemplate(
        source: dotty.tools.dotc.util.SourceFile,
        body: List[Tree]
    )(using Context): Template =
      given dotty.tools.dotc.util.SourceFile = source

      // ASSUMPTION
      // Leaving parents empty in the untyped template is sufficient for these generated
      // top-level definitions, and typer will supply the standard class/object parents.
      //
      // NEEDS VERIFICATION
      // If later milestones need stricter control over inheritance or ownership, these
      // templates may require more explicit parent handling.
      Template(
        emptyConstructor,
        Nil,
        Nil,
        EmptyValDef,
        body
      )

  private object DebugAnnotationExpander extends AnnotationExpander:
    val annotationName: String = "debug"

    def expand(input: ExpansionInput)(using Context): ExpansionResult =
      ExpansionResult.Expanded(expandAnnotatedClass(input.annotatedClass, input.currentAnnotation))

    private def expandAnnotatedClass(typeDef: TypeDef, currentAnnotation: Option[Tree])(using Context): List[Tree] =
      typeDef.rhs match
        case template: Template =>
          val strippedClass = HandledAnnotations.stripCurrent(typeDef, currentAnnotation)
          val generatedMethod = makeDebugName(typeDef.name, typeDef.source)

          // ASSUMPTION
          // This follows the same pre-typer template-body rewrite strategy as `@gen`,
          // but deliberately avoids companion and sibling generation.
          val rewrittenTemplate =
            cpy.Template(template)(
              template.constr,
              template.parentsOrDerived(using summon[Context]),
              template.derived,
              template.self,
              template.body(using summon[Context]) :+ generatedMethod
            )

          List(cpy.TypeDef(strippedClass)(strippedClass.name, rewrittenTemplate))
        case _ =>
          List(typeDef)

    private def makeDebugName(
        className: TypeName,
        source: dotty.tools.dotc.util.SourceFile
    )(using Context): DefDef =
      given dotty.tools.dotc.util.SourceFile = source

      DefDef(
        termName("debugName"),
        Nil,
        Ident(typeName("String")),
        Literal(Constant(className.toString))
      )

  private final class ExternalAnnotationExpander(
      loadedHandler: LoadedExternalHandler,
      invocationTrace: ExternalHandlerInvocationTrace
  ) extends AnnotationExpander:
    private val handler = loadedHandler.instance
    private val descriptor = loadedHandler.descriptor
    val annotationName: String = descriptor.annotationName
    override val canonicalHandlerIdentity: String =
      descriptor.handlerClassName
    override val consumesExistingCompanion: Boolean =
      descriptor.consumesExistingCompanion
    override val targetAdmissionProfile: TargetAdmissionProfile =
      descriptor.targetProfile match
        case ExternalExpansionTargetProfile.CommonClassOnly =>
          TargetAdmissionProfile.CommonClassOnly
        case ExternalExpansionTargetProfile.RestrictedGenericTraitApply =>
          TargetAdmissionProfile.RestrictedGenericTraitApply
        case ExternalExpansionTargetProfile.TwoUpperBoundedGenericTrait =>
          TargetAdmissionProfile.TwoUpperBoundedGenericTrait
        case ExternalExpansionTargetProfile.PlainZeroParameterTrait =>
          TargetAdmissionProfile.PlainZeroParameterTrait
        case ExternalExpansionTargetProfile.RestrictedOrTwoUpperBoundedGenericTrait =>
          TargetAdmissionProfile.RestrictedOrTwoUpperBoundedGenericTrait
    override val compositionPolicy: CompositionPolicy =
      descriptor.compositionPolicy match
        case ExternalExpansionCompositionPolicy.StandaloneOnly =>
          CompositionPolicy.StandaloneOnly
        case ExternalExpansionCompositionPolicy.SourceOrdered =>
          CompositionPolicy.SourceOrdered
    override val validationOrigin: String =
      s"external handler `${descriptor.handlerClassName}` for @$annotationName"
    override def outputValidationDiagnostic(category: String, detail: String): String =
      ExternalHandlerDiagnostics.render(
        ExternalHandlerDiagnostics.Stage.OutputValidation,
        category,
        "annotation" -> s"@$annotationName",
        "handler" -> descriptor.handlerClassName,
        "detail" -> detail
      )

    def expand(input: ExpansionInput)(using Context): ExpansionResult =
      val externalInput =
        ExternalExpansionInput(
          annotationName = annotationName,
          annotatedClass = input.annotatedClass,
          existingCompanion = input.existingCompanion,
          topLevelNames = input.topLevel.names,
          currentAnnotation = input.currentAnnotation
        )

      try
        invocationTrace.record(
          descriptor.handlerClassName,
          annotationName,
          input.annotatedClass.name.toString
        )
        val outcome = handler.expand(externalInput)
        adaptExternalOutcome(outcome, input)
      catch
        case error: LinkageError =>
          invocationFailure("LINKAGE_ERROR", error, input)
        case NonFatal(error) =>
          invocationFailure("NONFATAL_EXCEPTION", error, input)

    private def adaptExternalOutcome(
        outcome: ExternalExpansionOutcome,
        input: ExpansionInput
    )(using Context): ExpansionResult =
      if outcome == null then
        protocolRejection(
          "NULL_OUTCOME",
          "returned null instead of ExpansionOutcome",
          input,
          ExternalHandlerDiagnostics.Stage.OutputValidation
        )
      else
        outcome match
          case ExternalExpansionOutcome.Expanded(trees) =>
            if trees == null then
              protocolRejection(
                "NULL_EXPANDED_TREES",
                "returned Expanded with a null tree list",
                input,
                ExternalHandlerDiagnostics.Stage.OutputValidation
              )
            else
              ExpansionResult.Expanded(trees)
          case ExternalExpansionOutcome.Structured(output) =>
            ExpansionResult.Structured(output)
          case ExternalExpansionOutcome.Rejected(diagnostics, fallback) =>
            adaptRejected(diagnostics, fallback, input)
          case ExternalExpansionOutcome.NotApplicable =>
            protocolRejection(
              "SELECTED_HANDLER_NOT_APPLICABLE",
              "selected handler declined the admitted target",
              input,
              ExternalHandlerDiagnostics.Stage.OutputValidation
            )

    private def adaptRejected(
        diagnostics: List[paradise3.api.ExpansionDiagnostic],
        fallback: TypeDef,
        input: ExpansionInput
    )(using Context): ExpansionResult =
      val adaptedDiagnostics =
        if diagnostics == null then
          List(protocolDiagnostic("NULL_REJECTION_DIAGNOSTICS", "returned Rejected with a null diagnostics list", input, ExternalHandlerDiagnostics.Stage.OutputValidation))
        else if diagnostics.isEmpty && !loadedHandler.metadataFailureAlreadyReported then
          List(protocolDiagnostic("EMPTY_REJECTION_DIAGNOSTICS", "returned Rejected without a diagnostic", input, ExternalHandlerDiagnostics.Stage.OutputValidation))
        else if diagnostics.exists(_ == null) then
          List(protocolDiagnostic("NULL_REJECTION_DIAGNOSTIC", "returned Rejected with a null diagnostic entry", input, ExternalHandlerDiagnostics.Stage.OutputValidation))
        else
          diagnostics.map: diagnostic =>
            if diagnostic.message == null then
              protocolDiagnostic("NULL_REJECTION_MESSAGE", "returned Rejected with a null diagnostic message", input, ExternalHandlerDiagnostics.Stage.OutputValidation)
            else if diagnostic.pos == null then
              ExpansionDiagnostic(diagnostic.message, diagnosticPosition(input))
            else
              ExpansionDiagnostic(diagnostic.message, diagnostic.pos)

      val (validatedFallback, fallbackDiagnostics) =
        if fallback == null then
          (
            input.annotatedClass,
            List(protocolDiagnostic("NULL_REJECTED_FALLBACK", "returned Rejected with a null fallback", input, ExternalHandlerDiagnostics.Stage.OutputValidation))
          )
        else if fallback.name != input.annotatedClass.name then
          (
            input.annotatedClass,
            List(
              protocolDiagnostic(
                "INVALID_REJECTED_FALLBACK_NAME",
                s"returned fallback `${fallback.name}` instead of current primary `${input.annotatedClass.name}`",
                input,
                ExternalHandlerDiagnostics.Stage.OutputValidation
              )
            )
          )
        else
          (fallback, Nil)

      // Protocol-integrity diagnostics come first so compiler reporters that
      // suppress follow-on errors still expose the coordinator-owned cause.
      ExpansionResult.Rejected(fallbackDiagnostics ++ adaptedDiagnostics, validatedFallback)

    private def invocationFailure(
        category: String,
        error: Throwable,
        input: ExpansionInput
    )(using Context): ExpansionResult =
      val causeClass = error.getClass.getName
      val causeMessage = normalizedCauseMessage(error)
      protocolRejection(
        category,
        s"cause=$causeClass message=$causeMessage",
        input,
        ExternalHandlerDiagnostics.Stage.Invocation
      )

    private def protocolRejection(
      category: String,
      detail: String,
      input: ExpansionInput,
      stage: ExternalHandlerDiagnostics.Stage
    )(using Context): ExpansionResult =
      ExpansionResult.Rejected(
        List(protocolDiagnostic(category, detail, input, stage)),
        input.annotatedClass
      )

    private def protocolDiagnostic(
      category: String,
      detail: String,
      input: ExpansionInput,
      stage: ExternalHandlerDiagnostics.Stage
    )(using Context): ExpansionDiagnostic =
      ExpansionDiagnostic(
        ExternalHandlerDiagnostics.render(
          stage,
          category,
          "annotation" -> s"@$annotationName",
          "handler" -> descriptor.handlerClassName,
          "class" -> input.annotatedClass.name.toString,
          "detail" -> detail
        ),
        diagnosticPosition(input)
      )

    private def diagnosticPosition(input: ExpansionInput)(using Context): SrcPos =
      input.currentAnnotation.map(_.sourcePos).getOrElse(input.annotatedClass.sourcePos)

    private def normalizedCauseMessage(error: Throwable): String =
      Option(error.getMessage)
        .map(_.replaceAll("\\s+", " ").trim)
        .filter(_.nonEmpty)
        .getOrElse("<no-message>")

  private object HandledAnnotations:
    def matchingExpanders(typeDef: TypeDef)(using Context, ExplicitImportAnnotationIdentityResolver): List[AnnotationExpander] =
      matchingExpanders(typeDef, Nil)

    def matchingExpanders(
        typeDef: TypeDef,
        externalExpanders: List[AnnotationExpander]
    )(using Context, ExplicitImportAnnotationIdentityResolver): List[AnnotationExpander] =
      matchingExpanders(Trees.mods(typeDef).annotations, externalExpanders)

    def matchingAnnotationsInSourceOrder(
        typeDef: TypeDef,
        externalExpanders: List[AnnotationExpander],
        identityWitnesses: List[Tree] = Nil
    )(using Context, ExplicitImportAnnotationIdentityResolver): List[MatchingAnnotation] =
      // MAY DEPEND ON SCALA VERSION
      // The pre-typer modifier annotation list is assumed to preserve source
      // order. Keep the original raw tree here so identity-based stripping can
      // remove exactly the annotation whose turn is being expanded.
      Trees.mods(typeDef).annotations.flatMap: annotation =>
        expanders(externalExpanders)
          .find(expander => isAnnotationNamed(annotation, expander.annotationName, identityWitnesses))
          .map(expander => MatchingAnnotation(annotation, expander))

    def matchingExpanders(moduleDef: ModuleDef)(using Context, ExplicitImportAnnotationIdentityResolver): List[AnnotationExpander] =
      matchingExpanders(moduleDef, Nil)

    def matchingExpanders(
        moduleDef: ModuleDef,
        externalExpanders: List[AnnotationExpander]
    )(using Context, ExplicitImportAnnotationIdentityResolver): List[AnnotationExpander] =
      matchingExpanders(Trees.mods(moduleDef).annotations, externalExpanders)

    def annotationLabel(matching: List[AnnotationExpander]): String =
      matching match
        case expander :: Nil =>
          s"@${expander.annotationName}"
        case many =>
          many.map(expander => s"@${expander.annotationName}").mkString("handled annotations ", ", ", "")

    def strip(typeDef: TypeDef)(using Context): TypeDef =
      val currentMods = Trees.mods(typeDef)
      val preserved = currentMods.annotations.filterNot: annotation =>
        annotationName(annotation).exists(name => builtInExpanders.exists(_.annotationName == name))
      typeDef.withMods(currentMods.withAnnotations(preserved)).asInstanceOf[TypeDef]

    def strip(typeDef: TypeDef, externalExpanders: List[AnnotationExpander])(using Context, ExplicitImportAnnotationIdentityResolver): TypeDef =
      val currentMods = Trees.mods(typeDef)
      typeDef.withMods(currentMods.withAnnotations(removeHandledAnnotations(currentMods.annotations, externalExpanders))).asInstanceOf[TypeDef]

    def stripCurrent(typeDef: TypeDef, currentAnnotation: Option[Tree])(using Context): TypeDef =
      currentAnnotation match
        case Some(annotation) =>
          val currentMods = Trees.mods(typeDef)
          val preservedAnnotations = currentMods.annotations.filterNot(_ eq annotation)
          typeDef.withMods(currentMods.withAnnotations(preservedAnnotations)).asInstanceOf[TypeDef]
        case None =>
          strip(typeDef)

    def withAnnotations(typeDef: TypeDef, annotations: List[Tree])(using Context): TypeDef =
      val currentMods = Trees.mods(typeDef)
      typeDef.withMods(currentMods.withAnnotations(annotations)).asInstanceOf[TypeDef]

    def strip(moduleDef: ModuleDef)(using Context): ModuleDef =
      val currentMods = Trees.mods(moduleDef)
      val preserved = currentMods.annotations.filterNot: annotation =>
        annotationName(annotation).exists(name => builtInExpanders.exists(_.annotationName == name))
      moduleDef.withMods(currentMods.withAnnotations(preserved)).asInstanceOf[ModuleDef]

    def strip(moduleDef: ModuleDef, externalExpanders: List[AnnotationExpander])(using Context, ExplicitImportAnnotationIdentityResolver): ModuleDef =
      val currentMods = Trees.mods(moduleDef)
      moduleDef.withMods(currentMods.withAnnotations(removeHandledAnnotations(currentMods.annotations, externalExpanders))).asInstanceOf[ModuleDef]

    private def matchingExpanders(
        annotations: List[Tree],
        externalExpanders: List[AnnotationExpander]
    )(using Context, ExplicitImportAnnotationIdentityResolver): List[AnnotationExpander] =
      expanders(externalExpanders).filter: expander =>
        annotations.exists(isAnnotationNamed(_, expander.annotationName))

    private def removeHandledAnnotations(
        annotations: List[Tree],
        externalExpanders: List[AnnotationExpander]
    )(using Context, ExplicitImportAnnotationIdentityResolver): List[Tree] =
      annotations.filterNot(isHandledAnnotation(_, externalExpanders))

    private def isHandledAnnotation(tree: Tree, externalExpanders: List[AnnotationExpander])(using Context, ExplicitImportAnnotationIdentityResolver): Boolean =
      expanders(externalExpanders).exists(expander => isAnnotationNamed(tree, expander.annotationName))

    private def isAnnotationNamed(
        tree: Tree,
        expectedName: String,
        identityWitnesses: List[Tree] = Nil
    )(using context: Context, resolver: ExplicitImportAnnotationIdentityResolver): Boolean =
      resolver
        .identityOfUsingWitnesses(tree, identityWitnesses)
        .toOption
        .exists(_.value == expectedName)

    def annotationName(tree: Tree)(using Context): Option[String] =
      SyntacticAnnotationIdentity.fromTree(tree).map(_.value)

  def rewriteUnit(
      unit: CompilationUnit,
      loadedExternalHandlers: ExternalHandlerLoading.LoadedHandlers,
      privateObjectParticipant: Option[PrivateObjectPrimaryTransform.Participant]
  )(using Context): Tree =
    given identityResolver: ExplicitImportAnnotationIdentityResolver =
      ExplicitImportAnnotationIdentityResolver.fromUnitTree(unit.untpdTree)
    val discovery =
      ExternalHandlerLoading.discoverMetadataHandlers(
        collectAnnotationIdentityRequests(unit.untpdTree),
        loadedExternalHandlers
      )
    discovery.legacySimpleRequests.foreach(identityResolver.preferLegacySimpleIdentity)
    val discoveredHandlers = discovery.handlers
    val externalHandlers =
      ExternalHandlerLoading.validateUniqueHandlers(
        dedupeHandlersByClass(loadedExternalHandlers.explicit ++ discoveredHandlers)
      )
    val externalExpanders =
      externalHandlers.map: handler =>
        ExternalAnnotationExpander(
          handler,
          loadedExternalHandlers.invocationTrace
        )

    unit.untpdTree match
      case pkg: PackageDef =>
        val rewrittenStats =
          privateObjectParticipant match
            case None => rewritePackageStats(pkg.stats, externalExpanders)
            case Some(participant) =>
              rewritePrivateObjectTransactions(pkg.stats, participant) match
                case Right(privateStats) =>
                  rewritePackageStats(privateStats, externalExpanders)
                case Left(originalStats) => originalStats
        cpy.PackageDef(pkg)(pkg.pid, rewrittenStats)
      case tree =>
        tree

  private def rewritePrivateObjectTransactions(
      originalStats: List[Tree],
      participant: PrivateObjectPrimaryTransform.Participant
  )(using Context): Either[List[Tree], List[Tree]] =
    import PrivateObjectPrimaryTransform.*

    var currentStats = originalStats
    var failed = false

    originalStats.foreach: original =>
      if !failed then
        original match
          case moduleDef: ModuleDef =>
            val originalAnnotations = Trees.mods(moduleDef).annotations
            val matchingAnnotations = originalAnnotations.filter: annotation =>
              HandledAnnotations.annotationName(annotation).contains(
                participant.annotationName
              )
            matchingAnnotations match
              case Nil => ()
              case currentAnnotation :: Nil =>
                val transaction =
                  ObjectTransaction
                    .discover(
                      currentStats,
                      moduleDef,
                      Vector(participant.getClass.getName)
                    )
                transaction match
                  case Left(violation) =>
                    reportPrivateObjectFailure(
                      participant,
                      moduleDef,
                      currentAnnotation,
                      "discovery",
                      violation.category.toString,
                      violation.detail
                    )
                    failed = true
                  case Right(started) =>
                    val input =
                      Input(
                        started.targetId,
                        started.currentPrimary,
                        started.currentOpposite,
                        currentAnnotation,
                        originalAnnotations,
                        collectTopLevelNames(currentStats)
                      )
                    val transformed =
                      try participant.transform(input)
                      catch
                        case NonFatal(error) =>
                          Left(
                            Failure(
                              "NONFATAL_EXCEPTION",
                              s"${error.getClass.getName}: ${Option(error.getMessage).getOrElse("")}"
                            )
                          )
                    transformed match
                      case Left(failure) =>
                        reportPrivateObjectFailure(
                          participant,
                          moduleDef,
                          currentAnnotation,
                          "transform",
                          failure.category,
                          failure.detail
                        )
                        failed = true
                      case Right(result) =>
                        started.stageValidatedOutput(result) match
                          case Left(violation) =>
                            reportPrivateObjectFailure(
                              participant,
                              moduleDef,
                              currentAnnotation,
                              "role-validation",
                              violation.category.toString,
                              violation.detail
                            )
                            failed = true
                          case Right(staged) =>
                            validatePrivateObjectAnnotationIdentity(input, result) match
                              case Left(failure) =>
                                reportPrivateObjectFailure(
                                  participant,
                                  moduleDef,
                                  currentAnnotation,
                                  "annotation-identity",
                                  failure.category,
                                  failure.detail
                                )
                                failed = true
                              case Right(_) =>
                                val completion =
                                  try participant.validateStaged(input, result)
                                  catch
                                    case NonFatal(error) =>
                                      Left(
                                        Failure(
                                          "NONFATAL_EXCEPTION",
                                          s"${error.getClass.getName}: ${Option(error.getMessage).getOrElse("")}"
                                        )
                                      )
                                completion match
                                  case Left(failure) =>
                                    reportPrivateObjectFailure(
                                      participant,
                                      moduleDef,
                                      currentAnnotation,
                                      "late-validation",
                                      failure.category,
                                      failure.detail
                                    )
                                    failed = true
                                  case Right(_) =>
                                    currentStats = staged.commitPackageStats
              case _ =>
                reportPrivateObjectFailure(
                  participant,
                  moduleDef,
                  matchingAnnotations.head,
                  "admission",
                  "DUPLICATE_PRIVATE_ANNOTATION",
                  s"expected one exact @${participant.annotationName} occurrence, found ${matchingAnnotations.size}"
                )
                failed = true
          case _ => ()

    if failed then Left(originalStats) else Right(currentStats)

  private def validatePrivateObjectAnnotationIdentity(
      input: PrivateObjectPrimaryTransform.Input,
      result: RoleAwareExpansionResult
  )(using Context): Either[PrivateObjectPrimaryTransform.Failure, Unit] =
    result.continuingPrimary match
      case PrimaryRole.ObjectPrimary(moduleDef) =>
        val returned = Trees.mods(moduleDef).annotations
        val sameIdentityAndOrder =
          returned.size == input.originalAnnotations.size &&
            returned.zip(input.originalAnnotations).forall:
              case (actual, original) => actual eq original
        if sameIdentityAndOrder then Right(())
        else
          Left(
            PrivateObjectPrimaryTransform.Failure(
              "ANNOTATION_IDENTITY_MISMATCH",
              "private object transformation must preserve all source annotation nodes in exact order"
            )
          )
      case _ => Right(())

  private def reportPrivateObjectFailure(
      participant: PrivateObjectPrimaryTransform.Participant,
      primary: ModuleDef,
      annotation: Tree,
      stage: String,
      category: String,
      detail: String
  )(using Context): Unit =
    report.error(
      s"private object transaction failure: stage=$stage category=$category annotation=@${participant.annotationName} object=${primary.name} detail=$detail",
      annotation.sourcePos
    )

  private def dedupeHandlersByClass(
      handlers: List[LoadedExternalHandler]
  ): List[LoadedExternalHandler] =
    val seen = scala.collection.mutable.Set.empty[String]
    handlers.filter: handler =>
      val descriptor = handler.descriptor
      val key = s"${descriptor.handlerClassName}:${descriptor.annotationName}"
      val isNew = !seen.contains(key)
      seen += key
      isNew

  private def collectAnnotationIdentityRequests(
      tree: Tree
  )(using context: Context, resolver: ExplicitImportAnnotationIdentityResolver): Set[ExplicitImportAnnotationIdentityRequest] =
    val requests = Set.newBuilder[ExplicitImportAnnotationIdentityRequest]

    def loop(current: Tree): Unit =
      current match
        case typeDef: TypeDef =>
          Trees.mods(typeDef).annotations.foreach: annotation =>
            resolver.requestOf(annotation) match
              case Right(request) => requests += request
              case Left(diagnostic) => report.error(diagnostic.message, diagnostic.pos)
          typeDef.rhs match
            case template: Template =>
              template.body(using summon[Context]).foreach(loop)
            case _ =>
        case moduleDef: ModuleDef =>
          Trees.mods(moduleDef).annotations.foreach: annotation =>
            resolver.requestOf(annotation) match
              case Right(request) => requests += request
              case Left(diagnostic) => report.error(diagnostic.message, diagnostic.pos)
          moduleDef.impl.body(using summon[Context]).foreach(loop)
        case pkg: PackageDef =>
          pkg.stats.foreach(loop)
        case _ =>

    loop(tree)
    requests.result()

  private def rewritePackageStats(
      stats: List[Tree],
      externalExpanders: List[AnnotationExpander]
  )(using Context, ExplicitImportAnnotationIdentityResolver): List[Tree] =
    rewritePackageStats(stats, TopLevelRewriteContext(collectTopLevelNames(stats)), externalExpanders)

  private def collectTopLevelNames(stats: List[Tree]): Set[String] =
    val names = stats.collect:
      case typeDef: TypeDef => typeDef.name.toString
      case moduleDef: ModuleDef => moduleDef.name.toString
    names.toSet

  private def rewritePackageStats(
      stats: List[Tree],
      topLevel: TopLevelRewriteContext,
      externalExpanders: List[AnnotationExpander]
  )(using Context, ExplicitImportAnnotationIdentityResolver): List[Tree] =
    stats match
      case Nil => Nil
      case originalStats @ ((typeDef: TypeDef) :: rest) =>
        val matchingAnnotations = HandledAnnotations.matchingAnnotationsInSourceOrder(typeDef, externalExpanders)
        val matching = matchingAnnotations.map(_.expander)
        if matchingAnnotations.nonEmpty then
          compositionAdmission(typeDef, matchingAnnotations) match
            case Some(diagnostic) =>
              reportDiagnostic(diagnostic)
              HandledAnnotations.strip(typeDef, externalExpanders) ::
                rewritePackageStats(rest, topLevel, externalExpanders)
            case None if isSupportedTopLevelTarget(typeDef, matching) =>
              preExpansionAdmission(typeDef, matching) match
                case Some(diagnostic) =>
                  reportDiagnostic(diagnostic)
                  HandledAnnotations.strip(typeDef, externalExpanders) ::
                    rewritePackageStats(rest, topLevel, externalExpanders)
                case None =>
                  rewriteAnnotatedTopLevelClass(
                    originalStats,
                    typeDef,
                    rest,
                    topLevel,
                    matchingAnnotations,
                    externalExpanders
                  )
            case None =>
              rejectUnsupportedTarget(typeDef, unsupportedTypeDefTarget(typeDef), matching, externalExpanders) ::
                rewritePackageStats(rest, topLevel, externalExpanders)
        else
          stripNestedHandledAnnotations(typeDef, externalExpanders) :: rewritePackageStats(rest, topLevel, externalExpanders)
      case (moduleDef: ModuleDef) :: rest =>
        val matching = HandledAnnotations.matchingExpanders(moduleDef, externalExpanders)
        if matching.nonEmpty then
          rejectUnsupportedTarget(moduleDef, s"object ${moduleDef.name}", matching, externalExpanders) :: rewritePackageStats(rest, topLevel, externalExpanders)
        else
          stripNestedHandledAnnotations(moduleDef, externalExpanders) :: rewritePackageStats(rest, topLevel, externalExpanders)
      case stat :: rest =>
        stripNestedHandledAnnotations(stat, externalExpanders) :: rewritePackageStats(rest, topLevel, externalExpanders)

  private def rewriteAnnotatedTopLevelClass(
      originalStats: List[Tree],
      typeDef: TypeDef,
      rest: List[Tree],
      topLevel: TopLevelRewriteContext,
      matchingAnnotations: List[MatchingAnnotation],
      externalExpanders: List[AnnotationExpander]
  )(using Context, ExplicitImportAnnotationIdentityResolver): List[Tree] =
    matchingAnnotations match
      case matching :: Nil =>
        rewriteSingleTopLevelClass(
          originalStats,
          typeDef,
          rest,
          topLevel,
          matching,
          externalExpanders
        )
      case sourceOrdered =>
        rewriteComposedTopLevelClass(
          originalStats,
          typeDef,
          rest,
          topLevel,
          sourceOrdered,
          externalExpanders
        )

  private def startLegacyTransaction(
      originalStats: List[Tree],
      typeDef: TypeDef,
      existingCompanion: Option[ModuleDef],
      sourceParticipants: Vector[String]
  )(using Context): LegacyTransaction =
    val transaction =
      for
        primary <- PrimaryRole.fromLegacyTypeDef(typeDef)
        snapshot <- TransactionSnapshot.capture(
          originalStats,
          primary,
          existingCompanion.map(OppositeRole.ObjectOpposite(_)),
          sourceParticipants
        )
        legacy <- LegacyTransaction.start(snapshot)
      yield legacy
    transaction.fold(
      violation =>
        throw new IllegalStateException(
          s"role-aware transaction invariant failed before legacy invocation: ${violation.render}"
        ),
      identity
    )

  private def stageValidatedLegacy(
      transaction: LegacyTransaction,
      trees: List[Tree],
      oppositeOmission: LegacyOppositeOmissionPolicy
  )(using Context): LegacyTransaction =
    transaction.stageValidatedOutput(trees, oppositeOmission).fold(
      violation =>
        throw new IllegalStateException(
          s"role-aware transaction invariant failed after legacy validation: ${violation.render}"
        ),
      identity
    )

  private def rewriteSingleTopLevelClass(
      originalStats: List[Tree],
      typeDef: TypeDef,
      rest: List[Tree],
      topLevel: TopLevelRewriteContext,
      matching: MatchingAnnotation,
      externalExpanders: List[AnnotationExpander]
  )(using Context, ExplicitImportAnnotationIdentityResolver): List[Tree] =
    val MatchingAnnotation(annotation, expander) = matching
    val (existingCompanion, remainingStats) =
      if expander.consumesExistingCompanion then
        takeExistingCompanion(typeDef.name, rest)
      else (None, rest)
    val inputTransaction =
      startLegacyTransaction(
        originalStats,
        typeDef,
        existingCompanion,
        Vector(expander.canonicalHandlerIdentity)
      )
    val inputProjection =
      inputTransaction.projection(expander.consumesExistingCompanion)
    val input =
      ExpansionInput(
        inputProjection.primary,
        inputProjection.companion,
        topLevel,
        Some(annotation)
      )
    expandAndValidate(
      expander,
      input,
      originalSourceAnnotations = List(annotation)
    ) match
      case ValidatedExpansionResult.Expanded(trees, Nil) =>
        // Preserve the established single-annotation path exactly when no
        // coordinator-owned request exists, independent of the descriptor's
        // captured composition declaration.
        stageValidatedLegacy(
          inputTransaction,
          trees,
          LegacyOppositeOmissionPolicy.DropCurrent
        ).outputTrees ++
          rewritePackageStats(remainingStats, topLevel, externalExpanders)
      case rejected: ValidatedExpansionResult.Rejected =>
        spliceExpansionResult(
          rejected,
          remainingStats,
          topLevel,
          externalExpanders,
          restoredCompanionOnRejection = existingCompanion
        )
      case ValidatedExpansionResult.Expanded(trees, drafts) =>
        val (transactionCompanion, postTransactionStats) =
          if expander.consumesExistingCompanion then
            (existingCompanion, remainingStats)
          else takeExistingCompanion(typeDef.name, remainingStats)
        val transaction =
          if expander.consumesExistingCompanion then inputTransaction
          else
            startLegacyTransaction(
              originalStats,
              typeDef,
              transactionCompanion,
              Vector(expander.canonicalHandlerIdentity)
            )
        val lineage = transaction.targetId
        val initialState =
          CompositionState(
            transaction = transaction,
            generatedQueue = Nil,
            seenGeneratedStates = Nil,
            generatedSteps = 0,
            recentTrace = Vector.empty,
            nextTraceStep = 0
          )
        val sourceTrace =
          FurtherExpansionTraceEntry(
            step = 0,
            provenance = "source",
            annotationName = expander.annotationName,
            canonicalHandlerIdentity = expander.canonicalHandlerIdentity,
            targetName = typeDef.name.toString,
            requestedBy = None
          )
        val tentativeState =
          updateCompositionState(initialState, trees).appendTrace(sourceTrace)
        val sourceResult =
          if expander.compositionPolicy == CompositionPolicy.StandaloneOnly then
            resolveFurtherExpansionDrafts(
              tentativeState,
              drafts,
              expander,
              externalExpanders,
              annotation.sourcePos
            ).map(_ => tentativeState)
          else
            validateCompositionStep(
              current = matching,
              consumed = Nil,
              later = Nil,
              externalExpanders = externalExpanders,
              trees = trees
            ) match
              case Some(diagnostic) => Left(List(diagnostic))
              case None =>
                resolveFurtherExpansionDrafts(
                  tentativeState,
                  drafts,
                  expander,
                  externalExpanders,
                  annotation.sourcePos
                ).map: requests =>
                  tentativeState.copy(generatedQueue = requests)
        val result =
          sourceResult
            .left.map(diagnostics => (diagnostics, typeDef))
            .flatMap: state =>
              drainFurtherExpansionQueue(
                state,
                lineage,
                topLevel,
                externalExpanders,
                originalSourceAnnotations = List(annotation)
              )
        result match
          case Right(state) =>
            state.outputTrees ++
              rewritePackageStats(
                postTransactionStats,
                topLevel,
                externalExpanders
              )
          case Left((diagnostics, _)) =>
            val rollback = transaction.rollback
            val rollbackPrimary =
              rollback.primary.legacyTypeDefOption.getOrElse(
                throw new IllegalStateException(
                  "legacy transaction rollback contained an object primary"
                )
              )
            val rollbackCompanion =
              rollback.opposite.collect:
                case OppositeRole.ObjectOpposite(value) => value
            spliceExpansionResult(
              ValidatedExpansionResult.Rejected(diagnostics, rollbackPrimary),
              postTransactionStats,
              topLevel,
              externalExpanders,
              restoredCompanionOnRejection = rollbackCompanion
            )

  private def rewriteComposedTopLevelClass(
      originalStats: List[Tree],
      typeDef: TypeDef,
      rest: List[Tree],
      topLevel: TopLevelRewriteContext,
      annotations: List[MatchingAnnotation],
      externalExpanders: List[AnnotationExpander]
  )(using Context, ExplicitImportAnnotationIdentityResolver): List[Tree] =
    val (existingCompanion, remainingStats) =
      // ASSUMPTION
      // A following top-level companion is leased exactly once for the complete
      // source plus generated transaction. Source/generated handlers still see
      // it only when their captured descriptor requests companion consumption.
      takeExistingCompanion(typeDef.name, rest)

    val transaction =
      startLegacyTransaction(
        originalStats,
        typeDef,
        existingCompanion,
        annotations.map(_.expander.canonicalHandlerIdentity).toVector
      )
    val lineage = transaction.targetId
    val initialState =
      CompositionState(
        transaction = transaction,
        generatedQueue = Nil,
        seenGeneratedStates = Nil,
        generatedSteps = 0,
        recentTrace = Vector.empty,
        nextTraceStep = 0
      )
    val sourceResult =
      annotations.zipWithIndex.foldLeft[Either[(List[ExpansionDiagnostic], TypeDef), CompositionState]](Right(initialState)):
        case (Left(rejected), _) =>
          Left(rejected)
        case (Right(state), (matchingAnnotation, index)) =>
          val projection =
            state.transaction.projection(
              matchingAnnotation.expander.consumesExistingCompanion
            )
          val input =
            ExpansionInput(
              annotatedClass = projection.primary,
              // Each opt-in expander receives the latest companion produced or
              // merged by the preceding source-ordered expansion step.
              existingCompanion = projection.companion,
              topLevel = topLevel,
              currentAnnotation = Some(matchingAnnotation.annotation)
            )

          expandAndValidate(
            matchingAnnotation.expander,
            input,
            knownAdditionalTrees = state.additionalTopLevelTrees,
            originalSourceAnnotations = annotations.map(_.annotation)
          ) match
            case ValidatedExpansionResult.Expanded(trees, drafts) =>
              validateCompositionStep(
                matchingAnnotation,
                annotations.take(index),
                annotations.drop(index + 1),
                externalExpanders,
                trees
              ) match
                case Some(diagnostic) =>
                  Left((List(diagnostic), state.currentClass))
                case None =>
                  val nextState =
                    updateCompositionState(state, trees).appendTrace(
                      FurtherExpansionTraceEntry(
                        step = state.nextTraceStep,
                        provenance = "source",
                        annotationName = matchingAnnotation.expander.annotationName,
                        canonicalHandlerIdentity =
                          matchingAnnotation.expander.canonicalHandlerIdentity,
                        targetName = typeDef.name.toString,
                        requestedBy = None
                      )
                    )
                  resolveFurtherExpansionDrafts(
                    nextState,
                    drafts,
                    matchingAnnotation.expander,
                    externalExpanders,
                    matchingAnnotation.annotation.sourcePos
                  ) match
                    case Left(diagnostics) =>
                      Left((diagnostics, state.currentClass))
                    case Right(requests) =>
                      Right(
                        nextState.copy(
                          generatedQueue = nextState.generatedQueue ++ requests
                        )
                      )
            case ValidatedExpansionResult.Rejected(diagnostics, fallback) =>
              Left((diagnostics, fallback))

    val result =
      sourceResult.flatMap: state =>
        drainFurtherExpansionQueue(
          state,
          lineage,
          topLevel,
          externalExpanders,
          originalSourceAnnotations = annotations.map(_.annotation)
        )

    result match
      case Right(state) =>
        state.outputTrees ++ rewritePackageStats(remainingStats, topLevel, externalExpanders)
      case Left((diagnostics, _)) =>
        // Composition is transactional. A later failed step must not commit
        // class, companion, or additional output from any earlier step.
        val rollback = transaction.rollback
        val rollbackPrimary =
          rollback.primary.legacyTypeDefOption.getOrElse(
            throw new IllegalStateException(
              "legacy transaction rollback contained an object primary"
            )
          )
        val rollbackCompanion =
          rollback.opposite.collect:
            case OppositeRole.ObjectOpposite(value) => value
        spliceExpansionResult(
          ValidatedExpansionResult.Rejected(diagnostics, rollbackPrimary),
          remainingStats,
          topLevel,
          externalExpanders,
          restoredCompanionOnRejection = rollbackCompanion
        )

  private def resolveFurtherExpansionDrafts(
      state: CompositionState,
      drafts: List[InternalFurtherExpansionRequests.Draft],
      generator: AnnotationExpander,
      externalExpanders: List[AnnotationExpander],
      fallbackPos: SrcPos
  )(using Context): Either[List[ExpansionDiagnostic], List[FurtherExpansionRequest]] =
    if drafts.isEmpty then Right(Nil)
    else if generator.compositionPolicy != CompositionPolicy.SourceOrdered then
      Left(
        List(
          furtherExpansionDiagnostic(
            "FURTHER_EXPANSION_STANDALONE_GENERATOR",
            List(
              "handler" -> s"@${generator.annotationName}",
              "requestedBy" -> generator.canonicalHandlerIdentity,
              "detail" -> "a StandaloneOnly handler cannot enqueue generated/delegated work"
            ),
            state,
            None,
            fallbackPos
          )
        )
      )
    else
      val available = expanders(externalExpanders)
      drafts.foldLeft[Either[List[ExpansionDiagnostic], List[FurtherExpansionRequest]]](Right(Nil)):
        case (Left(diagnostics), _) => Left(diagnostics)
        case (Right(resolved), draft) =>
          val requestPos =
            Option(draft.rawApplication)
              .map(_.sourcePos)
              .filter(_.span.exists)
              .getOrElse(fallbackPos)
          val normalizedName =
            Option(draft.annotationName).map(_.trim).filter(_.nonEmpty)
          normalizedName match
            case None =>
              Left(
                List(
                  furtherExpansionDiagnostic(
                    "FURTHER_EXPANSION_MALFORMED_REQUEST",
                    List(
                      "handler" -> "@<missing>",
                      "requestedBy" -> generator.canonicalHandlerIdentity,
                      "detail" -> "request annotation name is empty"
                    ),
                    state,
                    None,
                    requestPos
                  )
                )
              )
            case Some(annotationName) if draft.rawApplication == null =>
              Left(
                List(
                  furtherExpansionDiagnostic(
                    "FURTHER_EXPANSION_MALFORMED_REQUEST",
                    List(
                      "handler" -> s"@$annotationName",
                      "requestedBy" -> generator.canonicalHandlerIdentity,
                      "detail" -> "request raw application is null"
                    ),
                    state,
                    None,
                    requestPos
                  )
                )
              )
            case Some(annotationName) =>
              val rawName =
                SyntacticAnnotationIdentity.fromTree(draft.rawApplication)
                  .map(_.value)
              val rawNameMatches =
                rawName.exists: value =>
                  value == annotationName ||
                    annotationName.endsWith(s".$value")
              if !rawNameMatches then
                Left(
                  List(
                    furtherExpansionDiagnostic(
                      "FURTHER_EXPANSION_MALFORMED_REQUEST",
                      List(
                        "handler" -> s"@$annotationName",
                        "requestedBy" -> generator.canonicalHandlerIdentity,
                        "detail" -> s"raw request names @${rawName.getOrElse("<unrecognized>")}"
                      ),
                      state,
                      None,
                      requestPos
                    )
                  )
                )
              else
                available.find(_.annotationName == annotationName) match
                  case None =>
                    Left(
                      List(
                        furtherExpansionDiagnostic(
                          "FURTHER_EXPANSION_UNKNOWN_HANDLER",
                          List(
                            "handler" -> s"@$annotationName",
                            "requestedBy" -> generator.canonicalHandlerIdentity,
                            "detail" -> "no canonical handler is available in the captured registry"
                          ),
                          state,
                          None,
                          requestPos
                        )
                      )
                    )
                  case Some(requested)
                      if requested.compositionPolicy != CompositionPolicy.SourceOrdered =>
                    Left(
                      List(
                        furtherExpansionDiagnostic(
                          "FURTHER_EXPANSION_STANDALONE_HANDLER",
                          List(
                            "handler" -> s"@$annotationName",
                            "requestedBy" -> generator.canonicalHandlerIdentity,
                            "detail" -> "requested handler declares StandaloneOnly"
                          ),
                          state,
                          None,
                          requestPos
                        )
                      )
                    )
                  case Some(requested) =>
                    Right(
                      resolved :+ FurtherExpansionRequest(
                        annotationName = annotationName,
                        canonicalHandlerIdentity =
                          requested.canonicalHandlerIdentity,
                        rawApplication = draft.rawApplication,
                        requestedBy = generator.canonicalHandlerIdentity
                      )
                    )

  private def drainFurtherExpansionQueue(
      initialState: CompositionState,
      lineage: TransactionTargetId,
      topLevel: TopLevelRewriteContext,
      externalExpanders: List[AnnotationExpander],
      originalSourceAnnotations: List[Tree]
  )(using Context, ExplicitImportAnnotationIdentityResolver)
      : Either[(List[ExpansionDiagnostic], TypeDef), CompositionState] =
    val available = expanders(externalExpanders)

    def loop(
        state: CompositionState
    ): Either[(List[ExpansionDiagnostic], TypeDef), CompositionState] =
      state.generatedQueue match
        case Nil => Right(state)
        case request :: remainingQueue =>
          val requestPos =
            Option(request.rawApplication)
              .map(_.sourcePos)
              .filter(_.span.exists)
              .getOrElse(state.currentClass.sourcePos)
          val currentTrace =
            FurtherExpansionTraceEntry(
              step = state.nextTraceStep,
              provenance = "generated/delegated",
              annotationName = request.annotationName,
              canonicalHandlerIdentity = request.canonicalHandlerIdentity,
              targetName = state.currentClass.name.toString,
              requestedBy = Some(request.requestedBy)
            )
          val effectiveState =
            FurtherExpansionEffectiveState(
              lineage = lineage,
              canonicalHandlerIdentity = request.canonicalHandlerIdentity,
              requestSyntax = request.syntax,
              targetState = currentTargetStructure(state)
            )

          if state.seenGeneratedStates.contains(effectiveState) then
            Left(
              (
                List(
                  furtherExpansionDiagnostic(
                    "FURTHER_EXPANSION_REPEATED_STATE",
                    List(
                      "handler" -> s"@${request.annotationName}",
                      "requestedBy" -> request.requestedBy,
                      "detail" -> "exact position-independent request/target state repeated"
                    ),
                    state,
                    Some(currentTrace),
                    requestPos
                  )
                ),
                state.currentClass
              )
            )
          else if state.generatedSteps >= FurtherExpansionStepBudget then
            Left(
              (
                List(
                  furtherExpansionDiagnostic(
                    "FURTHER_EXPANSION_STEP_BUDGET",
                    List(
                      "budget" -> FurtherExpansionStepBudget.toString,
                      "lastRequest" -> s"@${request.annotationName}",
                      "handler" -> s"@${request.annotationName}",
                      "detail" -> "private generated/delegated step budget exceeded"
                    ),
                    state,
                    Some(currentTrace),
                    requestPos
                  )
                ),
                state.currentClass
              )
            )
          else
            available.find: candidate =>
              candidate.annotationName == request.annotationName &&
                candidate.canonicalHandlerIdentity ==
                  request.canonicalHandlerIdentity
            match
              case None =>
                Left(
                  (
                    List(
                      furtherExpansionDiagnostic(
                        "FURTHER_EXPANSION_UNKNOWN_HANDLER",
                        List(
                          "handler" -> s"@${request.annotationName}",
                          "requestedBy" -> request.requestedBy,
                          "detail" -> "canonical handler disappeared from the captured registry"
                        ),
                        state,
                        Some(currentTrace),
                        requestPos
                      )
                    ),
                    state.currentClass
                  )
                )
              case Some(expander) =>
                preExpansionAdmission(state.currentClass, List(expander)) match
                  case Some(diagnostic) =>
                    Left(
                      (
                        appendFurtherExpansionTrace(
                          List(diagnostic),
                          state,
                          currentTrace,
                          request
                        ),
                        state.currentClass
                      )
                    )
                  case None =>
                    val input =
                      ExpansionInput(
                        annotatedClass = state.currentClass,
                        existingCompanion =
                          if expander.consumesExistingCompanion then
                            state.currentCompanion
                          else None,
                        topLevel = topLevel,
                        currentAnnotation = Some(request.rawApplication)
                      )
                    val executingState =
                      state
                        .copy(
                          generatedQueue = remainingQueue,
                          seenGeneratedStates =
                            state.seenGeneratedStates :+ effectiveState,
                          generatedSteps = state.generatedSteps + 1
                        )
                        .appendTrace(currentTrace)
                    expandAndValidate(
                      expander,
                      input,
                      knownAdditionalTrees = state.additionalTopLevelTrees,
                      originalSourceAnnotations = originalSourceAnnotations
                    ) match
                      case ValidatedExpansionResult.Rejected(
                            diagnostics,
                            fallback
                          ) =>
                        Left(
                          (
                            appendFurtherExpansionTrace(
                              diagnostics,
                              state,
                              currentTrace,
                              request
                            ),
                            fallback
                          )
                        )
                      case ValidatedExpansionResult.Expanded(trees, drafts) =>
                        validateGeneratedStep(
                          request,
                          expander,
                          state.currentClass,
                          externalExpanders,
                          trees
                        ) match
                          case Some(diagnostic) =>
                            Left(
                              (
                                appendFurtherExpansionTrace(
                                  List(diagnostic),
                                  state,
                                  currentTrace,
                                  request
                                ),
                                state.currentClass
                              )
                            )
                          case None =>
                            val updatedState =
                              updateCompositionState(executingState, trees)
                            resolveFurtherExpansionDrafts(
                              updatedState,
                              drafts,
                              expander,
                              externalExpanders,
                              requestPos
                            ) match
                              case Left(diagnostics) =>
                                Left((diagnostics, state.currentClass))
                              case Right(generatedRequests) =>
                                val madeNoProgress =
                                  currentTargetStructure(state) ==
                                    currentTargetStructure(updatedState) &&
                                    generatedRequests.exists: next =>
                                      next.canonicalHandlerIdentity ==
                                        request.canonicalHandlerIdentity &&
                                        next.syntax == request.syntax
                                if madeNoProgress then
                                  Left(
                                    (
                                      List(
                                        furtherExpansionDiagnostic(
                                          "FURTHER_EXPANSION_NO_PROGRESS",
                                          List(
                                            "handler" -> s"@${request.annotationName}",
                                            "requestedBy" -> request.requestedBy,
                                            "detail" -> "handler reproduced the same effective request without target progress"
                                          ),
                                          updatedState,
                                          None,
                                          requestPos
                                        )
                                      ),
                                      state.currentClass
                                    )
                                  )
                                else
                                  loop(
                                    updatedState.copy(
                                      generatedQueue =
                                        updatedState.generatedQueue ++ generatedRequests
                                    )
                                  )

    loop(initialState)

  private def currentTargetStructure(
      state: CompositionState
  ): PositionIndependentStructure =
    PositionIndependentStructure.trees(state.outputTrees)

  private def appendFurtherExpansionTrace(
      diagnostics: List[ExpansionDiagnostic],
      state: CompositionState,
      current: FurtherExpansionTraceEntry,
      request: FurtherExpansionRequest
  ): List[ExpansionDiagnostic] =
    val trace = renderFurtherExpansionTrace(state, Some(current))
    diagnostics.map: diagnostic =>
      diagnostic.copy(
        message =
          s"${diagnostic.message}; generated/delegated handler=@${request.annotationName} requestedBy=${request.requestedBy} trace=$trace"
      )

  private def furtherExpansionDiagnostic(
      category: String,
      fields: List[(String, String)],
      state: CompositionState,
      current: Option[FurtherExpansionTraceEntry],
      pos: SrcPos
  ): ExpansionDiagnostic =
    val renderedFields =
      fields.map((name, value) => s"$name=$value").mkString(" ")
    ExpansionDiagnostic(
      s"internal further-expansion failure: stage=further-expansion category=$category $renderedFields trace=${renderFurtherExpansionTrace(state, current)}",
      pos
    )

  private def renderFurtherExpansionTrace(
      state: CompositionState,
      current: Option[FurtherExpansionTraceEntry]
  ): String =
    (state.recentTrace ++ current.toVector)
      .takeRight(FurtherExpansionTraceLimit)
      .map(_.render)
      .mkString("[", " -> ", "]")

  private def expandAndValidate(
      expander: AnnotationExpander,
      input: ExpansionInput,
      knownAdditionalTrees: List[Tree] = Nil,
      originalSourceAnnotations: List[Tree] = Nil
  )(using Context): ValidatedExpansionResult =
    val captured =
      InternalFurtherExpansionRequests.capture(expander.expand(input))
    val loweredStructuredDrafts =
      if captured.drafts.nonEmpty && captured.structured.directives.nonEmpty then
        Left(
          InternalStructuredExpansionValidator.Violation(
            InternalStructuredExpansionValidator.Category.MixedAuthoring,
            "<mixed>",
            captured.structured.directives.head.rawApplication,
            "one handler invocation cannot mix direct R1 requests with structured R2 directives"
          )
        )
      else
        InternalStructuredExpansionValidator.lowerToR1(
          captured.structured.directives,
          originalSourceAnnotations
        )
    loweredStructuredDrafts match
      case Left(violation) =>
        val pos =
          Option(violation.rawApplication)
            .map(_.sourcePos)
            .filter(_.span.exists)
            .orElse(input.currentAnnotation.map(_.sourcePos).filter(_.span.exists))
            .getOrElse(input.annotatedClass.sourcePos)
        ValidatedExpansionResult.Rejected(
          List(
            ExpansionDiagnostic(
              s"internal structured R2 failure: stage=structured-r2-validation " +
                s"category=${violation.category match
                    case InternalStructuredExpansionValidator.Category.MixedAuthoring =>
                      "MIXED_INTERNAL_R1_R2_AUTHORING"
                    case InternalStructuredExpansionValidator.Category.DirectiveRejected =>
                      "STRUCTURED_R2_DIRECTIVE_REJECTED"
                  } " +
                s"handler=@${violation.annotationName} " +
                s"requestedBy=${expander.canonicalHandlerIdentity} detail=${violation.detail}",
              pos
            )
          ),
          input.annotatedClass
        )
      case Right(lowered) =>
        val capturedDrafts = captured.drafts ++ lowered
        validateCapturedExpansion(
          captured.value,
          capturedDrafts,
          expander,
          input,
          knownAdditionalTrees
        )

  private def validateCapturedExpansion(
      result: ExpansionResult,
      capturedDrafts: List[InternalFurtherExpansionRequests.Draft],
      expander: AnnotationExpander,
      input: ExpansionInput,
      knownAdditionalTrees: List[Tree]
  )(using Context): ValidatedExpansionResult =
    result match
      case ExpansionResult.Expanded(trees) =>
        val knownAdditionalNames =
          knownAdditionalTrees.collect:
            case typeDef: TypeDef => typeDef.name.toString
            case moduleDef: ModuleDef => moduleDef.name.toString
        RawExpansionOutputValidator
          .validate(
            RawExpansionOutputValidator.Input(
              currentPrimary = input.annotatedClass,
              knownTopLevelNames = input.topLevel.names ++ knownAdditionalNames,
              trees = trees
            )
          ) match
          case Some(violation) =>
            val detail =
              violation.diagnostic(
                expander.validationOrigin,
                input.annotatedClass.name.toString
              )
            ValidatedExpansionResult.Rejected(
              List(
                ExpansionDiagnostic(
                  expander.outputValidationDiagnostic(
                    "RAW_OUTPUT_INVARIANT",
                    detail
                  ),
                  DiagnosticPositionPolicy.mostSpecific(
                    input.currentAnnotation,
                    input.annotatedClass.sourcePos
                  )
                )
              ),
              input.annotatedClass
            )
          case None =>
            ValidatedExpansionResult.Expanded(trees, capturedDrafts)
      case ExpansionResult.Structured(output) =>
        val knownAdditionalNames =
          knownAdditionalTrees.collect:
            case typeDef: TypeDef => typeDef.name.toString
            case moduleDef: ModuleDef => moduleDef.name.toString
        StructuredExpansionOutputValidator.validate(
          StructuredExpansionOutputValidator.Input(
            currentPrimary = input.annotatedClass,
            knownTopLevelNames = input.topLevel.names ++ knownAdditionalNames,
            output = output
          )
        ) match
          case Left(violation) =>
            val detail =
              violation.diagnostic(
                expander.validationOrigin,
                expander.annotationName,
                input.annotatedClass.name.toString
              )
            ValidatedExpansionResult.Rejected(
              List(
                ExpansionDiagnostic(
                  expander.outputValidationDiagnostic(
                    violation.category,
                    detail
                  ),
                  DiagnosticPositionPolicy.mostSpecific(
                    input.currentAnnotation,
                    input.annotatedClass.sourcePos
                  )
                )
              ),
              input.annotatedClass
            )
          case Right(canonicalTrees) =>
            ValidatedExpansionResult.Expanded(
              canonicalTrees,
              capturedDrafts
            )
      case ExpansionResult.Rejected(diagnostics, fallback) =>
        ValidatedExpansionResult.Rejected(diagnostics, fallback)

  private def updateCompositionState(
      state: CompositionState,
      trees: List[Tree]
  )(using Context): CompositionState =
    state.copy(
      transaction = stageValidatedLegacy(
        state.transaction,
        trees,
        LegacyOppositeOmissionPolicy.RetainCurrent
      )
    )

  private def validateGeneratedStep(
      request: FurtherExpansionRequest,
      expander: AnnotationExpander,
      beforePrimary: TypeDef,
      externalExpanders: List[AnnotationExpander],
      trees: List[Tree]
  )(using Context, ExplicitImportAnnotationIdentityResolver)
      : Option[ExpansionDiagnostic] =
    val expected =
      HandledAnnotations.matchingAnnotationsInSourceOrder(
        beforePrimary,
        externalExpanders
      )
    trees.collectFirst:
      case typeDef: TypeDef if typeDef.name == beforePrimary.name => typeDef
    .flatMap: returnedPrimary =>
      val returnedAnnotations = Trees.mods(returnedPrimary).annotations
      val returnedHandled =
        HandledAnnotations.matchingAnnotationsInSourceOrder(
          returnedPrimary,
          externalExpanders,
          identityWitnesses = expected.map(_.annotation)
        )
      val detail =
        expected.iterator
          .map: preserved =>
            val occurrences =
              returnedAnnotations.count(_ eq preserved.annotation)
            (preserved, occurrences)
          .collectFirst:
            case (preserved, 0) =>
              s"generated/delegated composition contract violation: source handled annotation @${preserved.expander.annotationName} was not preserved by identity"
            case (preserved, occurrences) if occurrences != 1 =>
              s"generated/delegated composition contract violation: source handled annotation @${preserved.expander.annotationName} was preserved $occurrences times instead of exactly once"
          .orElse:
            val positions = expected.map: preserved =>
              returnedAnnotations.indexWhere(_ eq preserved.annotation)
            if positions != positions.sorted then
              Some(
                "generated/delegated composition contract violation: preserved source handled annotations changed relative order"
              )
            else
              returnedHandled.iterator
                .filterNot: returned =>
                  expected.exists(preserved =>
                    preserved.annotation eq returned.annotation
                  )
                .map: unexpected =>
                  s"generated/delegated composition contract violation: reason=NEW_UNEXPECTED_HANDLED_ANNOTATION " +
                    s"new unexpected handled annotation @${unexpected.expander.annotationName}"
                .nextOption()

      detail.map: value =>
        ExpansionDiagnostic(
          expander.outputValidationDiagnostic(
            "COMPOSITION_ANNOTATION_PRESERVATION",
            value
          ),
          Option(request.rawApplication)
            .map(_.sourcePos)
            .filter(_.span.exists)
            .getOrElse(beforePrimary.sourcePos)
        )

  private def validateCompositionStep(
      current: MatchingAnnotation,
      consumed: List[MatchingAnnotation],
      later: List[MatchingAnnotation],
      externalExpanders: List[AnnotationExpander],
      trees: List[Tree]
  )(using Context, ExplicitImportAnnotationIdentityResolver): Option[ExpansionDiagnostic] =
    val returnedPrimary =
      trees.collectFirst:
        case typeDef: TypeDef => typeDef
    returnedPrimary.flatMap: primary =>
      val returnedAnnotations = Trees.mods(primary).annotations
      val currentOccurrences = returnedAnnotations.count(_ eq current.annotation)
      val detail =
        if currentOccurrences != 0 then
          Some(
            s"composition contract violation: current handled annotation was not consumed by identity; occurrences=$currentOccurrences"
          )
        else
          later.iterator
            .map: expected =>
              val occurrences = returnedAnnotations.count(_ eq expected.annotation)
              (expected, occurrences)
            .collectFirst:
              case (expected, 0) =>
                s"composition contract violation: later handled annotation @${expected.expander.annotationName} was not preserved by identity"
              case (expected, occurrences) if occurrences != 1 =>
                s"composition contract violation: later handled annotation @${expected.expander.annotationName} was preserved $occurrences times instead of exactly once"
            .orElse:
              val positions = later.map: expected =>
                returnedAnnotations.indexWhere(_ eq expected.annotation)
              if positions != positions.sorted then
                Some("composition contract violation: later handled annotations changed relative source order")
              else
                val returnedHandled =
                  HandledAnnotations.matchingAnnotationsInSourceOrder(
                    primary,
                    externalExpanders,
                    identityWitnesses =
                      (consumed ++ (current :: later)).map(_.annotation)
                  )
                returnedHandled.iterator
                  .filterNot: returned =>
                    later.exists(expected => expected.annotation eq returned.annotation)
                  .map: unexpected =>
                    val unexpectedName = unexpected.expander.annotationName
                    if unexpectedName == current.expander.annotationName then
                      s"composition contract violation: reason=RECONSTRUCTED_CURRENT_HANDLED_ANNOTATION " +
                        s"unexpected reconstructed current handled annotation @$unexpectedName"
                    else if consumed.exists(_.expander.annotationName == unexpectedName) then
                      s"composition contract violation: reason=REINTRODUCED_CONSUMED_HANDLED_ANNOTATION " +
                        s"reintroduced already-consumed handled annotation @$unexpectedName"
                    else if later.exists(_.expander.annotationName == unexpectedName) then
                      s"composition contract violation: reason=EXTRA_RECONSTRUCTED_LATER_HANDLED_DUPLICATE " +
                        s"unexpected duplicate/reconstructed later handled annotation @$unexpectedName"
                    else
                      s"composition contract violation: reason=NEW_UNEXPECTED_HANDLED_ANNOTATION " +
                        s"new unexpected handled annotation @$unexpectedName"
                  .nextOption()

      detail.map: value =>
        ExpansionDiagnostic(
          current.expander.outputValidationDiagnostic(
            "COMPOSITION_ANNOTATION_PRESERVATION",
            value
          ),
          current.annotation.sourcePos
        )

  private def compositionAdmission(
      typeDef: TypeDef,
      annotations: List[MatchingAnnotation]
  )(using Context): Option[ExpansionDiagnostic] =
    if annotations.size <= 1 then None
    else
      val namesInSourceOrder =
        annotations.map(value => s"@${value.expander.annotationName}").mkString(", ")
      annotations.find(_.expander.compositionPolicy == CompositionPolicy.StandaloneOnly) match
        case Some(standalone) =>
          Some(
            ExpansionDiagnostic(
              s"composition admission failure: stage=admission category=STANDALONE_COMPOSITION_PARTICIPANT " +
                s"annotations=$namesInSourceOrder (source order) detail=@${standalone.expander.annotationName} declares StandaloneOnly",
              typeDef.sourcePos
            )
          )
        case None => None

  private def spliceExpansionResult(
      result: ValidatedExpansionResult,
      remainingStats: List[Tree],
      topLevel: TopLevelRewriteContext,
      externalExpanders: List[AnnotationExpander],
      restoredCompanionOnRejection: Option[ModuleDef] = None
  )(using Context, ExplicitImportAnnotationIdentityResolver): List[Tree] =
    result match
      case ValidatedExpansionResult.Expanded(trees, _) =>
        trees ++ rewritePackageStats(remainingStats, topLevel, externalExpanders)
      case ValidatedExpansionResult.Rejected(diagnostics, fallback) =>
        diagnostics.foreach(reportDiagnostic)
        val restoredPrimary = HandledAnnotations.strip(fallback, externalExpanders)
        restoredPrimary ::
          restoredCompanionOnRejection.toList ++
          rewritePackageStats(remainingStats, topLevel, externalExpanders)

  private def reportDiagnostic(diagnostic: ExpansionDiagnostic)(using Context): Unit =
    // MAY DEPEND ON SCALA VERSION
    // `report.error` is the smallest compiler reporting hook used here; keep this
    // internal until the diagnostic contract is better exercised across plugin phases.
    report.error(diagnostic.message, diagnostic.pos)

  private def reportUnsupportedTarget(
      tree: Tree,
      targetDescription: String,
      matching: List[AnnotationExpander]
  )(using Context): Unit =
    val annotationLabel = HandledAnnotations.annotationLabel(matching)
    val verb = if matching.size == 1 then "supports" else "support"
    val targetSummary =
      matching match
        case expander :: Nil
            if expander.targetAdmissionProfile == TargetAdmissionProfile.RestrictedGenericTraitApply =>
          "the restricted top-level generic trait envelope"
        case expander :: Nil
            if expander.targetAdmissionProfile == TargetAdmissionProfile.TwoUpperBoundedGenericTrait =>
          "the two-upper-bounded-parameter top-level trait envelope"
        case expander :: Nil
            if expander.targetAdmissionProfile == TargetAdmissionProfile.PlainZeroParameterTrait =>
          "the plain zero-parameter top-level trait envelope"
        case expander :: Nil
            if expander.targetAdmissionProfile == TargetAdmissionProfile.RestrictedOrTwoUpperBoundedGenericTrait =>
          "either the one-unbounded-parameter restricted trait shape or the two-upper-bounded-parameter trait shape"
        case _ => "top-level classes"
    reportDiagnostic(
      ExpansionDiagnostic(
        s"$annotationLabel currently $verb only $targetSummary; unsupported target `$targetDescription`",
        tree.sourcePos
      )
    )

  private def rejectUnsupportedTarget(
      typeDef: TypeDef,
      targetDescription: String,
      matching: List[AnnotationExpander],
      externalExpanders: List[AnnotationExpander]
  )(using Context, ExplicitImportAnnotationIdentityResolver): TypeDef =
    reportUnsupportedTarget(typeDef, targetDescription, matching)
    HandledAnnotations.strip(typeDef, externalExpanders)

  private def rejectUnsupportedTarget(
      moduleDef: ModuleDef,
      targetDescription: String,
      matching: List[AnnotationExpander],
      externalExpanders: List[AnnotationExpander]
  )(using Context, ExplicitImportAnnotationIdentityResolver): ModuleDef =
    reportUnsupportedTarget(moduleDef, targetDescription, matching)
    HandledAnnotations.strip(moduleDef, externalExpanders)

  private def isSupportedTopLevelTarget(
      typeDef: TypeDef,
      matching: List[AnnotationExpander]
  )(using Context): Boolean =
    PrimaryRole.fromLegacyTypeDef(typeDef).toOption.exists:
      case _: PrimaryRole.ClassPrimary => true
      case _: PrimaryRole.TraitPrimary =>
        matching.nonEmpty && matching.forall: expander =>
          LegacyAdmission
            .forProfile(expander.targetAdmissionProfile)
            .kinds
            .contains(TargetKind.Trait)
      case _: PrimaryRole.ObjectPrimary => false

  private def preExpansionAdmission(
      typeDef: TypeDef,
      matching: List[AnnotationExpander]
  )(using Context): Option[ExpansionDiagnostic] =
    AnnotatedClassAdmission.decode(typeDef) match
      case Left(rejection) =>
        Some(ExpansionDiagnostic(rejection.message, rejection.pos))
      case Right(view) =>
        val labels = HandledAnnotations.annotationLabel(matching)
        val profiles = matching.map(_.targetAdmissionProfile).distinct
        val profileRejection =
          profiles match
            case profile :: Nil =>
              targetProfileRejection(view, labels, profile)
            case _ =>
              matching.iterator
                .flatMap: expander =>
                  targetProfileRejection(
                    view,
                    s"@${expander.annotationName}",
                    expander.targetAdmissionProfile
                  )
                .nextOption()
        profileRejection
          .orElse:
            matching.iterator
              .flatMap(_.admissionRejection(view))
              .nextOption()
          .map(rejection => ExpansionDiagnostic(rejection.message, rejection.pos))

  private def targetProfileRejection(
      view: AnnotatedClassView,
      annotationLabel: String,
      profile: TargetAdmissionProfile
  ): Option[AnnotatedClassAdmission.Rejection] =
    profile match
      case TargetAdmissionProfile.CommonClassOnly =>
        AnnotatedClassAdmission.commonRejection(view, annotationLabel)
      case TargetAdmissionProfile.RestrictedGenericTraitApply =>
        AnnotatedClassAdmission.restrictedGenericTraitApplyRejection(
          view,
          annotationLabel
        )
      case TargetAdmissionProfile.TwoUpperBoundedGenericTrait =>
        AnnotatedClassAdmission.twoUpperBoundedGenericTraitRejection(
          view,
          annotationLabel
        )
      case TargetAdmissionProfile.PlainZeroParameterTrait =>
        AnnotatedClassAdmission.plainZeroParameterTraitRejection(
          view,
          annotationLabel
        )
      case TargetAdmissionProfile.RestrictedOrTwoUpperBoundedGenericTrait =>
        AnnotatedClassAdmission.restrictedOrTwoUpperBoundedGenericTraitRejection(
          view,
          annotationLabel
        )

  private def unsupportedTypeDefTarget(typeDef: TypeDef)(using Context): String =
    val prefix =
      if Trees.mods(typeDef).is(Trait) then "trait"
      else if Trees.mods(typeDef).is(Enum) then "enum"
      else "type"
    s"$prefix ${typeDef.name}"

  private def stripNestedHandledAnnotations(
      tree: Tree,
      externalExpanders: List[AnnotationExpander]
  )(using Context, ExplicitImportAnnotationIdentityResolver): Tree =
    tree match
      case typeDef: TypeDef =>
        typeDef.rhs match
          case template: Template =>
            val matching = HandledAnnotations.matchingExpanders(typeDef, externalExpanders)
            if matching.nonEmpty then
              val family = if Trees.mods(typeDef).is(Trait) then "trait" else "class"
              reportUnsupportedTarget(typeDef, s"nested $family ${typeDef.name}", matching)
            val rewrittenTemplate =
              cpy.Template(template)(
                template.constr,
                template.parentsOrDerived(using summon[Context]),
                template.derived,
                template.self,
                template.body(using summon[Context]).map(stripNestedHandledAnnotations(_, externalExpanders))
              )
            HandledAnnotations.strip(cpy.TypeDef(typeDef)(typeDef.name, rewrittenTemplate), externalExpanders)
          case _ =>
            HandledAnnotations.strip(typeDef, externalExpanders)
      case moduleDef: ModuleDef =>
        val template = moduleDef.impl
        val rewrittenTemplate =
          cpy.Template(template)(
            template.constr,
            template.parentsOrDerived(using summon[Context]),
            template.derived,
            template.self,
            template.body(using summon[Context]).map(stripNestedHandledAnnotations(_, externalExpanders))
          )
        HandledAnnotations.strip(cpy.ModuleDef(moduleDef)(moduleDef.name, rewrittenTemplate), externalExpanders)
      case defDef: DefDef =>
        val rewrittenRhs =
          new UntypedTreeMap:
            override def transform(tree: Tree)(using Context): Tree =
              tree match
                case localClass: TypeDef if localClass.isClassDef =>
                  val matching =
                    HandledAnnotations.matchingExpanders(
                      localClass,
                      externalExpanders
                    )
                  if matching.nonEmpty then
                    val family = if Trees.mods(localClass).is(Trait) then "trait" else "class"
                    reportUnsupportedTarget(localClass, s"local $family ${localClass.name}", matching)
                  super.transform(
                    HandledAnnotations.strip(localClass, externalExpanders)
                  )
                case other =>
                  super.transform(other)
          .transform(defDef.rhs)
        cpy.DefDef(defDef)(
          defDef.name,
          defDef.paramss,
          defDef.tpt,
          rewrittenRhs
        )
      case other =>
        other

  private def takeExistingCompanion(
      className: TypeName,
      stats: List[Tree]
  )(using Context): (Option[ModuleDef], List[Tree]) =
    stats match
      case Nil =>
        (None, Nil)
      case (moduleDef: ModuleDef) :: rest if moduleDef.name == className.toTermName =>
        (Some(moduleDef), rest)
      case stat :: rest =>
        val (companion, remainingStats) = takeExistingCompanion(className, rest)
        (companion, stat :: remainingStats)
