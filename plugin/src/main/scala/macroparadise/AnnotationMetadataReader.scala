package macroparadise

import scala.quoted.Quotes
import scala.tasty.inspector.Inspector
import scala.tasty.inspector.Tasty
import scala.tasty.inspector.TastyInspector
import scala.util.control.NonFatal
import java.io.File
import java.lang.annotation.AnnotationFormatError
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.jar.JarFile
import scala.collection.JavaConverters.*
import scala.collection.mutable

private[macroparadise] trait AnnotationMetadataReader:
  def findExpanderClass(annotationName: String): MetadataLookupResult

private[macroparadise] enum MetadataLookupResult:
  case NotFound
  case Found(className: String)
  case Failed(message: String)

private[macroparadise] object AnnotationMetadataReader:
  def production(apiLoader: ClassLoader): AnnotationMetadataReader =
    production(apiLoader, Nil)

  def production(
      apiLoader: ClassLoader,
      structuredMetadataPaths: List[ValidatedStructuredMetadataPath]
  ): AnnotationMetadataReader =
    val trace = MetadataReaderTrace.fromSystemProperty()
    val explicitStructuredInputs =
      if structuredMetadataPaths.isEmpty then None
      else
        Some(
          ExplicitStructuredTastyInputs(
            paths = structuredMetadataPaths,
            dependencyClasspath =
              structuredMetadataPaths.map(_.value) :::
                apiClasspathFrom(classOf[paradise3.api.expander])
          )
        )
    reader(
      apiLoader,
      trace,
      explicitStructuredInputs
    )

  private def reader(
      apiLoader: ClassLoader,
      trace: MetadataReaderTrace,
      explicitStructuredInputs: Option[ExplicitStructuredTastyInputs]
  ): AnnotationMetadataReader =
    RuntimeFirstAnnotationMetadataReader(
      RuntimeAnnotationMetadataReader(apiLoader, trace),
      compatibility(apiLoader, trace, explicitStructuredInputs),
      trace
    )

  private def compatibility(
      apiLoader: ClassLoader,
      trace: MetadataReaderTrace,
      explicitStructuredInputs: Option[ExplicitStructuredTastyInputs]
  ): AnnotationMetadataReader =
    val fallback = TastyStringAnnotationMetadataReader(apiLoader)
    val structuredClasspath = apiClasspathFrom(classOf[paradise3.api.expander])
    val structured =
      // NEEDS VERIFICATION
      // Production always attempts structured lookup first, but packaged
      // scala3-tasty-inspector availability depends on the effective plugin
      // classpath. Structured success and controlled fallback success are both
      // valid; unavailable inspector classes must never crash compiler startup.
      try
        explicitStructuredInputs match
          case Some(inputs) =>
            TastyInspectorAnnotationMetadataReader.fromPaths(
              paths = inputs.paths,
              dependencyClasspath = inputs.dependencyClasspath
            )
          case None =>
            TastyInspectorAnnotationMetadataReader(structuredClasspath)
      catch
        case error: LinkageError =>
          UnavailableStructuredAnnotationMetadataReader(unavailableMessage(error))
        case NonFatal(error) =>
          UnavailableStructuredAnnotationMetadataReader(unavailableMessage(error))

    StructuredFirstAnnotationMetadataReader(
      structured,
      fallback,
      trace
    )

  private def apiClasspathFrom(anchor: Class[?]): List[String] =
    Option(anchor.getProtectionDomain)
      .flatMap(domain => Option(domain.getCodeSource))
      .flatMap(codeSource => Option(codeSource.getLocation))
      .map(url => File(url.toURI).getAbsolutePath)
      .toList

  private def unavailableMessage(error: Throwable): String =
    val message = Option(error.getMessage).getOrElse(error.getClass.getName)
    s"structured annotation metadata reader unavailable: $message"

private[macroparadise] final class UnavailableStructuredAnnotationMetadataReader(message: String) extends AnnotationMetadataReader:
  def findExpanderClass(annotationName: String): MetadataLookupResult =
    MetadataLookupResult.Failed(message)

