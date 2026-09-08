package surfaceprobe

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.*

final class IsolatedSurfaceProbeHandler extends ExpansionHandler:
  val annotationName = "surfaceProbe"
  val admissions = List(
    ExpansionAdmission(ExpansionTargetKind.Class, ExpansionShapeProfile.OrdinaryTemplate),
    ExpansionAdmission(ExpansionTargetKind.Trait, ExpansionShapeProfile.OrdinaryTemplate),
    ExpansionAdmission(ExpansionTargetKind.Object, ExpansionShapeProfile.NoTypeOrValueParameters)
  )

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionEdit.finish(ExpansionEdit.start(input))

  def rawPowerEscapeHatch: ExpansionOutcome = ExpansionOutcome.Expanded(Nil)

  def structuredPower(changes: ExpansionChanges): ExpansionOutcome =
    ExpansionOutcome.Structured(changes)

  def diagnosticRoundTrip(diagnostic: ExpansionDiagnostic): ExpansionDiagnostic = diagnostic

object IsolatedPluginApiSurfaceRuntime:
  def main(args: Array[String]): Unit =
    val handler = new IsolatedSurfaceProbeHandler()
    val api = classOf[ExpansionHandler]
    val handlerClass = handler.getClass
    val expand = handlerClass.getMethod("expand", classOf[ExpansionInput], classOf[Context])
    val annotationName = handlerClass.getMethod("annotationName")
    val admissions = handlerClass.getMethod("admissions")

    require(api.isAssignableFrom(handlerClass), "handler does not implement the shared pluginApi interface")
    require(handler.annotationName == "surfaceProbe")
    require(handler.admissions.map(_.targetKind).toSet == ExpansionTargetKind.values.toSet)
    require(annotationName.getReturnType == classOf[String])
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
    println(s"admissionCount=${handler.admissions.size}")
    println(s"admissionsReturnType=${admissions.getReturnType.getName}")
    println(s"apiIdentityShared=${api.isAssignableFrom(handlerClass)}")
    println(s"expandDescriptor=$expandDescriptor")
