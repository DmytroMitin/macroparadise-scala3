package macroparadise

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.ParadiseAnnotationExpander
import paradise3.api.expander
import scala.jdk.CollectionConverters.*
import scala.quoted.Quotes
import scala.tasty.inspector.Inspector
import scala.util.control.NonFatal

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

private[macroparadise] final case class ValidatedStructuredMetadataPath(
    path: Path,
    isDirectory: Boolean
):
  def value: String = path.toString

  def contains(resource: String): Boolean =
    if isDirectory then Files.isRegularFile(path.resolve(resource))
    else
      val jar = JarFile(path.toFile)
      try jar.getJarEntry(resource) != null
      finally jar.close()

private[macroparadise] final case class LoadedArtifactFacts(
    role: String,
    codeSource: Option[Path],
    isReadableJar: Boolean,
    implementationVersion: Option[String],
    entries: Set[String]
)

private[macroparadise] object StructuredMetadataDistributionContract:
  val OptionName = "structuredMetadataPath"
  val OptionPrefix = s"$OptionName="

  private val CompilerRole = "active compiler"
  private val InspectorRole = "active inspector"
  private val CompilerClassEntry =
    "dotty/tools/dotc/core/Contexts$Context.class"
  private val InspectorClassEntry =
    "scala/tasty/inspector/Inspector.class"

  enum InspectorAudit:
    case Ready(evidence: List[String])
    case Unavailable(message: String)
    case Invalid(message: String)

  def parseAndValidate(
      options: List[String]
  ): Either[String, Option[List[ValidatedStructuredMetadataPath]]] =
    val rawValues =
      options.collect:
        case option if option.startsWith(OptionPrefix) =>
          option.stripPrefix(OptionPrefix)

    if rawValues.isEmpty then Right(None)
    else if rawValues.exists(_.trim.isEmpty) then
      Left(s"empty experimental `$OptionName=` option")
    else
      rawValues
        .foldLeft[Either[String, List[ValidatedStructuredMetadataPath]]](
          Right(Nil)
        ):
          case (accumulated, rawValue) =>
            for
              paths <- accumulated
              path <- validatePath(rawValue.trim)
              _ <-
                if paths.exists(_.path == path.path) then
                  Left(
                    s"duplicate experimental structured metadata path after normalization: `${path.value}`"
                  )
                else Right(())
            yield paths :+ path
        .map(Some(_))

  def auditRuntime(
      pluginLoader: ClassLoader,
      handlerLoader: ClassLoader
  ): InspectorAudit =
    try
      validateActiveArtifacts().flatMap: activeArtifactEvidence =>
        for
          pluginEvidence <- verifySameIdentities(
            "plugin",
            pluginLoader,
            List(
              classOf[Context],
              classOf[Inspector],
              classOf[Quotes],
              classOf[expander],
              classOf[ParadiseAnnotationExpander]
            )
          )
          handlerEvidence <- verifySameIdentities(
            "handler",
            handlerLoader,
            List(
              classOf[Context],
              classOf[Quotes],
              classOf[expander],
              classOf[ParadiseAnnotationExpander]
            )
          )
        yield activeArtifactEvidence ::: pluginEvidence ::: handlerEvidence
      match
        case Right(evidence) => InspectorAudit.Ready(evidence)
        case Left(message) => InspectorAudit.Invalid(message)
    catch
      case error: LinkageError =>
        InspectorAudit.Unavailable(
          s"exact active compiler/inspector universe is unavailable: ${errorMessage(error)}"
        )
      case NonFatal(error) =>
        InspectorAudit.Invalid(
          s"could not validate structured metadata distribution: ${errorMessage(error)}"
        )

  def validatePath(
      rawPath: String
  ): Either[String, ValidatedStructuredMetadataPath] =
    val unresolved = Path.of(rawPath).toAbsolutePath.normalize()
    if !Files.exists(unresolved) then
      Left(s"experimental structured metadata path does not exist: `$unresolved`")
    else if !Files.isReadable(unresolved) then
      Left(s"experimental structured metadata path is not readable: `$unresolved`")
    else
      try
        val path = unresolved.toRealPath()
        if Files.isDirectory(path) then validateDirectory(path)
        else if Files.isRegularFile(path) && path.getFileName.toString.endsWith(".jar") then
          validateJar(path)
        else
          Left(
            s"experimental structured metadata path must be a readable JAR or directory: `$path`"
          )
      catch
        case NonFatal(error) =>
          Left(
            s"could not validate experimental structured metadata path `$unresolved`: ${errorMessage(error)}"
          )

  private def validateJar(
      path: Path
  ): Either[String, ValidatedStructuredMetadataPath] =
    readJarEntries(path, "structured metadata").flatMap: entries =>
      validateMetadataEntries(path, entries).map: _ =>
        ValidatedStructuredMetadataPath(path, isDirectory = false)

  private def validateDirectory(
      path: Path
  ): Either[String, ValidatedStructuredMetadataPath] =
    try
      val stream = Files.walk(path)
      val entries =
        try
          stream.iterator().asScala
            .filter(Files.isRegularFile(_))
            .map(entry => path.relativize(entry).toString.replace(File.separatorChar, '/'))
            .toSet
        finally stream.close()

      validateMetadataEntries(path, entries).map: _ =>
        ValidatedStructuredMetadataPath(path, isDirectory = true)
    catch
      case NonFatal(error) =>
        Left(
          s"could not inspect structured metadata directory `$path`: ${errorMessage(error)}"
        )

  private def validateMetadataEntries(
      path: Path,
      entries: Set[String]
  ): Either[String, Unit] =
    val obsoleteCarrier =
      entries.filter(entry =>
        entry == "paradise3/api/expander.class" ||
          entry == "paradise3/api/expander.tasty"
      )
    val conflictingIdentity =
      entries.filter(entry =>
        entry.startsWith("dotty/") ||
          entry.startsWith("scala/") ||
          entry.startsWith("paradise3/api/")
      )
    val markerTasty =
      entries.exists(entry =>
        entry.startsWith("paradise3/") &&
          !entry.startsWith("paradise3/api/") &&
          entry.endsWith(".tasty")
      )

    if obsoleteCarrier.nonEmpty then
      Left(
        s"experimental structured metadata path `$path` exposes obsolete carrier ${obsoleteCarrier.toList.sorted.mkString(", ")}"
      )
    else if conflictingIdentity.nonEmpty then
      Left(
        s"experimental structured metadata path `$path` exposes conflicting compiler/Scala/TASTy/API identity at ${conflictingIdentity.toList.sorted.head}"
      )
    else if !markerTasty then
      Left(
        s"experimental structured metadata path `$path` contains no `paradise3` legacy marker TASTy entry"
      )
    else Right(())

  private[macroparadise] def validateActiveArtifactPair(
      compiler: LoadedArtifactFacts,
      inspector: LoadedArtifactFacts
  ): Either[String, List[String]] =
    for
      compilerPath <- requireReadableJar(compiler)
      compilerVersion <- requireImplementationVersion(compiler)
      expectedCompilerName = s"scala3-compiler_3-$compilerVersion.jar"
      _ <- requireFileName(compiler, compilerPath, expectedCompilerName)
      _ <- requireEntry(compiler, compilerPath, CompilerClassEntry)
      inspectorPath <- requireReadableJar(inspector)
      inspectorVersion <- requireImplementationVersion(inspector)
      expectedInspectorName =
        s"scala3-tasty-inspector_3-$compilerVersion.jar"
      _ <- requireFileName(inspector, inspectorPath, expectedInspectorName)
      _ <-
        if inspectorVersion == compilerVersion then Right(())
        else
          Left(
            s"${inspector.role} manifest version mismatch: expected active compiler version `$compilerVersion`, got `$inspectorVersion`"
          )
      _ <- requireEntry(inspector, inspectorPath, InspectorClassEntry)
      forbidden = inspector.entries.filter(isForbiddenInspectorEntry)
      _ <-
        if forbidden.isEmpty then Right(())
        else
          Left(
            s"${inspector.role} jar `$inspectorPath` duplicates compiler/Scala/TASTy/API identity at ${forbidden.toList.sorted.head}"
          )
    yield List(
      s"compiler=$compilerPath version=$compilerVersion artifact=exact-loaded",
      s"inspector=$inspectorPath version=$inspectorVersion artifact=thin-exact"
    )

  private def validateActiveArtifacts(): Either[String, List[String]] =
    for
      compiler <- loadedArtifactFacts(classOf[Context], CompilerRole)
      inspector <- loadedArtifactFacts(classOf[Inspector], InspectorRole)
      evidence <- validateActiveArtifactPair(compiler, inspector)
    yield evidence

  private def loadedArtifactFacts(
      clazz: Class[?],
      role: String
  ): Either[String, LoadedArtifactFacts] =
    codeSourcePath(clazz) match
      case None =>
        Right(
          LoadedArtifactFacts(
            role,
            codeSource = None,
            isReadableJar = false,
            implementationVersion = None,
            entries = Set.empty
          )
        )
      case Some(path) =>
        val isReadableJar =
          Files.isRegularFile(path) &&
            Files.isReadable(path) &&
            path.getFileName.toString.endsWith(".jar")
        if !isReadableJar then
          Right(
            LoadedArtifactFacts(
              role,
              codeSource = Some(path),
              isReadableJar = false,
              implementationVersion = None,
              entries = Set.empty
            )
          )
        else
          for
            version <- readImplementationVersion(path, role)
            entries <- readJarEntries(path, role)
          yield LoadedArtifactFacts(
            role,
            codeSource = Some(path),
            isReadableJar = true,
            implementationVersion = version,
            entries = entries
          )

  private def readImplementationVersion(
      path: Path,
      role: String
  ): Either[String, Option[String]] =
    try
      val jar = JarFile(path.toFile)
      try
        Right(
          Option(jar.getManifest)
            .flatMap(manifest =>
              Option(
                manifest.getMainAttributes.getValue(
                  "Implementation-Version"
                )
              )
            )
            .map(_.trim)
            .filter(_.nonEmpty)
        )
      finally jar.close()
    catch
      case NonFatal(error) =>
        Left(s"$role jar `$path` is malformed: ${errorMessage(error)}")

  private def requireReadableJar(
      artifact: LoadedArtifactFacts
  ): Either[String, Path] =
    artifact.codeSource match
      case None => Left(s"${artifact.role} class has no code source")
      case Some(path) if !artifact.isReadableJar =>
        Left(
          s"${artifact.role} code source must be a readable JAR, got `$path`"
        )
      case Some(path) => Right(path)

  private def requireImplementationVersion(
      artifact: LoadedArtifactFacts
  ): Either[String, String] =
    artifact.implementationVersion
      .map(_.trim)
      .filter(_.nonEmpty)
      .toRight(
        s"${artifact.role} jar has no non-empty implementation version"
      )

  private def requireFileName(
      artifact: LoadedArtifactFacts,
      path: Path,
      expected: String
  ): Either[String, Unit] =
    val actual = path.getFileName.toString
    if actual == expected then Right(())
    else
      Left(
        s"${artifact.role} filename mismatch: expected `$expected`, got `$actual` at `$path`"
      )

  private def requireEntry(
      artifact: LoadedArtifactFacts,
      path: Path,
      required: String
  ): Either[String, Unit] =
    if artifact.entries.contains(required) then Right(())
    else Left(s"${artifact.role} jar `$path` is missing $required")

  private def isForbiddenInspectorEntry(entry: String): Boolean =
    entry.startsWith("dotty/") ||
      entry.startsWith("scala/quoted/") ||
      entry.startsWith("paradise3/api/") ||
      (
        entry.startsWith("scala/") &&
          entry != "scala/" &&
          entry != "scala/tasty/" &&
          !entry.startsWith("scala/tasty/inspector/")
      )

  private def verifySameIdentities(
      scope: String,
      loader: ClassLoader,
      expectedClasses: List[Class[?]]
  ): Either[String, List[String]] =
    expectedClasses.foldLeft[Either[String, List[String]]](Right(Nil)):
      case (accumulated, expected) =>
        for
          evidence <- accumulated
          loaded <-
            try Right(Class.forName(expected.getName, false, loader))
            catch
              case error: LinkageError =>
                Left(
                  s"$scope loader could not resolve `${expected.getName}`: ${errorMessage(error)}"
                )
              case NonFatal(error) =>
                Left(
                  s"$scope loader could not resolve `${expected.getName}`: ${errorMessage(error)}"
                )
          line <-
            if loaded eq expected then
              Right(
                s"$scope identity=${expected.getName} source=${codeSourcePath(expected).map(_.toString).getOrElse("<none>")} loader=${loaderIdentity(expected.getClassLoader)}"
              )
            else
              Left(
                s"$scope loader duplicated `${expected.getName}`: expected ${loaderIdentity(expected.getClassLoader)}, got ${loaderIdentity(loaded.getClassLoader)}"
              )
        yield evidence :+ line

  private def readJarEntries(
      path: Path,
      role: String
  ): Either[String, Set[String]] =
    try
      val jar = JarFile(path.toFile)
      try
        val entries = Set.newBuilder[String]
        val iterator = jar.entries()
        while iterator.hasMoreElements do entries += iterator.nextElement().getName
        Right(entries.result())
      finally jar.close()
    catch
      case NonFatal(error) =>
        Left(s"$role jar `$path` is malformed: ${errorMessage(error)}")

  private def codeSourcePath(clazz: Class[?]): Option[Path] =
    Option(clazz.getProtectionDomain)
      .flatMap(domain => Option(domain.getCodeSource))
      .flatMap(source => Option(source.getLocation))
      .map(url => Path.of(url.toURI).toRealPath())

  private def loaderIdentity(loader: ClassLoader | Null): String =
    if loader == null then "<bootstrap>"
    else s"${loader.getClass.getName}@${System.identityHashCode(loader).toHexString}"

  private def errorMessage(error: Throwable): String =
    Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getName)
