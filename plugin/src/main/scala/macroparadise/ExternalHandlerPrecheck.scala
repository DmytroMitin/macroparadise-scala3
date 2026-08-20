package macroparadise

import paradise3.api.{ParadiseAnnotationExpander, expander}

import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Plugin-owned declaration and binding precheck for experimental external handlers.
  *
  * The retained command entrypoint is deliberately narrower than the compiler
  * plugin. It validates packaged authoring inputs before a consumer compilation
  * starts and reuses the plugin's existing declaration and binding authorities.
  * It never calls `ParadiseAnnotationExpander.expand`.
  */
private[macroparadise] object ExternalHandlerPrecheck:
  final case class Failure(category: String, detail: String):
    def render: String =
      if detail.startsWith("failureStage=") then s"category=$category $detail"
      else s"category=$category detail=$detail"

  final case class Environment(
      expectedScalaVersion: String,
      actualScalaVersion: String,
      expectedJdkMajor: Int,
      actualJdkMajor: Int
  )

  final case class ClasspathArtifact(
      path: Path,
      entries: Set[String],
      referencedNames: Set[String]
  )

  final case class ArtifactPaths(
      plugin: Path,
      pluginApi: Path,
      marker: Path,
      handler: Path
  )

  final case class Request(
      artifacts: ArtifactPaths,
      handlerCompileClasspath: Vector[Path],
      markerClassName: String,
      expectedHandlerClassName: String,
      expectedAnnotationName: String,
      environment: Environment,
      parentLoader: ClassLoader
  )

  final case class Success(
      annotationName: String,
      handlerClassName: String,
      metadataHandlerClassName: String,
      parentFirstContractIdentity: Boolean,
      markerLoadedWithoutInitialization: Boolean,
      expansionInvoked: Boolean,
      handlerCompileClasspath: Vector[Path]
  ):
    def render: String =
      s"annotation=$annotationName handler=$handlerClassName metadataHandler=$metadataHandlerClassName " +
        s"parentFirstContractIdentity=$parentFirstContractIdentity " +
        s"markerLoadedWithoutInitialization=$markerLoadedWithoutInitialization expansionInvoked=$expansionInvoked " +
        s"handlerCompileClasspath=${handlerCompileClasspath.mkString(java.io.File.pathSeparator)}"

  final case class ArtifactSnapshot(path: Path, entries: Set[String])

  final case class ValidatedArtifacts(
      plugin: ArtifactSnapshot,
      pluginApi: ArtifactSnapshot,
      marker: ArtifactSnapshot,
      handler: ArtifactSnapshot
  )

  private final case class AuthoringDiagnosticContext(
      markerIdentity: String,
      expectedAnnotation: String,
      expectedHandler: String,
      markerArtifact: Path,
      handlerArtifact: Path
  ):
    def render(
        failureStage: String,
        metadataHandler: Option[String],
        declaredAnnotation: Option[String] = None,
        detail: String
    ): String =
      val fields = Vector(
        "failureStage" -> failureStage,
        "markerIdentity" -> markerIdentity,
        "expectedAnnotation" -> expectedAnnotation,
        "metadataHandler" -> metadataHandler.getOrElse("<unavailable>"),
        "expectedHandler" -> expectedHandler,
        "markerArtifact" -> normalize(markerArtifact).toString,
        "handlerArtifact" -> normalize(handlerArtifact).toString
      ) ++ declaredAnnotation.map("declaredAnnotation" -> _)

      (fields.map((name, value) => s"$name=${diagnosticValue(value)}") :+
        s"detail=${ExternalHandlerDiagnostics.normalize(detail)}").mkString(" ")

  def run(request: Request): Either[Failure, Success] =
    for
      _ <- validateEnvironment(request.environment)
      _ <- validateDeclaredAnnotation(request.expectedAnnotationName)
      validated <- validateArtifactRoles(
        request.artifacts,
        request.markerClassName,
        request.expectedHandlerClassName
      )
      classpath <- inspectClasspath(request.handlerCompileClasspath)
      _ <- validateHandlerCompileClasspath(
        request.artifacts.pluginApi,
        request.artifacts.plugin,
        classpath
      )
      _ <- validateHandlerPayload(validated.handler.path)
      success <- loadAndValidate(request, validated)
    yield success

  def validateDeclaredAnnotation(value: String): Either[Failure, String] =
    SyntacticAnnotationIdentity.fromDeclaredName(value).left.map: detail =>
      Failure(
        "INVALID_HANDLER_ANNOTATION_NAME",
        s"declared annotation `$value` is invalid: $detail"
      )
    .map(_.value)

  def validateEnvironment(environment: Environment): Either[Failure, Unit] =
    if environment.actualJdkMajor != environment.expectedJdkMajor then
      Left(
        Failure(
          "EXACT_JDK_MISMATCH",
          s"expected JDK ${environment.expectedJdkMajor}, found ${environment.actualJdkMajor}"
        )
      )
    else if environment.actualScalaVersion != environment.expectedScalaVersion then
      Left(
        Failure(
          "EXACT_COMPILER_MISMATCH",
          s"expected Scala compiler ${environment.expectedScalaVersion}, found ${environment.actualScalaVersion}"
        )
      )
    else Right(())

  def validateMetadataSelection(
      markerIdentity: String,
      metadataHandlerClass: String,
      expectedAnnotation: String,
      expectedHandlerClass: String
  ): Either[Failure, Unit] =
    for
      canonicalExpected <- validateDeclaredAnnotation(expectedAnnotation)
      canonicalMarker <- validateDeclaredAnnotation(markerIdentity).left.map: failure =>
        failure.copy(
          category = "INVALID_MARKER_ANNOTATION_IDENTITY",
          detail = s"marker identity `$markerIdentity` is invalid: ${failure.detail}"
        )
      _ <-
        if canonicalMarker == canonicalExpected then Right(())
        else
          Left(
            Failure(
              "MARKER_ANNOTATION_IDENTITY_MISMATCH",
              s"configured annotation `$canonicalExpected` does not equal marker class identity `$canonicalMarker`"
            )
          )
      _ <- validateMetadataHandlerClassName(metadataHandlerClass)
      _ <-
        if metadataHandlerClass == expectedHandlerClass then Right(())
        else
          Left(
            Failure(
              "METADATA_HANDLER_CLASS_MISMATCH",
              s"marker metadata selects `$metadataHandlerClass`, but the independent caller expectation is `$expectedHandlerClass`"
            )
          )
    yield ()

  private def validateMetadataHandlerClassName(value: String): Either[Failure, String] =
    SyntacticAnnotationIdentity.fromDeclaredName(value).left.map: _ =>
      Failure(
        "INVALID_METADATA_HANDLER_CLASS_NAME",
        s"marker metadata handler `${diagnosticValue(value)}` is invalid: expected a canonical simple or dot-qualified handler class name"
      )
    .map(_.value)

  def validateArtifactRoles(
      paths: ArtifactPaths,
      markerClassName: String,
      handlerClassName: String
  ): Either[Failure, ValidatedArtifacts] =
    for
      plugin <- inspectJar(paths.plugin, "plugin")
      pluginApi <- inspectJar(paths.pluginApi, "pluginApi")
      marker <- inspectJar(paths.marker, "marker")
      handler <- inspectJar(paths.handler, "handler")
      _ <- requireEntries(
        plugin,
        "plugin",
        Set(
          "macroparadise/MacroParadisePlugin.class",
          "macroparadise/ExternalHandlerPrecheckMain.class",
          "plugin.properties"
        )
      )
      _ <- requireEntries(
        pluginApi,
        "pluginApi",
        Set(
          "paradise3/api/ParadiseAnnotationExpander.class",
          "paradise3/api/expander.class"
        )
      )
      markerEntry = classEntry(markerClassName)
      handlerEntry = classEntry(handlerClassName)
      _ <- requireEntries(marker, "marker", Set(markerEntry))
      _ <-
        if handler.entries.exists(_.endsWith(".class")) then Right(())
        else
          Left(
            Failure(
              "WRONG_ARTIFACT_ROLE",
              s"handler artifact `${handler.path}` contains no JVM class entries"
            )
          )
      _ <-
        if marker.entries.contains(handlerEntry) then
          Left(
            Failure(
              "WRONG_ARTIFACT_ROLE",
              s"marker artifact `${marker.path}` contains configured handler class `$handlerClassName`"
            )
          )
        else Right(())
      _ <-
        if handler.entries.contains(markerEntry) then
          Left(
            Failure(
              "WRONG_ARTIFACT_ROLE",
              s"handler artifact `${handler.path}` contains marker class `$markerClassName`"
            )
          )
        else Right(())
    yield ValidatedArtifacts(plugin, pluginApi, marker, handler)

  def validateHandlerCompileClasspath(
      pluginApiArtifact: Path,
      pluginArtifact: Path,
      artifacts: Vector[ClasspathArtifact]
  ): Either[Failure, Unit] =
    val normalizedApi = normalize(pluginApiArtifact)
    val normalizedPlugin = normalize(pluginArtifact)
    val normalizedArtifacts = artifacts.map(value => value.copy(path = normalize(value.path)))
    val apiOccurrences = normalizedArtifacts.count(_.path == normalizedApi)

    if apiOccurrences != 1 then
      Left(
        Failure(
          "HANDLER_CONTRACT_CLASSPATH_MISMATCH",
          s"handler compile classpath must contain the supplied plugin API artifact exactly once; found $apiOccurrences"
        )
      )
    else
      normalizedArtifacts.find: artifact =>
        artifact.path == normalizedPlugin ||
          forbiddenPath(artifact.path) ||
          artifact.entries.contains("macroparadise/MacroParadisePlugin.class") ||
          artifact.referencedNames.exists(forbiddenReference)
      match
        case Some(forbidden) =>
          Left(
            Failure(
              "FORBIDDEN_HANDLER_DEPENDENCY",
              s"handler compile/linkage evidence contains forbidden production-plugin or repository-fixture input `${forbidden.path}`"
            )
          )
        case None =>
          if normalizedArtifacts.exists(_.entries.contains("dotty/tools/dotc/Main.class")) then
            Right(())
          else
            Left(
              Failure(
                "HANDLER_COMPILER_CLASSPATH_MISSING",
                "handler compile/linkage evidence contains no exact Scala compiler artifact"
              )
            )

  private def loadAndValidate(
      request: Request,
      artifacts: ValidatedArtifacts
  ): Either[Failure, Success] =
    val parent = Option(request.parentLoader).getOrElse(classOf[ParadiseAnnotationExpander].getClassLoader)
    val loader = URLClassLoader(
      Array(
        artifacts.marker.path.toUri.toURL,
        artifacts.handler.path.toUri.toURL
      ),
      parent
    )

    try
      for
        parentApi <- loadClass(
          classOf[ParadiseAnnotationExpander].getName,
          initialize = false,
          parent,
          "HANDLER_CONTRACT_IDENTITY_FAILURE"
        )
        childApi <- loadClass(
          classOf[ParadiseAnnotationExpander].getName,
          initialize = false,
          loader,
          "HANDLER_CONTRACT_IDENTITY_FAILURE"
        )
        _ <-
          if childApi eq parentApi then Right(())
          else
            Left(
              Failure(
                "HANDLER_CONTRACT_IDENTITY_FAILURE",
                "handler child loader resolved a second ParadiseAnnotationExpander identity"
              )
            )
        markerClass <- loadClass(
          request.markerClassName,
          initialize = false,
          loader,
          "MARKER_CLASS_LOADING_FAILURE"
        )
        metadata <-
          Option(markerClass.getAnnotation(classOf[expander])) match
            case Some(value) => Right(value)
            case None =>
              Left(
                Failure(
                  "MARKER_METADATA_MISSING",
                  s"marker `${request.markerClassName}` has no runtime paradise3.api.expander metadata"
                )
              )
        context = AuthoringDiagnosticContext(
          markerIdentity = markerClass.getName,
          expectedAnnotation = request.expectedAnnotationName,
          expectedHandler = request.expectedHandlerClassName,
          markerArtifact = artifacts.marker.path,
          handlerArtifact = artifacts.handler.path
        )
        _ <- validateMetadataSelection(
          markerClass.getName,
          metadata.value(),
          request.expectedAnnotationName,
          request.expectedHandlerClassName
        ).left.map: failure =>
          contextualize(
            failure,
            context,
            failureStage = "metadata-selection",
            metadataHandler = Some(metadata.value())
          )
        _ <- requireExpectedHandlerEntry(
          artifacts.handler,
          request.expectedHandlerClassName,
          metadata.value(),
          context
        )
        handlerClass <- loadClass(
          request.expectedHandlerClassName,
          initialize = true,
          loader,
          "HANDLER_CLASS_LOADING_FAILURE"
        ).left.map: failure =>
          contextualize(
            failure,
            context,
            failureStage = "handler-loading",
            metadataHandler = Some(metadata.value())
          )
        _ <-
          if parentApi.isAssignableFrom(handlerClass) then Right(())
          else
            Left(
              Failure(
                "HANDLER_CONTRACT_IDENTITY_FAILURE",
                context.render(
                  failureStage = "handler-contract",
                  metadataHandler = Some(metadata.value()),
                  detail = s"handler `${handlerClass.getName}` does not implement the supplied parent-first ParadiseAnnotationExpander identity"
                )
              )
            )
        instance <- constructHandler(handlerClass).left.map: failure =>
          contextualize(
            failure,
            context,
            failureStage = "handler-construction",
            metadataHandler = Some(metadata.value())
          )
        loaded <- ExternalHandlerDescriptor.capture(instance, loader).left.map: failure =>
          Failure(
            "HANDLER_DECLARATION_FAILURE",
            context.render(
              failureStage = "handler-declaration",
              metadataHandler = Some(metadata.value()),
              detail = failure.diagnostic
            )
          )
        binding <- MetadataHandlerBinding
          .validate(
            markerClass.getName,
            metadata.value(),
            loaded,
            loader
          )
          .left
          .map: _ =>
            Failure(
              "METADATA_HANDLER_ANNOTATION_MISMATCH",
              context.render(
                failureStage = "metadata-binding",
                metadataHandler = Some(metadata.value()),
                declaredAnnotation = Some(loaded.descriptor.annotationName),
                detail = s"marker metadata selects `${metadata.value()}`, but its captured descriptor declares `${loaded.descriptor.annotationName}`"
              )
            )
      yield
        Success(
          annotationName = binding.metadataAnnotationName,
          handlerClassName = binding.loadedHandler.descriptor.handlerClassName,
          metadataHandlerClassName = binding.metadataHandlerClassName,
          parentFirstContractIdentity = true,
          markerLoadedWithoutInitialization = true,
          expansionInvoked = false,
          handlerCompileClasspath = request.handlerCompileClasspath.map(normalize)
        )
    catch
      case error: LinkageError =>
        Left(
          Failure(
            "HANDLER_LINKAGE_FAILURE",
            s"${error.getClass.getName}: ${Option(error.getMessage).getOrElse("")}"
          )
        )
      case NonFatal(error) =>
        Left(
          Failure(
            "PRECHECK_INTERNAL_FAILURE",
            s"${error.getClass.getName}: ${Option(error.getMessage).getOrElse("")}"
          )
        )
    finally loader.close()

  private def requireExpectedHandlerEntry(
      handler: ArtifactSnapshot,
      expectedHandlerClassName: String,
      metadataHandlerClassName: String,
      context: AuthoringDiagnosticContext
  ): Either[Failure, Unit] =
    if handler.entries.contains(classEntry(expectedHandlerClassName)) then Right(())
    else
      Left(
        Failure(
          "HANDLER_CLASS_LOADING_FAILURE",
          context.render(
            failureStage = "handler-artifact",
            metadataHandler = Some(metadataHandlerClassName),
            detail = s"supplied handler artifact `${handler.path}` does not contain expected handler class `$expectedHandlerClassName`"
          )
        )
      )

  private def contextualize(
      failure: Failure,
      context: AuthoringDiagnosticContext,
      failureStage: String,
      metadataHandler: Option[String]
  ): Failure =
    failure.copy(
      detail = context.render(
        failureStage,
        metadataHandler,
        detail = failure.detail
      )
    )

  private def diagnosticValue(value: String): String =
    Option(value) match
      case None => "<null>"
      case Some(raw) if raw.isEmpty => "<empty>"
      case Some(raw) if raw.trim.isEmpty => "<whitespace>"
      case Some(raw) => ExternalHandlerDiagnostics.normalize(raw)

  private def constructHandler(
      handlerClass: Class[?]
  ): Either[Failure, ParadiseAnnotationExpander] =
    try
      Right(
        handlerClass
          .getDeclaredConstructor()
          .newInstance()
          .asInstanceOf[ParadiseAnnotationExpander]
      )
    catch
      case error: LinkageError =>
        Left(
          Failure(
            "HANDLER_CONSTRUCTION_FAILURE",
            s"${error.getClass.getName}: ${Option(error.getMessage).getOrElse("")}"
          )
        )
      case NonFatal(error) =>
        Left(
          Failure(
            "HANDLER_CONSTRUCTION_FAILURE",
            s"${error.getClass.getName}: ${Option(error.getMessage).getOrElse("")}"
          )
        )

  private def loadClass(
      name: String,
      initialize: Boolean,
      loader: ClassLoader,
      category: String
  ): Either[Failure, Class[?]] =
    try Right(Class.forName(name, initialize, loader))
    catch
      case error: LinkageError =>
        Left(Failure(category, s"${error.getClass.getName}: ${Option(error.getMessage).getOrElse("")}"))
      case NonFatal(error) =>
        Left(Failure(category, s"${error.getClass.getName}: ${Option(error.getMessage).getOrElse("")}"))

  private def inspectClasspath(
      paths: Vector[Path]
  ): Either[Failure, Vector[ClasspathArtifact]] =
    traverse(paths): path =>
      inspectJar(path, "handler compile classpath").map: snapshot =>
        ClasspathArtifact(
          snapshot.path,
          snapshot.entries,
          snapshot.entries.map(_.stripSuffix(".class"))
        )

  private def validateHandlerPayload(handlerArtifact: Path): Either[Failure, Unit] =
    val forbidden = Vector(
      "macroparadise/",
      "pluginTestHandlers/",
      "pluginTestMarkers/",
      "pluginTests/",
      "quasiquotes/",
      "auxify/"
    )
    val jar = JarFile(handlerArtifact.toFile)
    try
      val offending = jar.entries().asScala
        .filter(entry => !entry.isDirectory && entry.getName.endsWith(".class"))
        .flatMap: entry =>
          val input = jar.getInputStream(entry)
          val text =
            try String(input.readAllBytes(), StandardCharsets.ISO_8859_1)
            finally input.close()
          forbidden.find(text.contains).map(value => s"${entry.getName}:$value")
        .toSeq
        .headOption

      offending match
        case Some(value) =>
          Left(
            Failure(
              "FORBIDDEN_HANDLER_DEPENDENCY",
              s"handler classfile linkage references a production-plugin or repository-fixture namespace: $value"
            )
          )
        case None => Right(())
    finally jar.close()

  private def inspectJar(
      path: Path,
      role: String
  ): Either[Failure, ArtifactSnapshot] =
    val normalized = normalize(path)
    if !Files.isRegularFile(normalized) then
      Left(
        Failure(
          "MISSING_ARTIFACT",
          s"$role artifact does not exist as a regular file: `$normalized`"
        )
      )
    else
      try
        val jar = JarFile(normalized.toFile)
        try
          Right(
            ArtifactSnapshot(
              normalized,
              jar.entries().asScala.map(_.getName).toSet
            )
          )
        finally jar.close()
      catch
        case NonFatal(error) =>
          Left(
            Failure(
              "WRONG_ARTIFACT_ROLE",
              s"$role artifact is not a readable JAR `$normalized`: ${error.getClass.getName}"
            )
          )

  private def requireEntries(
      snapshot: ArtifactSnapshot,
      role: String,
      required: Set[String]
  ): Either[Failure, Unit] =
    val missing = required -- snapshot.entries
    if missing.isEmpty then Right(())
    else
      Left(
        Failure(
          "WRONG_ARTIFACT_ROLE",
          s"$role artifact `${snapshot.path}` is missing ${missing.toList.sorted.mkString(", ")}"
        )
      )

  private def classEntry(className: String): String =
    className.replace('.', '/') + ".class"

  private def forbiddenPath(path: Path): Boolean =
    val value = path.toString.replace('\\', '/').toLowerCase
    Vector(
      "plugin-test-handlers",
      "plugin-test-markers",
      "plugin-tests",
      "plugin-api-handler-contract-probe",
      "composition-contract",
      "same-module",
      "quasiquotes",
      "auxify"
    ).exists(value.contains)

  private def forbiddenReference(value: String): Boolean =
    val normalized = value.replace('.', '/')
    normalized.startsWith("macroparadise/") ||
      normalized.startsWith("pluginTestHandlers/") ||
      normalized.startsWith("pluginTestMarkers/") ||
      normalized.startsWith("pluginTests/") ||
      normalized.startsWith("quasiquotes/") ||
      normalized.startsWith("auxify/") ||
      normalized.contains("composition-contract") ||
      normalized.contains("same-module")

  private def traverse[A, B](
      values: Vector[A]
  )(use: A => Either[Failure, B]): Either[Failure, Vector[B]] =
    values.foldLeft[Either[Failure, Vector[B]]](Right(Vector.empty)): (result, value) =>
      for
        accumulated <- result
        next <- use(value)
      yield accumulated :+ next

  private def normalize(path: Path): Path =
    path.toAbsolutePath.normalize
