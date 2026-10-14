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

private[macroparadise] object BoundedSameModuleSelection:
  import untpd.*

  private final case class ImportScope(
      explicit: Map[String, Set[String]],
      wildcardPrefixes: Set[String]
  )

  private object ImportScope:
    val Empty: ImportScope = ImportScope(Map.empty, Set.empty)

  private final case class ConfiguredAnnotations(
      direct: Vector[String],
      unsupported: Vector[String]
  ):
    def all: Vector[String] = (direct ++ unsupported).distinct.sorted

  def select(
      tree: untpd.Tree,
      configuredAnnotationNames: Vector[String]
  )(using Context): Either[String, Option[String]] =
    val found = configuredAnnotations(tree, configuredAnnotationNames)
    if found.unsupported.nonEmpty then
      Left(
        s"bounded same-module external bindings require direct-qualified consumer annotations; unsupported configured identities: ${found.unsupported.mkString(", ")}"
      )
    else
      found.direct match
        case Vector() => Right(None)
        case Vector(annotationName) => Right(Some(annotationName))
        case names =>
          Left(
            s"multiple configured same-module annotations select one source: ${names.mkString(", ")}"
          )

  private[macroparadise] def configuredAnnotationNames(
      tree: untpd.Tree,
      configuredAnnotationNames: Vector[String]
  )(using Context): Either[String, Vector[String]] =
    val found = configuredAnnotations(tree, configuredAnnotationNames)
    if found.unsupported.nonEmpty then
      Left(
        s"bounded same-module external bindings require direct-qualified consumer annotations; unsupported configured identities: ${found.unsupported.mkString(", ")}"
      )
    else Right(found.direct)

  private def configuredAnnotations(
      tree: untpd.Tree,
      configuredAnnotationNames: Vector[String]
  )(using Context): ConfiguredAnnotations =
    val configured = configuredAnnotationNames.toSet
    val configuredByLeaf = configuredAnnotationNames.groupBy(_.split('.').last)
    val direct = Vector.newBuilder[String]
    val unsupported = Vector.newBuilder[String]

    def record(annotation: untpd.Tree, imports: ImportScope): Unit =
      SyntacticAnnotationIdentity.fromTree(annotation).foreach: identity =>
        if identity.isQualified then
          if configured(identity.value) then direct += identity.value
        else
          val explicitBindings = imports.explicit.get(identity.value)
          val fromExplicit = explicitBindings.getOrElse(Set.empty).filter(configured)
          val fromLeaf =
            if explicitBindings.isDefined then Vector.empty
            else configuredByLeaf.getOrElse(identity.value, Vector.empty)
          val fromWildcard =
            if explicitBindings.isDefined then Set.empty
            else
              imports.wildcardPrefixes
                .map(prefix => s"$prefix.${identity.value}")
                .filter(configured)
          (fromLeaf ++ fromExplicit ++ fromWildcard).foreach(unsupported += _)

    def recordDefinitions(tree: untpd.Tree, imports: ImportScope): Unit =
      tree match
        case value: TypeDef => Trees.mods(value).annotations.foreach(record(_, imports))
        case value: ModuleDef => Trees.mods(value).annotations.foreach(record(_, imports))
        case _ => ()

    def analyzeStats(stats: List[untpd.Tree], initial: ImportScope): Unit =
      var imports = initial
      stats.foreach:
        case importTree: Import => imports = addImport(imports, importTree)
        case nestedPackage: PackageDef => analyzeStats(nestedPackage.stats, imports)
        case other => recordDefinitions(other, imports)

    tree match
      case pkg: PackageDef => analyzeStats(pkg.stats, ImportScope.Empty)
      case other => recordDefinitions(other, ImportScope.Empty)

    ConfiguredAnnotations(
      direct.result().distinct.sorted,
      unsupported.result().distinct.sorted
    )

  private def addImport(
      scope: ImportScope,
      importTree: Import
  ): ImportScope =
    referenceSegments(importTree.expr) match
      case None => scope
      case Some(prefix) if importTree.selectors.isEmpty =>
        val canonical = prefix.mkString(".")
        addExplicit(scope, prefix.last, canonical)
      case Some(prefix) =>
        importTree.selectors.foldLeft(scope): (current, selector) =>
          if selector.isWildcard then
            current.copy(wildcardPrefixes = current.wildcardPrefixes + prefix.mkString("."))
          else if selector.isGiven || selector.isUnimport then current
          else
            val canonical = (prefix :+ selector.name.toString).mkString(".")
            addExplicit(current, selector.rename.toString, canonical)

  private def addExplicit(
      scope: ImportScope,
      localName: String,
      canonicalName: String
  ): ImportScope =
    scope.copy(
      explicit = scope.explicit.updated(
        localName,
        scope.explicit.getOrElse(localName, Set.empty) + canonicalName
      )
    )

  private def referenceSegments(tree: untpd.Tree): Option[List[String]] =
    tree match
      case Ident(name) => Some(List(name.toString))
      case Select(qualifier, name) =>
        referenceSegments(qualifier).map(_ :+ name.toString)
      case _ => None

  def introducedCrossBinding(
      original: untpd.Tree,
      rewritten: untpd.Tree,
      selectedAnnotationName: String,
      configuredAnnotationNames: Vector[String]
  )(using Context): Option[String] =
    val before = configuredAnnotations(original, configuredAnnotationNames).all.toSet
    configuredAnnotations(rewritten, configuredAnnotationNames).all
      .find(annotationName => annotationName != selectedAnnotationName && !before(annotationName))

  private[macroparadise] def directQualifiedAnnotationNames(
      tree: untpd.Tree,
      configuredAnnotationNames: Vector[String]
  )(using Context): Vector[String] =
    configuredAnnotations(tree, configuredAnnotationNames).direct

  private[macroparadise] def producerCollisionDiagnostic(
      consumerPath: String,
      producerSourceIdentities: Vector[DeferredSameModuleHandlerSupport.SourceIdentity]
  ): Option[String] =
    val normalizedConsumerPath = DeferredSameModuleHandlerSupport.normalizePath(consumerPath)
    val collisions = producerSourceIdentities
      .filter(
        DeferredSameModuleHandlerSupport.pathMatchesIdentity(
          normalizedConsumerPath,
          _
        )
      )
      .map(_.value)
      .distinct
      .sorted
    Option.when(collisions.nonEmpty)(
      s"configured same-module producer source cannot also be a consumer: $normalizedConsumerPath; matched producer identities: ${collisions.mkString(", ")}"
    )

