package macroparadise.sbt

import java.io.File

final case class SameModuleHandlerBinding(
    annotationName: String,
    handlerClassName: String,
    markerSource: LabelledSource,
    handlerSource: LabelledSource
)
final case class SameModuleEmbeddedBinding(
    annotationName: String,
    producerSource: LabelledSource
)


final case class DerivedSameModuleConfiguration(
    sourceIdentity: DerivedSourceIdentity,
    compilerOptions: Vector[String]
)

object SameModuleConfiguration {
  def derive(
      sourceRoot: File,
      handlerOutput: File,
      binding: SameModuleHandlerBinding
  ): DerivedSameModuleConfiguration = {
    validateToken(binding.annotationName, "annotation name")
    validateToken(binding.handlerClassName, "handler class name")
    val sourceIdentity =
      SourceIdentity.derive(
        sourceRoot,
        Seq(binding.markerSource, binding.handlerSource)
      )
    val sourcesByLabel = sourceIdentity.sources.map(source => source.label -> source).toMap
    val markerPath = sourcesByLabel(binding.markerSource.label).relativePath
    val handlerPath = sourcesByLabel(binding.handlerSource.label).relativePath
    val output = handlerOutput.getCanonicalFile.getAbsolutePath
    DerivedSameModuleConfiguration(
      sourceIdentity,
      Vector(
        "-Xplugin-require:macroparadise",
        "-P:macroparadise:handlerClasspath=" + output,
        "-P:macroparadise:sameModuleHandler=" +
          binding.annotationName + ":" +
          binding.handlerClassName + ":" +
          markerPath + ":" +
          handlerPath,
        "-P:macroparadise:sameModuleSourceIdentity=sha256:" + sourceIdentity.identity
      )
    )
  }

  def deriveEmbedded(
      sourceRoot: File,
      handlerOutput: File,
      binding: SameModuleEmbeddedBinding
  ): DerivedSameModuleConfiguration = {
    validateToken(binding.annotationName, "annotation name")
    val sourceIdentity = SourceIdentity.derive(sourceRoot, Seq(binding.producerSource))
    val producerPath = sourceIdentity.sources.head.relativePath
    val output = handlerOutput.getCanonicalFile.getAbsolutePath
    val bindingPayload = binding.annotationName + ":" + producerPath
    DerivedSameModuleConfiguration(
      sourceIdentity,
      Vector(
        "-Xplugin-require:macroparadise",
        "-Xplugin-require:macroparadise-embedded-producer",
        "-P:macroparadise:handlerClasspath=" + output,
        "-P:macroparadise:sameModuleEmbedded=" + bindingPayload,
        "-P:macroparadise:sameModuleSourceIdentity=sha256:" + sourceIdentity.identity,
        "-P:macroparadise-embedded-producer:sameModuleEmbedded=" + bindingPayload
      )
    )
  }

  private[sbt] def deriveEmbeddedPair(
      sourceRoot: File,
      handlerOutput: File,
      bindings: Seq[SameModuleEmbeddedBinding]
  ): DerivedSameModuleConfiguration = {
    val sourceIdentity = SourceIdentity.derive(
      sourceRoot,
      bindings.map(_.producerSource)
    )
    val sourcesByLabel = sourceIdentity.sources.map(source => source.label -> source).toMap
    val relationships = bindings.map { binding =>
      val producerPath = sourcesByLabel(binding.producerSource.label).relativePath
      (binding.annotationName, producerPath)
    }.sortBy(identity).map { case (annotationName, producerPath) =>
      annotationName + ":" + producerPath
    }.toVector
    val output = handlerOutput.getCanonicalFile.getAbsolutePath
    DerivedSameModuleConfiguration(
      sourceIdentity,
      Vector(
        "-Xplugin-require:macroparadise",
        "-Xplugin-require:macroparadise-embedded-producer",
        "-P:macroparadise:handlerClasspath=" + output
      ) ++ relationships.map("-P:macroparadise:sameModuleEmbedded=" + _) ++ Vector(
        "-P:macroparadise:sameModuleSourceIdentity=sha256:" + sourceIdentity.identity
      ) ++ relationships.map("-P:macroparadise-embedded-producer:sameModuleEmbedded=" + _)
    )
  }

