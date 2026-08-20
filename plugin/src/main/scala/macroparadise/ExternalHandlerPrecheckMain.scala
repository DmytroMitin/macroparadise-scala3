package macroparadise

import dotty.tools.dotc.config.Properties
import paradise3.api.ParadiseAnnotationExpander

import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern
import java.util.jar.JarFile

import scala.util.Try
import scala.util.control.NonFatal

/** Narrow executable preconsumer precheck packaged with the production plugin.
  *
  * This command is an experimental exact-build diagnostic entrypoint, not a
  * public handler library API. It constructs and snapshots the configured
  * handler but never expands user code.
  */
object ExternalHandlerPrecheckMain:
  import ExternalHandlerPrecheck.*

  private[macroparadise] val usage: String =
    """Usage:
      |  Explicit mode (maximum caller-supplied independent expectations):
      |  java -cp <plugin-and-exact-runtime-classpath> macroparadise.ExternalHandlerPrecheckMain \
      |    --plugin=<plugin.jar> \
      |    --plugin-api=<plugin-api.jar> \
      |    --marker=<marker.jar> \
      |    --handler=<handler.jar> \
      |    --handler-compile-classpath=<path-list> \
      |    --marker-class=<qualified-marker-class> \
      |    --expected-handler-class=<qualified-handler-class> \
      |    --expected-annotation=<qualified-annotation-name> \
      |    --expected-scala-version=<exact-version> \
      |    --expected-jdk-major=<major>
      |
      |  Compact mode (bounded runtime derivation):
      |  java -cp <plugin-and-exact-runtime-classpath> macroparadise.ExternalHandlerPrecheckMain \
      |    --compact \
      |    --marker=<marker.jar> \
      |    --handler=<handler.jar> \
      |    --handler-compile-classpath=<path-list> \
      |    --expected-handler-class=<qualified-handler-class> \
      |    --expected-annotation=<qualified-annotation-name> \
      |    --expected-scala-version=<exact-version> \
      |    --expected-jdk-major=<major>
      |
      |Compact derived witnesses:
      |  plugin: executing ExternalHandlerPrecheckMain code source
      |  runtime plugin-api: parent-loaded ParadiseAnnotationExpander code source (embedded in a self-contained plugin)
      |  authoring plugin-api: the unique contract JAR on handler-compile-classpath
      |  marker-class: canonical expected-annotation identity
      |
      |Artifact roles:
      |  plugin: packaged production compiler plugin containing this command
      |  plugin-api: packaged experimental handler contract
      |  marker: separately compiled annotation and runtime handler metadata
      |  handler: separately compiled ParadiseAnnotationExpander implementation
      |  handler-compile-classpath: plugin-api plus the exact compiler/runtime artifacts used to compile the handler
      |
      |Preconsumer guarantee:
      |  failures stop with stage=preconsumer consumerCompilationStarted=false expansionInvoked=false
      |
      |Metadata authoring failure context (when available):
      |  failureStage=<metadata-selection|handler-artifact|handler-loading|handler-contract|handler-construction|handler-declaration|metadata-binding>
      |  markerIdentity=<qualified-marker-class> expectedAnnotation=<qualified-annotation-name>
      |  metadataHandler=<marker-declared-handler> expectedHandler=<caller-expected-handler>
      |  markerArtifact=<marker.jar> handlerArtifact=<handler.jar>
      |  malformed marker handler metadata uses category=INVALID_METADATA_HANDLER_CLASS_NAME
      |
      |Use --help to print this usage without running the precheck.
      |""".stripMargin

  private val RequiredKeys = Vector(
    "plugin",
    "plugin-api",
    "marker",
    "handler",
    "handler-compile-classpath",
    "marker-class",
    "expected-handler-class",
    "expected-annotation",
    "expected-scala-version",
    "expected-jdk-major"
  )

  private val CompactRequiredKeys = Vector(
    "marker",
    "handler",
    "handler-compile-classpath",
    "expected-handler-class",
    "expected-annotation",
    "expected-scala-version",
    "expected-jdk-major"
  )

  private[macroparadise] final case class RuntimeArtifacts(
      plugin: Path,
      pluginApi: Path
  )

  def main(args: Array[String]): Unit =
    if helpRequested(args) then println(usage)
    else
      execute(args, getClass.getClassLoader) match
        case Right(success) =>
          println(
            s"EXTERNAL_HANDLER_AUTHORING_PRECHECK_READY " +
              s"PRECONSUMER_HANDLER_DECLARATION_AND_BINDING_PRECHECK_READY ${success.render}"
          )
        case Left(failure) =>
          System.err.println(renderFailure(failure))
          System.exit(2)

  private[macroparadise] def helpRequested(args: Array[String]): Boolean =
    args.toVector == Vector("--help")

  private[macroparadise] def renderFailure(failure: Failure): String =
    s"EXTERNAL_HANDLER_AUTHORING_PRECHECK_FAILED stage=preconsumer " +
      s"consumerCompilationStarted=false expansionInvoked=false ${failure.render}\n$usage"

  private[macroparadise] def execute(
      args: Array[String],
      parentLoader: ClassLoader
  ): Either[Failure, Success] =
    if args.contains("--compact") then
      deriveRuntimeArtifacts().flatMap: runtimeArtifacts =>
        parseCompact(
          args,
          parentLoader,
          actualScalaVersion = Properties.versionNumberString,
          actualJdkMajor = Runtime.version().feature(),
          runtimeArtifacts
        ).flatMap(ExternalHandlerPrecheck.run)
    else
      parse(
        args,
        parentLoader,
        actualScalaVersion = Properties.versionNumberString,
        actualJdkMajor = Runtime.version().feature()
      ).flatMap(ExternalHandlerPrecheck.run)

  private[macroparadise] def parse(
      args: Array[String],
      parentLoader: ClassLoader,
      actualScalaVersion: String,
      actualJdkMajor: Int
  ): Either[Failure, Request] =
    parseRequest(
      args,
      RequiredKeys,
      parentLoader,
      actualScalaVersion,
      actualJdkMajor
    )(
      values =>
        ArtifactPaths(
          plugin = Path.of(values("plugin")),
          pluginApi = Path.of(values("plugin-api")),
          marker = Path.of(values("marker")),
          handler = Path.of(values("handler"))
        ),
      values => values("marker-class")
    )

  private[macroparadise] def parseCompact(
      args: Array[String],
      parentLoader: ClassLoader,
      actualScalaVersion: String,
      actualJdkMajor: Int,
      runtimeArtifacts: RuntimeArtifacts
  ): Either[Failure, Request] =
    val modeCount = args.count(_ == "--compact")
    if modeCount != 1 then
      Left(
        Failure(
          "PRECHECK_ARGUMENT_FAILURE",
          s"compact mode requires exactly one --compact flag; found $modeCount"
        )
      )
    else
      parseRequest(
        args.filterNot(_ == "--compact"),
        CompactRequiredKeys,
        parentLoader,
        actualScalaVersion,
        actualJdkMajor
      )(
        values =>
          ArtifactPaths(
            plugin = runtimeArtifacts.plugin,
            pluginApi = runtimeArtifacts.pluginApi,
            marker = Path.of(values("marker")),
            handler = Path.of(values("handler"))
        ),
        values => values("expected-annotation")
      ).flatMap: request =>
        selectCompactAuthoringApi(
          runtimeArtifacts,
          request.handlerCompileClasspath
        ).map: authoringApi =>
          request.copy(
            artifacts = request.artifacts.copy(pluginApi = authoringApi)
          )

  private def parseRequest(
      args: Array[String],
      requiredKeys: Vector[String],
      parentLoader: ClassLoader,
      actualScalaVersion: String,
      actualJdkMajor: Int
  )(
      artifacts: Map[String, String] => ArtifactPaths,
      markerClassName: Map[String, String] => String
  ): Either[Failure, Request] =
    for
      values <- parseArguments(args.toVector, requiredKeys)
      missing = requiredKeys.filterNot(values.contains)
      _ <-
        missing.headOption match
          case Some(key) =>
            Left(
              Failure(
                "PRECHECK_ARGUMENT_FAILURE",
                s"missing required argument --$key"
              )
            )
          case None => Right(())
      expectedJdk <-
        Try(values("expected-jdk-major").toInt).toOption match
          case Some(value) if value > 0 => Right(value)
          case _ =>
            Left(
              Failure(
                "PRECHECK_ARGUMENT_FAILURE",
                s"--expected-jdk-major must be a positive integer; found `${values("expected-jdk-major")}`"
              )
            )
      compileClasspath = values("handler-compile-classpath")
        .split(Pattern.quote(java.io.File.pathSeparator), -1)
        .toVector
        .filter(_.nonEmpty)
        .map(Path.of(_))
      _ <-
        if compileClasspath.nonEmpty then Right(())
        else
          Left(
            Failure(
              "PRECHECK_ARGUMENT_FAILURE",
              "--handler-compile-classpath must contain at least one explicit artifact path"
            )
          )
    yield
      Request(
        artifacts = artifacts(values),
        handlerCompileClasspath = compileClasspath,
        markerClassName = markerClassName(values),
        expectedHandlerClassName = values("expected-handler-class"),
        expectedAnnotationName = values("expected-annotation"),
        environment = Environment(
          expectedScalaVersion = values("expected-scala-version"),
          actualScalaVersion = actualScalaVersion,
          expectedJdkMajor = expectedJdk,
          actualJdkMajor = actualJdkMajor
        ),
        parentLoader = parentLoader
      )

  private def parseArguments(
      args: Vector[String],
      allowedKeys: Vector[String]
  ): Either[Failure, Map[String, String]] =
    args.foldLeft[Either[Failure, Map[String, String]]](Right(Map.empty)):
      (result, argument) =>
        result.flatMap: accumulated =>
          argument match
            case value if value.startsWith("--") && value.contains('=') =>
              val separator = value.indexOf('=')
              val key = value.substring(2, separator)
              val raw = value.substring(separator + 1)
              if !allowedKeys.contains(key) then
                Left(
                  Failure(
                    "PRECHECK_ARGUMENT_FAILURE",
                    s"unknown argument --$key"
                  )
                )
              else if accumulated.contains(key) then
                Left(
                  Failure(
                    "PRECHECK_ARGUMENT_FAILURE",
                    s"duplicate argument --$key"
                  )
                )
              else if raw.isEmpty then
                Left(
                  Failure(
                    "PRECHECK_ARGUMENT_FAILURE",
                    s"argument --$key must not be empty"
                  )
                )
              else Right(accumulated.updated(key, raw))
            case other =>
              Left(
                Failure(
                  "PRECHECK_ARGUMENT_FAILURE",
                  s"expected --key=value argument, found `$other`"
                )
              )

  private def deriveRuntimeArtifacts(): Either[Failure, RuntimeArtifacts] =
    for
      plugin <- artifactPathFromCodeSource(getClass, "plugin")
      pluginApi <- artifactPathFromCodeSource(
        classOf[ParadiseAnnotationExpander],
        "plugin-api"
      )
    yield RuntimeArtifacts(plugin, pluginApi)

  private[macroparadise] def selectCompactAuthoringApi(
      runtimeArtifacts: RuntimeArtifacts,
      handlerCompileClasspath: Vector[Path]
  ): Either[Failure, Path] =
    val plugin = runtimeArtifacts.plugin.toAbsolutePath.normalize
    val runtimeApi = runtimeArtifacts.pluginApi.toAbsolutePath.normalize

    if runtimeApi != plugin then Right(runtimeApi)
    else
      val requiredEntries = Set(
        "paradise3/api/ParadiseAnnotationExpander.class",
        "paradise3/api/expander.class"
      )
      val candidates = handlerCompileClasspath
        .map(_.toAbsolutePath.normalize)
        .distinct
        .filter(path => path != plugin && containsAllEntries(path, requiredEntries))

      candidates match
        case Vector(candidate) => Right(candidate)
        case _ =>
          Left(
            Failure(
              "COMPACT_PRECHECK_DERIVATION_FAILURE",
              s"self-contained plugin requires exactly one ordinary authoring plugin API JAR on handler compile classpath; found ${candidates.size}"
            )
          )

  private def containsAllEntries(path: Path, requiredEntries: Set[String]): Boolean =
    if !Files.isRegularFile(path) || !path.getFileName.toString.endsWith(".jar") then false
    else
      try
        val jar = JarFile(path.toFile)
        try requiredEntries.forall(entry => jar.getJarEntry(entry) != null)
        finally jar.close()
      catch case NonFatal(_) => false

  private[macroparadise] def artifactPathFromCodeSource(
      clazz: Class[?],
      role: String
  ): Either[Failure, Path] =
    val location =
      Option(clazz.getProtectionDomain)
        .flatMap(domain => Option(domain.getCodeSource))
        .flatMap(source => Option(source.getLocation))

    location match
      case None =>
        Left(
          Failure(
            "COMPACT_PRECHECK_DERIVATION_FAILURE",
            s"$role runtime class `${clazz.getName}` has no code-source location"
          )
        )
      case Some(url) =>
        try
          val path = Path.of(url.toURI).toAbsolutePath.normalize
          if url.getProtocol == "file" && Files.isRegularFile(path) && path.getFileName.toString.endsWith(".jar") then
            Right(path)
          else
            Left(
              Failure(
                "COMPACT_PRECHECK_DERIVATION_FAILURE",
                s"$role runtime class `${clazz.getName}` must come from one local regular JAR file; found `$url`"
              )
            )
        catch
          case NonFatal(error) =>
            Left(
              Failure(
                "COMPACT_PRECHECK_DERIVATION_FAILURE",
                s"$role runtime class `${clazz.getName}` has unreadable code source `$url`: ${error.getClass.getName}"
              )
            )
