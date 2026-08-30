package macroparadise.sbt

import java.io.File

final case class SameModuleHandlerBinding(
    annotationName: String,
    handlerClassName: String,
    markerSource: LabelledSource,
    handlerSource: LabelledSource
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

  private def validateToken(value: String, role: String): Unit =
    if (
      value == null ||
      value.trim.isEmpty ||
      value.exists(character => character == ':' || character == '\t' || character == '\n' || character == '\r')
    ) throw new IllegalArgumentException(role + " must be nonempty and contain no colons, tabs, or line breaks")
}