  private[sbt] def derivePair(
      sourceRoot: File,
      handlerOutput: File,
      bindings: Seq[SameModuleHandlerBinding]
  ): DerivedSameModuleConfiguration = {
    val sorted = bindings.sortBy(binding => (binding.annotationName, binding.handlerClassName))
    sorted.foreach { binding =>
      validateToken(binding.annotationName, "annotation name")
      validateToken(binding.handlerClassName, "handler class name")
    }
    val sourceIdentity = SourceIdentity.derive(
      sourceRoot,
      sorted.flatMap(binding => Seq(binding.markerSource, binding.handlerSource))
    )
    val sourcesByLabel = sourceIdentity.sources.map(source => source.label -> source).toMap
    val relationships = sorted.map { binding =>
      val markerPath = sourcesByLabel(binding.markerSource.label).relativePath
      val handlerPath = sourcesByLabel(binding.handlerSource.label).relativePath
      "-P:macroparadise:sameModuleHandler=" +
        binding.annotationName + ":" +
        binding.handlerClassName + ":" +
        markerPath + ":" +
        handlerPath
    }
    val output = handlerOutput.getCanonicalFile.getAbsolutePath
    DerivedSameModuleConfiguration(
      sourceIdentity,
      Vector(
        "-Xplugin-require:macroparadise",
        "-P:macroparadise:handlerClasspath=" + output
      ) ++ relationships ++ Vector(
        "-P:macroparadise:sameModuleSourceIdentity=sha256:" + sourceIdentity.identity
      )
    )
  }

  private[sbt] def deriveSelected(
      sourceRoot: File,
      handlerOutput: File,
      external: Option[SameModuleHandlerBinding],
      embedded: Option[SameModuleEmbeddedBinding]
  ): Either[String, DerivedSameModuleConfiguration] =
    deriveSelected(sourceRoot, handlerOutput, external, Seq.empty, embedded, Seq.empty)

  private[sbt] def deriveSelected(
      sourceRoot: File,
      handlerOutput: File,
      external: Option[SameModuleHandlerBinding],
      externalPair: Seq[SameModuleHandlerBinding],
      embedded: Option[SameModuleEmbeddedBinding]
  ): Either[String, DerivedSameModuleConfiguration] =
    deriveSelected(sourceRoot, handlerOutput, external, externalPair, embedded, Seq.empty)

  private[sbt] def deriveSelected(
      sourceRoot: File,
      handlerOutput: File,
      external: Option[SameModuleHandlerBinding],
      externalPair: Seq[SameModuleHandlerBinding],
      embedded: Option[SameModuleEmbeddedBinding],
      embeddedPair: Seq[SameModuleEmbeddedBinding]
  ): Either[String, DerivedSameModuleConfiguration] = {
    val selected = Seq(
      external.nonEmpty,
      externalPair.nonEmpty,
      embedded.nonEmpty,
      embeddedPair.nonEmpty
    ).count(identity)

    if (selected == 0)
      Left("exactly one same-module binding mode is required; configure external or embedded")
    else if (selected > 1) {
      if (external.nonEmpty && externalPair.nonEmpty)
        Left("macroParadiseSameModuleBinding and macroParadiseSameModuleBindings are mutually exclusive")
      else if ((external.nonEmpty || externalPair.nonEmpty) && (embedded.nonEmpty || embeddedPair.nonEmpty))
        Left("same-module external and embedded binding modes are mutually exclusive")
      else
        Left("macroParadiseSameModuleEmbeddedBinding and macroParadiseSameModuleEmbeddedBindings are mutually exclusive")
    } else if (external.nonEmpty)
      Right(derive(sourceRoot, handlerOutput, external.get))
    else if (externalPair.nonEmpty) {
      if (externalPair.size == 1)
        Left("macroParadiseSameModuleBindings requires exactly two entries; use macroParadiseSameModuleBinding for one external relationship")
      else if (externalPair.size > 2)
        Left("macroParadiseSameModuleBindings supports exactly two external relationships")
      else
        validatePair(externalPair).flatMap { validated =>
          try Right(derivePair(sourceRoot, handlerOutput, validated))
          catch { case error: IllegalArgumentException => Left(error.getMessage) }
        }
    } else if (embedded.nonEmpty)
      Right(deriveEmbedded(sourceRoot, handlerOutput, embedded.get))
    else if (embeddedPair.size == 1)
      Left("macroParadiseSameModuleEmbeddedBindings requires exactly two entries; use macroParadiseSameModuleEmbeddedBinding for one embedded relationship")
    else if (embeddedPair.size > 2)
      Left("macroParadiseSameModuleEmbeddedBindings supports exactly two embedded relationships")
    else
      validateEmbeddedPair(embeddedPair).flatMap { validated =>
        try Right(deriveEmbeddedPair(sourceRoot, handlerOutput, validated))
        catch { case error: IllegalArgumentException => Left(error.getMessage) }
      }
  }

