package surfaceprobe

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.{
  AnnotationApplication,
  ExpansionDiagnostic,
  ExpansionInput,
  ExpansionOutcome,
  ParadiseAnnotationExpander,
  StructuredExpansionOutput
}
import paradise3.api.helpers.ExpansionHelpers

final class DefaultSurfaceProbeHandler extends ParadiseAnnotationExpander:
  val annotationName: String = "surfaceProbeDefault"

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    SurfaceProbeContracts.expand(input)

final class IsolatedSurfaceProbeHandler extends ParadiseAnnotationExpander:
  val annotationName: String = "surfaceProbe"
  override val consumesExistingCompanion: Boolean = true

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    SurfaceProbeContracts.expand(input)

  def rawPowerEscapeHatch: ExpansionOutcome =
    ExpansionOutcome.Expanded(Nil)

  def structuredPower(output: StructuredExpansionOutput): ExpansionOutcome =
    ExpansionOutcome.Structured(output)

  def diagnosticRoundTrip(
      diagnostic: ExpansionDiagnostic
  ): ExpansionDiagnostic = diagnostic

private object SurfaceProbeContracts:
  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionHelpers.withAnnotatedClassView(input): _ =>
      val application = AnnotationApplication.fromInput(input)
      application match
        case Right(_) =>
          ExpansionHelpers.structured(input.annotatedClass)
        case Left(diagnostic) =>
          ExpansionHelpers.rejected(diagnostic, input.annotatedClass)

object IsolatedPluginApiSurfaceRuntime:
  def main(args: Array[String]): Unit =
    val handler = new IsolatedSurfaceProbeHandler()
    val defaultHandler = new DefaultSurfaceProbeHandler()
    val api = classOf[ParadiseAnnotationExpander]
    val handlerClass = handler.getClass
    val expand = handlerClass.getMethod(
      "expand",
      classOf[ExpansionInput],
      classOf[Context]
    )
    val annotationName = handlerClass.getMethod("annotationName")
    val consumes = handlerClass.getMethod("consumesExistingCompanion")
    val apiIdentityShared = api.isAssignableFrom(handlerClass)

    require(apiIdentityShared, "handler does not implement the shared pluginApi interface")
    require(handler.annotationName == "surfaceProbe")
    require(handler.consumesExistingCompanion)
    require(!defaultHandler.consumesExistingCompanion)
    require(annotationName.getReturnType == classOf[String])
    require(consumes.getReturnType == java.lang.Boolean.TYPE)
    require(expand.getReturnType == classOf[ExpansionOutcome])

    def loaderName(value: Class[?]): String =
      Option(value.getClassLoader).fold("bootstrap")(_.getClass.getName)

    def codeSource(value: Class[?]): String =
      value.getProtectionDomain.getCodeSource.getLocation.toURI.getPath

    val expandDescriptor =
      s"(${expand.getParameterTypes.map(_.getName).mkString(",")})${expand.getReturnType.getName}"

    println(s"handlerClass=${handlerClass.getName}")
    println(s"handlerLoader=${loaderName(handlerClass)}")
    println(s"handlerCodeSource=${codeSource(handlerClass)}")
    println(s"apiClass=${api.getName}")
    println(s"apiLoader=${loaderName(api)}")
    println(s"apiCodeSource=${codeSource(api)}")
    println(s"annotationName=${handler.annotationName}")
    println(s"defaultConsumesExistingCompanion=${defaultHandler.consumesExistingCompanion}")
    println(s"overrideConsumesExistingCompanion=${handler.consumesExistingCompanion}")
    println(s"apiIdentityShared=$apiIdentityShared")
    println(s"expandDescriptor=$expandDescriptor")
