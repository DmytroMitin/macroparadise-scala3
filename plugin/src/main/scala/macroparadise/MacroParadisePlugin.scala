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
  ExpansionTargetView,
  ExpansionHandler as ExternalExpansionHandler,
  ExpansionInput as ExternalExpansionInput,
  ExpansionOutcome as ExternalExpansionOutcome,
  ExpansionTarget as ExternalExpansionTarget
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
      handlers: List[LoadedExternalHandlerContract],
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
      explicit: List[LoadedExternalHandlerContract],
      handlerClasspath: List[String],
      handlerLoader: ClassLoader,
      metadataReader: AnnotationMetadataReader,
      metadataHandlerRunCache: ExternalHandlerContractRunCache,
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
              metadataHandlerRunCache = ExternalHandlerContractRunCache(
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
      metadataHandlerRunCache = ExternalHandlerContractRunCache(Nil),
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
            loaded.explicit.exists(_.annotationName == request.annotationName)
          val hasLegacySimpleExplicit =
            !hasCanonicalExplicit && request.importedShortName.exists: shortName =>
              loaded.explicit.exists(_.annotationName == shortName)
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
                            !handler.annotationName.contains('.') =>
                        legacySimpleRequests += request
                        shortName
                      case _ => lookupAnnotationName
                  ExternalHandlerContractBinding.validate(
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
                          List(binding)
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
      handlers: List[LoadedExternalHandlerContract]
  )(using Context): List[LoadedExternalHandlerContract] =
    val seenExternal = scala.collection.mutable.Set.empty[String]
    val uniqueHandlers = List.newBuilder[LoadedExternalHandlerContract]

    handlers.foreach: handler =>
      val annotationName = handler.annotationName
      if BuiltInAnnotationNames.contains(annotationName) then
        report.error(
          ExternalHandlerDiagnostics.render(
            ExternalHandlerDiagnostics.Stage.Loading,
            "DUPLICATE_HANDLER_REGISTRATION",
            "annotation" -> s"@$annotationName",
            "handler" -> handler.handlerClassName,
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
            "handler" -> handler.handlerClassName,
            "conflict" -> "another external handler",
            "detail" -> s"duplicate annotation handler registration for `$annotationName`"
          )
        )
      else
        seenExternal += annotationName
        uniqueHandlers += handler

    uniqueHandlers.result()

  private final class InvalidMetadataAnnotationExpander(val annotationName: String) extends ExternalExpansionHandler:
    def expand(input: ExternalExpansionInput)(using Context): ExternalExpansionOutcome =
      ExternalExpansionOutcome.Rejected(
        List(paradise3.api.ExpansionDiagnostic("metadata handler is unavailable", input.currentAnnotation.sourcePos))
      )

  private def invalidMetadataHandler(annotationName: String): LoadedExternalHandler =
    val instance = InvalidMetadataAnnotationExpander(annotationName)
    LoadedExternalHandler(
      instance,
      ExternalHandlerDescriptor(
        handlerClassName = instance.getClass.getName,
        annotationName = annotationName
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
      val handlerClass = loader.loadClass(deferred.handlerClassName)
      ExternalHandlerClassClassification.classify(handlerClass) match
        case ExternalHandlerClassClassification.Handler =>
          val expander = handlerClass.getConstructor().newInstance()
            .asInstanceOf[ExternalExpansionHandler]
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
        case ExternalHandlerClassClassification.Invalid =>
          report.error(
            s"experimental same-module handler `${deferred.handlerClassName}` does not implement ExpansionHandler"
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
  )(using Context): Option[LoadedExternalHandlerContract] =
    try
      val handlerClass = loader.loadClass(className)
      ExternalHandlerClassClassification.classify(handlerClass) match
        case ExternalHandlerClassClassification.Handler =>
          val instance = handlerClass.getConstructor().newInstance()
          captureDescriptor(
            instance.asInstanceOf[ExternalExpansionHandler],
            loader
          )
        case ExternalHandlerClassClassification.Invalid =>
          report.error(
            ExternalHandlerDiagnostics.render(
              ExternalHandlerDiagnostics.Stage.Loading,
              "INVALID_HANDLER_INTERFACE",
              "handler" -> className,
              "detail" -> "external handler does not implement ExpansionHandler"
            )
          )
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
      expander: ExternalExpansionHandler,
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
  private var activeExpansionBudget: Option[Int] = None
  override val phaseName = "paradiseGen"
  override val description =
    "expands narrow top-level built-in annotations before typer"

  override def runsAfter = Set("parser")
  override def runsBefore = Set("typer")

  override def runOn(units: List[CompilationUnit])(using ctx: Context): List[CompilationUnit] =
    ExpansionBudget.parse(options) match
      case Left(diagnostic) =>
        report.error(diagnostic)
        units
      case Right(budget) =>
        RunLocalResourceScope.use(ExternalHandlerLoading.load(options)): loaded =>
          require(
            activeExternalHandlers.isEmpty && activeExpansionBudget.isEmpty,
            "macroparadise phase run-local state is already active"
          )
          activeExternalHandlers = Some(loaded)
          activeExpansionBudget = Some(budget)
          try super.runOn(units)
          finally
            activeExpansionBudget = None
            activeExternalHandlers = None

  private def externalHandlers: ExternalHandlerLoading.LoadedHandlers =
    activeExternalHandlers.getOrElse:
      throw IllegalStateException(
        "macroparadise handler state is unavailable outside PluginPhase.runOn"
      )

  private def expansionBudget: Int =
    activeExpansionBudget.getOrElse:
      throw IllegalStateException(
        "macroparadise expansion budget is unavailable outside PluginPhase.runOn"
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
          expansionBudget
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
            expansionBudget
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
    val apiLoader = classOf[ExternalExpansionHandler].getClassLoader
    val handlerApiLoader =
      if handler == null then "not-loaded"
      else
        val apiClassFromHandlerLoader =
          handler.instance.getClass.getClassLoader.loadClass(
            classOf[ExternalExpansionHandler].getName
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
