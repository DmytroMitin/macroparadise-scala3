package macroparadise

import dotty.tools.dotc.config.Properties

import java.nio.file.Path
import java.util.regex.Pattern

import scala.util.Try

/** Narrow executable preconsumer precheck packaged with the production plugin.
  *
  * This command is an experimental exact-build diagnostic entrypoint, not a
  * public handler library API. It constructs and snapshots the configured
  * handler but never expands user code.
  */
object ExternalHandlerPrecheckMain:
  import ExternalHandlerPrecheck.*

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

  def main(args: Array[String]): Unit =
    execute(args, getClass.getClassLoader) match
      case Right(success) =>
        println(
          s"EXTERNAL_HANDLER_AUTHORING_PRECHECK_READY " +
            s"PRECONSUMER_HANDLER_DECLARATION_AND_BINDING_PRECHECK_READY ${success.render}"
        )
      case Left(failure) =>
        System.err.println(s"EXTERNAL_HANDLER_AUTHORING_PRECHECK_FAILED ${failure.render}")
        System.exit(2)

  private[macroparadise] def execute(
      args: Array[String],
      parentLoader: ClassLoader
  ): Either[Failure, Success] =
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
    for
      values <- parseArguments(args.toVector)
      missing = RequiredKeys.filterNot(values.contains)
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
        artifacts = ArtifactPaths(
          plugin = Path.of(values("plugin")),
          pluginApi = Path.of(values("plugin-api")),
          marker = Path.of(values("marker")),
          handler = Path.of(values("handler"))
        ),
        handlerCompileClasspath = compileClasspath,
        markerClassName = values("marker-class"),
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
      args: Vector[String]
  ): Either[Failure, Map[String, String]] =
    args.foldLeft[Either[Failure, Map[String, String]]](Right(Map.empty)):
      (result, argument) =>
        result.flatMap: accumulated =>
          argument match
            case value if value.startsWith("--") && value.contains('=') =>
              val separator = value.indexOf('=')
              val key = value.substring(2, separator)
              val raw = value.substring(separator + 1)
              if !RequiredKeys.contains(key) then
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
