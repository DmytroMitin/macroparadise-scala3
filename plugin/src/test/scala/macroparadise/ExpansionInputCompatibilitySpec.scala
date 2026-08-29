package macroparadise

import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.ast.Trees
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Contexts.{Context, ContextBase}
import dotty.tools.dotc.parsing.Parsers
import paradise3.api.{ExpansionCompositionPolicy, ExpansionInput, ExpansionOutcome, ExpansionTargetProfile, ParadiseAnnotationExpander}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

class ExpansionInputCompatibilitySpec extends munit.FunSuite:
  test("external handlers default to the common class-only target profile") {
    val handler = new ParadiseAnnotationExpander:
      val annotationName: String = "ArbitraryDefaultHandler"
      def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
        ExpansionOutcome.NotApplicable

    assertEquals(handler.targetProfile, ExpansionTargetProfile.CommonClassOnly)
    assertEquals(
      handler.compositionPolicy,
      ExpansionCompositionPolicy.StandaloneOnly
    )
    assert(
      classOf[ParadiseAnnotationExpander]
        .getMethod("targetProfile")
        .isDefault
    )
    assert(
      classOf[ParadiseAnnotationExpander]
        .getMethod("compositionPolicy")
        .isDefault
    )
  }

  test("production composition admission maps the snapshotted capability without handler rereads") {
    val source = Files.readString(
      Path.of("plugin/src/main/scala/macroparadise/MacroParadisePlugin.scala"),
      StandardCharsets.UTF_8
    )
    val descriptorSource = Files.readString(
      Path.of("plugin/src/main/scala/macroparadise/ExternalHandlerDescriptor.scala"),
      StandardCharsets.UTF_8
    )
    assert(source.contains("descriptor.compositionPolicy"))
    assert(!source.contains("handler.compositionPolicy"))
    assertEquals(descriptorSource.split("instance.compositionPolicy", -1).length - 1, 1)
    assert(source.contains("compositionAdmission(typeDef, matchingAnnotations)"))
    assert(!source.contains("SupportedCompositionPolicy"))
    assert(!source.contains("SupportedPair("))
  }

  test("production target admission maps the snapshotted profile without annotation-name magic") {
    val source = Files.readString(
      Path.of("plugin/src/main/scala/macroparadise/MacroParadisePlugin.scala"),
      StandardCharsets.UTF_8
    )
    val descriptorSource = Files.readString(
      Path.of("plugin/src/main/scala/macroparadise/ExternalHandlerDescriptor.scala"),
      StandardCharsets.UTF_8
    )
    assert(source.contains("descriptor.targetProfile match"))
    assert(!source.contains("handler.targetProfile"))
    assertEquals(descriptorSource.split("instance.targetProfile", -1).length - 1, 1)
    assert(source.contains("ExternalExpansionTargetProfile.RestrictedGenericTraitApply"))
    assert(source.contains("ExternalExpansionTargetProfile.TwoUpperBoundedGenericTrait"))
    assert(source.contains("ExternalExpansionTargetProfile.PlainZeroParameterTrait"))
    assert(source.contains("AnnotatedClassAdmission.twoUpperBoundedGenericTraitRejection"))
    assert(source.contains("AnnotatedClassAdmission.plainZeroParameterTraitRejection"))
    assert(!source.contains("annotationName == \"externalRestrictedTraitApply\""))

    val fixture = readHandler("ExternalRestrictedTraitApplyExpander.scala")
    assert(fixture.contains("override val targetProfile: ExpansionTargetProfile"))
    assert(fixture.contains("ExpansionTargetProfile.RestrictedGenericTraitApply"))
  }

  test("ExpansionInput retains the five-field constructor and product shape") {
    val (annotated, context) = parsedClass("class Compatibility")
    given Context = context
    val input = ExpansionInput("externalDebug", annotated, None, Set("Compatibility"), None)

    assertEquals(input.productArity, 5)
    assertEquals(
      input.productElementNames.toList,
      List("annotationName", "annotatedClass", "existingCompanion", "topLevelNames", "currentAnnotation")
    )
    assert(classOf[ExpansionInput].getDeclaredConstructors.exists(_.getParameterCount == 5))
    assert(!classOf[ExpansionInput].getDeclaredConstructors.exists(_.getParameterCount == 6))
  }

  test("ExpansionInput copy extractors accessors and currentAnnotation default remain compatible") {
    val (annotated, context) = parsedClass("class Compatibility")
    given Context = context
    val defaulted = ExpansionInput("externalDebug", annotated, None, Set("Compatibility"))
    val copied = defaulted.copy(
      annotationName = defaulted.annotationName,
      annotatedClass = defaulted.annotatedClass,
      existingCompanion = defaulted.existingCompanion,
      topLevelNames = defaulted.topLevelNames,
      currentAnnotation = defaulted.currentAnnotation
    )

    assertEquals(defaulted.currentAnnotation, None)
    assertEquals(copied, defaulted)
    copied match
      case ExpansionInput(annotationName, rawClass, existingCompanion, topLevelNames, currentAnnotation) =>
        assertEquals(annotationName, "externalDebug")
        assert(rawClass eq annotated)
        assertEquals(existingCompanion, None)
        assertEquals(topLevelNames, Set("Compatibility"))
        assertEquals(currentAnnotation, None)
  }

  test("new view convenience leaves every existing raw escape-hatch accessor intact") {
    val (annotated, context) = parsedClass("class RawPower(value: String)")
    given Context = context
    val annotation = Trees.mods(annotated).annotations.headOption
    val input = ExpansionInput("externalMarker", annotated, None, Set("RawPower", "Neighbor"), annotation)

    assert(input.annotatedClass eq annotated)
    assertEquals(input.currentAnnotation, annotation)
    assertEquals(input.existingCompanion, None)
    assertEquals(input.topLevelNames, Set("RawPower", "Neighbor"))
    assertEquals(input.className, "RawPower")
    assert(input.annotatedClassView.isRight)
  }

  test("selected precompiled handlers use the view while ExternalMarker retains raw input power") {
    val viewBackedHandlers = List(
      "ExternalDebugExpander.scala",
      "ExternalCompanionDebugExpander.scala",
      "ExternalSiblingDebugExpander.scala",
      "ExternalLabelExpander.scala",
      "ExternalTypedLabelExpander.scala"
    )
    viewBackedHandlers.foreach: fileName =>
      val source = readHandler(fileName)
      assert(
        source.contains("ExpansionHelpers.withAnnotatedClassView(input)"),
        s"$fileName must use the shared authoring adapter"
      )
      assert(!source.contains("annotatedClass.rhs match"), s"$fileName must not repeat raw envelope matching")

    val typedLabelSource = readHandler("ExternalTypedLabelExpander.scala")
    assert(typedLabelSource.contains("case Left(diagnostic)"))
    assert(typedLabelSource.contains("ExpansionHelpers.rejected(diagnostic, input.annotatedClass)"))
    assert(!typedLabelSource.contains("ExpansionOutcome.Rejected"))

    val rawSource = readHandler("ExternalMarkerExpander.scala")
    assert(rawSource.contains("input.annotatedClass.rhs match"))
    assert(rawSource.contains("untpd.cpy.TypeDef"))
    assert(rawSource.contains("ExpansionOutcome.Expanded"))
  }

  private def parsedClass(code: String): (TypeDef, Context) =
    val unit = CompilationUnit("ExpansionInputCompatibilitySpec.scala", code)
    val context = ContextBase().initialCtx.fresh.setCompilationUnit(unit)
    val parsed = new Parsers.Parser(unit.source)(using context).parse()
    val stats = parsed match
      case PackageDef(_, values) => values
      case tree => List(tree)
    val annotated = stats.collectFirst { case value: TypeDef => value }.getOrElse(fail(s"missing class in $stats"))
    (annotated, context)

  private def readHandler(fileName: String): String =
    Files.readString(
      Path.of("plugin-test-handlers/src/main/scala/demo", fileName),
      StandardCharsets.UTF_8
    )