  private def validatePair(
      bindings: Seq[SameModuleHandlerBinding]
  ): Either[String, Vector[SameModuleHandlerBinding]] = {
    val sorted = bindings.toVector.sortBy(binding => (
      Option(binding.annotationName).getOrElse(""),
      Option(binding.handlerClassName).getOrElse("")
    ))
    val invalidToken = sorted.iterator.flatMap { binding =>
      Seq(
        validatePairIdentity(binding.annotationName, "annotation name"),
        validatePairIdentity(binding.handlerClassName, "handler class name")
      ).collectFirst { case Left(message) => message }
    }.toSeq.headOption
    invalidToken match {
      case Some(message) => Left(message)
      case None =>
        val duplicateAnnotations = duplicates(sorted.map(_.annotationName))
        val duplicateHandlers = duplicates(sorted.map(_.handlerClassName))
        if (duplicateAnnotations.nonEmpty)
          Left("duplicate same-module annotation names: " + duplicateAnnotations.mkString(", "))
        else if (duplicateHandlers.nonEmpty)
          Left("duplicate same-module handler class names: " + duplicateHandlers.mkString(", "))
        else Right(sorted)
    }
  }

  private def validateEmbeddedPair(
      bindings: Seq[SameModuleEmbeddedBinding]
  ): Either[String, Vector[SameModuleEmbeddedBinding]] = {
    val sorted = bindings.toVector.sortBy(binding => (
      Option(binding.annotationName).getOrElse(""),
      Option(binding.producerSource).map(_.relativePath).getOrElse("")
    ))
    val invalidToken = sorted.iterator
      .map(binding => validateEmbeddedPairIdentity(binding.annotationName))
      .collectFirst { case Left(message) => message }
    invalidToken match {
      case Some(message) => Left(message)
      case None =>
        val duplicateAnnotations = duplicates(sorted.map(_.annotationName))
        if (duplicateAnnotations.nonEmpty)
          Left("duplicate same-module annotation names: " + duplicateAnnotations.mkString(", "))
        else Right(sorted)
    }
  }

  private def validateEmbeddedPairIdentity(value: String): Either[String, Unit] = {
    val valid =
      value != null &&
        value.contains('.') &&
        value.split("\\.", -1).forall(validIdentifier)
    if (valid) Right(())
    else Left(
      "macroParadiseSameModuleEmbeddedBindings annotation names must be qualified canonical identifier chains: " +
        Option(value).getOrElse("null")
    )
  }

  private def validatePairIdentity(value: String, role: String): Either[String, Unit] = {
    val valid =
      value != null &&
        value.contains('.') &&
        value.split("\\.", -1).forall(validIdentifier)
    if (valid) Right(())
    else
      Left(
        "macroParadiseSameModuleBindings " + role +
          "s must be qualified canonical identifier chains: " + Option(value).getOrElse("null")
      )
  }

  private def validIdentifier(value: String): Boolean =
    value.nonEmpty &&
      Character.isJavaIdentifierStart(value.codePointAt(0)) &&
      value.codePoints().skip(1).allMatch(Character.isJavaIdentifierPart(_))

  private def duplicates(values: Seq[String]): Vector[String] =
    values.groupBy(identity).collect { case (value, entries) if entries.size > 1 => value }.toVector.sorted

  private def validateToken(value: String, role: String): Unit =
    if (
      value == null ||
      value.trim.isEmpty ||
      value.exists(character => character == ':' || character == '\t' || character == '\n' || character == '\r')
    ) throw new IllegalArgumentException(role + " must be nonempty and contain no colons, tabs, or line breaks")
}