private[macroparadise] final class RuntimeAnnotationMetadataReader(
    apiLoader: ClassLoader,
    trace: MetadataReaderTrace = MetadataReaderTrace.disabled
) extends AnnotationMetadataReader:
  private val externalCandidateCache = mutable.Map.empty[String, List[String]]

  def findExpanderClass(annotationName: String): MetadataLookupResult =
    val annotationClassName = exactOrLegacyClassName(annotationName)
    inspect(annotationClassName) match
      case found: MetadataLookupResult.Found => found
      case failure: MetadataLookupResult.Failed => failure
      case MetadataLookupResult.NotFound if annotationName.contains('.') =>
        MetadataLookupResult.NotFound
      case MetadataLookupResult.NotFound =>
        val candidates = externalCandidateCache.getOrElseUpdate(
          annotationName,
          externalAnnotationClassNames(annotationName)
        )
        val found = candidates.flatMap: candidate =>
          inspect(candidate) match
            case value: MetadataLookupResult.Found => Some(candidate -> value)
            case _ => None
        found match
          case (candidate, value) :: Nil =>
            trace.recordResolved("runtime-candidate", candidate, value)
            value
          case Nil => MetadataLookupResult.NotFound
          case many =>
            MetadataLookupResult.Failed(
              s"ambiguous runtime annotation metadata for `$annotationName`; candidates: ${many.map(_._1).mkString(", ")}"
            )

  private def exactOrLegacyClassName(annotationName: String): String =
    if annotationName.contains('.') then annotationName
    else s"paradise3.$annotationName"

  private def inspect(annotationClassName: String): MetadataLookupResult =
    try
      val carrierClass = Class.forName("paradise3.api.expander", false, apiLoader)
      if carrierClass ne classOf[paradise3.api.expander] then
        MetadataLookupResult.Failed(
          s"annotation metadata carrier loader mismatch for `$annotationClassName`"
        )
      else
        val annotationClass = Class.forName(annotationClassName, false, apiLoader)
        Option(annotationClass.getDeclaredAnnotation(classOf[paradise3.api.expander])) match
          case Some(metadata) =>
            val className = metadata.value().trim
            if className.nonEmpty then MetadataLookupResult.Found(className)
            else
              MetadataLookupResult.Failed(
                s"empty annotation metadata expander class name for `$annotationClassName`"
              )
          case None =>
            MetadataLookupResult.NotFound
    catch
      case _: ClassNotFoundException =>
        MetadataLookupResult.NotFound
      case error: AnnotationFormatError =>
        MetadataLookupResult.Failed(
          s"malformed runtime annotation metadata for `$annotationClassName`: ${errorMessage(error)}"
        )
      case error: LinkageError =>
        MetadataLookupResult.Failed(
          s"could not load runtime annotation metadata for `$annotationClassName`: ${errorMessage(error)}"
        )
      case NonFatal(error) =>
        MetadataLookupResult.Failed(
          s"could not inspect runtime annotation metadata for `$annotationClassName`: ${errorMessage(error)}"
        )

  private def externalAnnotationClassNames(annotationName: String): List[String] =
    val suffix = s"/$annotationName.class"
    val rootEntry = s"$annotationName.class"
    loaderUrls(apiLoader).flatMap: url =>
      try
        val file = File(url.toURI)
        if file.isDirectory then
          val stream = Files.walk(file.toPath)
          try
            stream.iterator.asScala
              .filter(Files.isRegularFile(_))
              .map(path => file.toPath.relativize(path).toString.replace(File.separatorChar, '/'))
              .filter(entry => entry == rootEntry || entry.endsWith(suffix))
              .filterNot(_.contains("$"))
              .map(_.stripSuffix(".class").replace('/', '.'))
              .toList
          finally stream.close()
        else if file.isFile && file.getName.endsWith(".jar") then
          val jar = JarFile(file)
          try
            jar.entries.asScala
              .map(_.getName)
              .filter(entry => entry == rootEntry || entry.endsWith(suffix))
              .filterNot(entry => entry.contains("$") || entry.startsWith("META-INF/versions/"))
              .map(_.stripSuffix(".class").replace('/', '.'))
              .toList
          finally jar.close()
        else Nil
      catch
        case NonFatal(_) => Nil
    .filterNot(_ == s"paradise3.$annotationName")
    .distinct
    .sorted

  private def loaderUrls(loader: ClassLoader): List[java.net.URL] =
    loader match
      case value: URLClassLoader => value.getURLs.toList ::: loaderUrls(value.getParent)
      case _ => Nil

  private def errorMessage(error: Throwable): String =
    Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getName)