private[macroparadise] object BoundedSameModulePreflight:
  import DeferredSameModuleHandlerSupport.*

  final case class Producer(
      annotationName: String,
      handlerClassName: String,
      sourceIdentity: SourceIdentity
  )

  def validate(
      units: List[CompilationUnit],
      producers: Vector[Producer],
      configuredAnnotationNames: Vector[String]
  )(using Context): Either[String, Vector[String]] =
    val sourcePaths = units.map(_.source.file.path)
    val ordered = producers.sortBy(producer => (
      producer.sourceIdentity.value,
      producer.annotationName,
      producer.handlerClassName
    ))
    ordered.foldLeft[Either[String, Vector[String]]](Right(Vector.empty)):
      case (acc, producer) =>
        acc.flatMap: diagnostics =>
          resolveDependency(producer.sourceIdentity, sourcePaths) match
            case DependencyResolution.Missing => Right(diagnostics)
            case DependencyResolution.Ambiguous(paths) =>
              Left(
                s"same-module producer source `${producer.sourceIdentity.value}` is ambiguous in the current run; matched: ${paths.mkString(", ")}"
              )
            case DependencyResolution.Unique(path) =>
              units.find(unit => normalizePath(unit.source.file.path) == path) match
                case None => Right(diagnostics)
                case Some(unit) =>
                  BoundedSameModuleSelection.configuredAnnotationNames(
                    unit.untpdTree,
                    configuredAnnotationNames
                  ) match
                    case Left(message) =>
                      Left(s"same-module producer source `$path`: $message")
                    case Right(requiredAnnotations) =>
                      val edges = requiredAnnotations.map: requiredAnnotation =>
                        s"UNSUPPORTED_SAME_MODULE_PRODUCER_EDGE producerSource=$path producerBinding=${producer.annotationName}/${producer.handlerClassName} requiresAnnotation=$requiredAnnotation detail=bounded same-module external bindings must be independent"
                      Right(diagnostics ++ edges)

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
      deferredSameModule: Vector[DeferredSameModuleHandler],
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
      deferredSameModule = Vector.empty,
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
  )(using Context): Vector[DeferredSameModuleHandler] =
    DeferredSameModuleHandlerSupport.parseConfigurations(options) match
      case Left(message) =>
        report.error(s"invalid experimental same-module configuration: $message")
        Vector.empty
      case Right(configurations) =>
        configurations.map: configuration =>
          DeferredSameModuleHandler(
            configuration.annotationName,
            configuration.handlerClassName,
            configuration.markerSourceIdentity,
            configuration.handlerSourceIdentity,
            configuration.sourceDigest
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
          try
            prevalidateBoundedProducers(units, loaded) match
              case true => super.runOn(units)
              case false => units
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
      case pair if pair.size == 2 => runBoundedPair(unit, pair)
      case Vector(deferred)
          if ParadiseTreeRewrite.containsTopLevelClassAnnotation(
            unit.untpdTree,
            deferred.annotationName
          ) =>
        handleDeferredConsumer(unit, deferred)
      case _ => rewriteOrdinary(unit)

  private def prevalidateBoundedProducers(
      units: List[CompilationUnit],
      loaded: ExternalHandlerLoading.LoadedHandlers
  )(using Context): Boolean =
    val pair = loaded.deferredSameModule
    if pair.size != 2 then true
    else
      val producers = pair.flatMap: deferred =>
        Vector(
          BoundedSameModulePreflight.Producer(
            deferred.annotationName,
            deferred.handlerClassName,
            deferred.markerSourceIdentity
          ),
          BoundedSameModulePreflight.Producer(
            deferred.annotationName,
            deferred.handlerClassName,
            deferred.handlerSourceIdentity
          )
        )
      val configured = pair.map(_.annotationName)
      BoundedSameModulePreflight.validate(units, producers, configured) match
        case Left(message) =>
          report.error(message)
          false
        case Right(diagnostics) if diagnostics.nonEmpty =>
          diagnostics.foreach(report.error(_))
          false
        case Right(_) => true

  private def runBoundedPair(
      unit: CompilationUnit,
      pair: Vector[ExternalHandlerLoading.DeferredSameModuleHandler]
  )(using Context): Unit =
    val configured = pair.map(_.annotationName)
    BoundedSameModuleSelection.select(unit.untpdTree, configured) match
      case Left(message) => report.error(message, unit.untpdTree.sourcePos)
      case Right(Some(annotationName)) =>
        val deferred = pair.find(_.annotationName == annotationName).get
        val producerSources = pair.flatMap(deferred =>
          Vector(deferred.markerSourceIdentity, deferred.handlerSourceIdentity)
        )
        BoundedSameModuleSelection.producerCollisionDiagnostic(
          unit.source.file.path,
          producerSources
        ) match
          case Some(message) => report.error(message, unit.untpdTree.sourcePos)
          case None => handleDeferredConsumer(unit, deferred)
      case Right(None) =>
        val imported = pair.filter(deferred =>
          ParadiseTreeRewrite.containsTopLevelClassAnnotation(
            unit.untpdTree,
            deferred.annotationName
          )
        ).map(_.annotationName).sorted
        if imported.nonEmpty then
          report.error(
            s"bounded same-module external bindings require direct-qualified consumer annotations; unsupported resolved identities: ${imported.mkString(", ")}",
            unit.untpdTree.sourcePos
          )
        else rewriteOrdinary(unit)

  private def rewriteOrdinary(unit: CompilationUnit)(using Context): Unit =
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
          val original = unit.untpdTree
          val rewritten = ParadiseTreeRewrite.rewriteUnit(
            unit,
            loadedForUnit,
            expansionBudget
          )
          val configured = externalHandlers.deferredSameModule.map(_.annotationName)
          val introduced =
            if configured.size == 2 then
              BoundedSameModuleSelection.introducedCrossBinding(
                original,
                rewritten,
                deferred.annotationName,
                configured
              )
            else None
          introduced match
            case Some(annotationName) =>
              report.error(
                s"unsupported same-module cross-binding expansion: handler `${deferred.handlerClassName}` for `${deferred.annotationName}` introduced configured annotation `$annotationName` in the same physical unit",
                original.sourcePos
              )
            case None => unit.untpdTree = rewritten
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
