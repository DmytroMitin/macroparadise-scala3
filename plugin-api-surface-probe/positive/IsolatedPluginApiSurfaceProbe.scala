package surfaceprobe

import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.*
import paradise3.api.helpers.ExpansionTransforms

final class IsolatedSurfaceProbeHandler extends ExpansionHandler:
  val annotationName = "surfaceProbe"
  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionEdit.finish(ExpansionEdit.start(input))

  def rawPowerEscapeHatch: ExpansionOutcome = ExpansionOutcome.Expanded(Nil)

  def structuredPower(changes: ExpansionChanges): ExpansionOutcome =
    ExpansionOutcome.Structured(changes)

  def primaryMemberTransform(
      member: untpd.Tree
  )(using Context): ExpansionEdit => Either[ExpansionDiagnostic, ExpansionEdit] =
    ExpansionTransforms.placeMemberInPrimary(member)

  def composedPrimaryEdit(
      edit: Either[ExpansionDiagnostic, ExpansionEdit],
      member: untpd.Tree
  )(using Context): Either[ExpansionDiagnostic, ExpansionEdit] =
    edit.flatMap(ExpansionTransforms.placeMemberInPrimary(member))

  def diagnosticRoundTrip(diagnostic: ExpansionDiagnostic): ExpansionDiagnostic = diagnostic

  def participantNames(input: ExpansionInput): List[String] =
    input.sourceOrderedHandledAnnotationNames

object IsolatedPluginApiSurfaceRuntime:
  def main(args: Array[String]): Unit =
    val handler = new IsolatedSurfaceProbeHandler()
    val api = classOf[ExpansionHandler]
    val handlerClass = handler.getClass
    val expand = handlerClass.getMethod("expand", classOf[ExpansionInput], classOf[Context])
    val annotationName = handlerClass.getMethod("annotationName")
    val participantNames = classOf[ExpansionInput].getMethod("sourceOrderedHandledAnnotationNames")

    val transformsClass = ExpansionTransforms.getClass
    val requiredTransformMethods = Set(
      "placeMemberInPrimary",
      "placeMembersInPrimary",
      "placeMemberInCompanion",
      "placeMembersInCompanion",
      "replacePrimaryAnnotations",
      "replaceCompanionAnnotations",
      "createSibling",
      "prepareTraitSelf"
    )
    val transformMethods = transformsClass.getDeclaredMethods.map(_.getName).toSet
    val catsPresent =
      try
        Class.forName("cats.Monad", false, transformsClass.getClassLoader)
        true
      catch
        case _: ClassNotFoundException => false

    require(api.isAssignableFrom(handlerClass), "handler does not implement the shared pluginApi interface")
    require(handler.annotationName == "surfaceProbe")
    require(annotationName.getReturnType == classOf[String])
    require(expand.getReturnType == classOf[ExpansionOutcome])
    require(participantNames.getReturnType.getName == "scala.collection.immutable.List")

    require(java.lang.reflect.Modifier.isPublic(transformsClass.getModifiers))
    require(requiredTransformMethods.subsetOf(transformMethods))
    require(!catsPresent, "isolated pluginApi unexpectedly requires Cats")

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
    println(s"apiIdentityShared=${api.isAssignableFrom(handlerClass)}")
    println(s"sourceOrderedHandledAnnotationNamesGetter=${participantNames.getReturnType.getName}")
    println(s"expandDescriptor=$expandDescriptor")
    println(s"transformsPublic=${java.lang.reflect.Modifier.isPublic(transformsClass.getModifiers)}")
    println(s"transformsCodeSource=${codeSource(transformsClass)}")
    println(s"transformsMethods=${requiredTransformMethods.toVector.sorted.mkString(",")}")
    println(s"catsPresent=$catsPresent")