private[macroparadise] final class RuntimeFirstAnnotationMetadataReader(
    runtime: AnnotationMetadataReader,
    compatibility: => AnnotationMetadataReader,
    trace: MetadataReaderTrace
) extends AnnotationMetadataReader:
  private lazy val compatibilityReader = compatibility

  def findExpanderClass(annotationName: String): MetadataLookupResult =
    val runtimeResult = runtime.findExpanderClass(annotationName)
    trace.record("runtime", annotationName, runtimeResult)
    runtimeResult match
      case found: MetadataLookupResult.Found =>
        found
      case MetadataLookupResult.NotFound =>
        compatibilityReader.findExpanderClass(annotationName)
      case runtimeFailure: MetadataLookupResult.Failed =>
        compatibilityReader.findExpanderClass(annotationName) match
          case found: MetadataLookupResult.Found =>
            found
          case MetadataLookupResult.NotFound =>
            runtimeFailure
          case compatibilityFailure: MetadataLookupResult.Failed =>
            compatibilityFailure

private[macroparadise] final class StructuredFirstAnnotationMetadataReader(
    structured: AnnotationMetadataReader,
    fallback: AnnotationMetadataReader,
    trace: MetadataReaderTrace
) extends AnnotationMetadataReader:
  def findExpanderClass(annotationName: String): MetadataLookupResult =
    val structuredResult = structured.findExpanderClass(annotationName)
    trace.record("structured", annotationName, structuredResult)
    structuredResult match
      case found: MetadataLookupResult.Found =>
        found
      case MetadataLookupResult.NotFound =>
        val fallbackResult = fallback.findExpanderClass(annotationName)
        trace.record("string", annotationName, fallbackResult)
        fallbackResult
      case structuredFailure: MetadataLookupResult.Failed =>
        val fallbackResult = fallback.findExpanderClass(annotationName)
        trace.record("string", annotationName, fallbackResult)
        fallbackResult match
          case found: MetadataLookupResult.Found =>
            found
          case MetadataLookupResult.NotFound =>
            MetadataLookupResult.NotFound
          case fallbackFailure: MetadataLookupResult.Failed =>
            fallbackFailure

private[macroparadise] final class MetadataReaderTrace private (path: Option[Path]):
  def record(readerName: String, annotationName: String, result: MetadataLookupResult): Unit =
    path.foreach: tracePath =>
      val annotationClassName =
        if annotationName.contains('.') then annotationName
        else s"paradise3.$annotationName"
      val line = s"$readerName $annotationClassName $result\n"
      try
        // ASSUMPTION
        // This system-property trace is a test-only observability hook, not a
        // public compiler-plugin option or stable diagnostic surface.
        Files.writeString(tracePath, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
      catch
        case NonFatal(_) => ()

  def recordResolved(readerName: String, annotationClassName: String, result: MetadataLookupResult): Unit =
    path.foreach: tracePath =>
      val line = s"$readerName $annotationClassName $result\n"
      try
        Files.writeString(tracePath, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
      catch
        case NonFatal(_) => ()

private[macroparadise] object MetadataReaderTrace:
  private val PropertyName = "macroparadise.metadataReaderTrace"

  def disabled: MetadataReaderTrace =
    MetadataReaderTrace(None)

  def fromSystemProperty(): MetadataReaderTrace =
    Option(System.getProperty(PropertyName)).map(_.trim).filter(_.nonEmpty) match
      case Some(path) => MetadataReaderTrace(Some(Path.of(path)))
      case None => disabled

private[macroparadise] final case class ExplicitStructuredTastyInputs(
    paths: List[ValidatedStructuredMetadataPath],
    dependencyClasspath: List[String]
)

private[macroparadise] object TastyInspectorAnnotationMetadataReader:
  def apply(apiClasspath: List[String]): TastyInspectorAnnotationMetadataReader =
    new TastyInspectorAnnotationMetadataReader(
      dependencyClasspath = apiClasspath,
      inputProvider = annotationName => inputsFromClasspath(annotationName, apiClasspath)
    )

  def explicit(
      tastyFiles: List[String],
      jars: List[String],
      dependencyClasspath: List[String]
  ): TastyInspectorAnnotationMetadataReader =
    new TastyInspectorAnnotationMetadataReader(
      dependencyClasspath = dependencyClasspath,
      inputProvider = _ => (tastyFiles, jars)
    )

  def fromPaths(
      paths: List[ValidatedStructuredMetadataPath],
      dependencyClasspath: List[String]
  ): TastyInspectorAnnotationMetadataReader =
    new TastyInspectorAnnotationMetadataReader(
      dependencyClasspath = dependencyClasspath,
      inputProvider = annotationName =>
        inputsFromExplicitPaths(annotationName, paths)
    )

  private def inputsFromClasspath(
      annotationName: String,
      apiClasspath: List[String]
  ): (List[String], List[String]) =
    val tastyResource = annotationTastyResource(annotationName)
    val tastyFiles = List.newBuilder[String]
    val jars = List.newBuilder[String]

    apiClasspath.foreach: entry =>
      val file = File(entry)
      if file.isDirectory then
        val tastyFile = File(file, tastyResource)
        if tastyFile.isFile then tastyFiles += tastyFile.getAbsolutePath
      else if file.isFile && file.getName.endsWith(".jar") then
        jars += file.getAbsolutePath

    (tastyFiles.result(), jars.result())

  private def inputsFromExplicitPaths(
      annotationName: String,
      paths: List[ValidatedStructuredMetadataPath]
  ): (List[String], List[String]) =
    val tastyResource = annotationTastyResource(annotationName)
    val tastyFiles = List.newBuilder[String]
    val jars = List.newBuilder[String]

    paths.foreach: path =>
      if path.isDirectory then
        val tastyFile = path.path.resolve(tastyResource)
        if Files.isRegularFile(tastyFile) then
          tastyFiles += tastyFile.toAbsolutePath.toString
      else if path.contains(tastyResource) then
        jars += path.value

    (tastyFiles.result(), jars.result())

  private def annotationTastyResource(annotationName: String): String =
    val className =
      if annotationName.contains('.') then annotationName
      else s"paradise3.$annotationName"
    s"${className.replace('.', '/')}.tasty"

private[macroparadise] final class TastyInspectorAnnotationMetadataReader private (
    dependencyClasspath: List[String],
    inputProvider: String => (List[String], List[String])
) extends AnnotationMetadataReader:
  def findExpanderClass(annotationName: String): MetadataLookupResult =
    val annotationClassName =
      if annotationName.contains('.') then annotationName
      else s"paradise3.$annotationName"
    try
      val (tastyFiles, jars) = inputProvider(annotationName)
      if tastyFiles.isEmpty && jars.isEmpty then MetadataLookupResult.NotFound
      else
        val inspector = ExpanderMetadataInspector(annotationClassName)
        val inspected =
          // MAY DEPEND ON SCALA VERSION
          // Production attempts this path-based structured reader first. Its
          // availability remains classpath-sensitive, so lookup stays protected
          // by the controlled string-reader fallback.
          TastyInspector.inspectAllTastyFiles(
            tastyFiles,
            jars,
            dependencyClasspath
          )(inspector)

        if inspected then inspector.result.getOrElse(MetadataLookupResult.NotFound)
        else MetadataLookupResult.Failed(s"could not inspect annotation metadata for `$annotationClassName`: TastyInspector reported errors")
    catch
      case NonFatal(error) =>
        MetadataLookupResult.Failed(s"could not inspect annotation metadata for `$annotationClassName`: ${error.getMessage}")

private[macroparadise] final class ExpanderMetadataInspector(annotationClassName: String) extends Inspector:
  private var lookupResult: Option[MetadataLookupResult] = None

  def result: Option[MetadataLookupResult] = lookupResult

  def inspect(using q: Quotes)(tastys: List[Tasty[q.type]]): Unit =
    import q.reflect.*

    object Traverser extends TreeTraverser:
      override def traverseTree(tree: Tree)(owner: Symbol): Unit =
        tree match
          case classDef: ClassDef if classDef.symbol.fullName == annotationClassName =>
            lookupResult = readExpanderAnnotation(classDef.symbol.annotations)
          case _ =>
            super.traverseTree(tree)(owner)

    tastys.foreach(tasty => Traverser.traverseTree(tasty.ast)(Symbol.spliceOwner))

  private def readExpanderAnnotation(using q: Quotes)(annotations: List[q.reflect.Term]): Option[MetadataLookupResult] =
    annotations.collectFirst:
      case annotation if isExpanderAnnotation(annotation) =>
        expanderClassName(annotation) match
          case Some(className) if className.trim.nonEmpty =>
            MetadataLookupResult.Found(className.trim)
          case Some(_) =>
            MetadataLookupResult.Failed(s"empty annotation metadata expander class name for `$annotationClassName`")
          case None =>
            MetadataLookupResult.Failed(s"malformed annotation metadata expander for `$annotationClassName`")

  private def isExpanderAnnotation(using q: Quotes)(annotation: q.reflect.Term): Boolean =
    import q.reflect.*

    annotation.symbol.fullName == "paradise3.api.expander.<init>" ||
      annotation.tpe.show == "paradise3.api.expander"

  private def expanderClassName(using q: Quotes)(annotation: q.reflect.Term): Option[String] =
    import q.reflect.*

    annotation match
      case Apply(_, arguments) =>
        arguments.collectFirst(Function.unlift(stringLiteral))
      case _ => None

  private def stringLiteral(using q: Quotes)(term: q.reflect.Term): Option[String] =
    import q.reflect.*

    term match
      case Literal(StringConstant(className)) => Some(className)
      case NamedArg(_, value) => stringLiteral(value)
      case Typed(value, _) => stringLiteral(value)
      case Inlined(_, Nil, value) => stringLiteral(value)
      case Block(Nil, value) => stringLiteral(value)
      case _ => None

private[macroparadise] final class TastyStringAnnotationMetadataReader(apiLoader: ClassLoader) extends AnnotationMetadataReader:
  def findExpanderClass(annotationName: String): MetadataLookupResult =
    // ASSUMPTION
    // This compatibility prototype discovers precompiled marker resources by
    // their exact syntactic class identity. Same-module definitions remain TODO.
    val annotationClassName =
      if annotationName.contains('.') then annotationName
      else s"paradise3.$annotationName"
    val tastyResource = s"${annotationClassName.replace('.', '/')}.tasty"

    Option(apiLoader.getResourceAsStream(tastyResource)).map: stream =>
      try
        val bytes = stream.readAllBytes()
        val strings = printableStrings(bytes)
        if strings.contains("expander") then
          firstHandlerClassName(strings) match
            case Some(className) if className.trim.nonEmpty =>
              MetadataLookupResult.Found(className.trim)
            case _ =>
              MetadataLookupResult.Failed(s"empty annotation metadata expander class name for `$annotationClassName`")
        else MetadataLookupResult.NotFound
      catch
        case NonFatal(error) =>
          MetadataLookupResult.Failed(s"could not inspect annotation metadata for `$annotationClassName`: ${error.getMessage}")
      finally stream.close()
    .getOrElse(MetadataLookupResult.NotFound)

  private def firstHandlerClassName(strings: List[String]): Option[String] =
    val afterExpander = strings.dropWhile(_ != "expander").drop(1)
    afterExpander.find(isLikelyExternalHandlerClassName)

  private def isLikelyExternalHandlerClassName(value: String): Boolean =
    value.contains(".") &&
      !value.contains("/") &&
      value.forall(ch => ch.isLetterOrDigit || ch == '.' || ch == '_' || ch == '$') &&
      !value.startsWith("scala.") &&
      !value.startsWith("java.") &&
      !value.startsWith("dotty.") &&
      !value.startsWith("paradise3.")

  private def printableStrings(bytes: Array[Byte]): List[String] =
    // NEEDS VERIFICATION
    // MAY DEPEND ON SCALA VERSION
    // This is a deliberately narrow TASTy resource probe for the metadata-discovery
    // prototype. A durable implementation should use a structured TASTy/classfile reader.
    val strings = List.newBuilder[String]
    val current = new StringBuilder

    bytes.foreach: byte =>
      val value = byte & 0xff
      if value >= 32 && value <= 126 then current.append(value.toChar)
      else if current.nonEmpty then
        strings += current.toString
        current.clear()

    if current.nonEmpty then strings += current.toString
    strings.result()
