package macroparadise

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.{
  ExpansionCompositionPolicy,
  ExpansionInput,
  ExpansionOutcome,
  ExpansionTargetProfile,
  ParadiseAnnotationExpander
}

class ExternalHandlerDescriptorSpec extends munit.FunSuite:
  test("valid declaration and default capabilities are captured exactly once") {
    val handler = InstrumentedHandler()

    val loaded = captured(handler)

    assertEquals(loaded.descriptor.annotationName, "descriptorProbe")
    assertEquals(
      loaded.descriptor.targetProfile,
      ExpansionTargetProfile.CommonClassOnly
    )
    assertEquals(loaded.descriptor.consumesExistingCompanion, false)
    assertEquals(
      loaded.descriptor.compositionPolicy,
      ExpansionCompositionPolicy.StandaloneOnly
    )
    assertEquals(handler.annotationReads, 1)
    assertEquals(handler.profileReads, 1)
    assertEquals(handler.companionReads, 1)
    assertEquals(handler.compositionReads, 1)
  }

  test("restricted trait and companion capabilities are captured exactly once") {
    val handler = InstrumentedHandler(
      profileValues = List(ExpansionTargetProfile.RestrictedGenericTraitApply),
      companionValues = List(true),
      compositionValues = List(ExpansionCompositionPolicy.SourceOrdered)
    )

    val loaded = captured(handler)

    assertEquals(
      loaded.descriptor.targetProfile,
      ExpansionTargetProfile.RestrictedGenericTraitApply
    )
    assertEquals(loaded.descriptor.consumesExistingCompanion, true)
    assertEquals(
      loaded.descriptor.compositionPolicy,
      ExpansionCompositionPolicy.SourceOrdered
    )
    assertEquals(handler.annotationReads, 1)
    assertEquals(handler.profileReads, 1)
    assertEquals(handler.companionReads, 1)
    assertEquals(handler.compositionReads, 1)
  }

  test("alternating accessors cannot change an immutable descriptor") {
    val handler = InstrumentedHandler(
      annotationValues = List("firstMarker", "secondMarker"),
      profileValues = List(
        ExpansionTargetProfile.RestrictedGenericTraitApply,
        ExpansionTargetProfile.CommonClassOnly
      ),
      companionValues = List(true, false),
      compositionValues = List(
        ExpansionCompositionPolicy.SourceOrdered,
        ExpansionCompositionPolicy.StandaloneOnly
      )
    )

    val loaded = captured(handler)

    assertEquals(loaded.descriptor.annotationName, "firstMarker")
    assertEquals(
      loaded.descriptor.targetProfile,
      ExpansionTargetProfile.RestrictedGenericTraitApply
    )
    assertEquals(loaded.descriptor.consumesExistingCompanion, true)
    assertEquals(
      loaded.descriptor.compositionPolicy,
      ExpansionCompositionPolicy.SourceOrdered
    )
    assertEquals(handler.annotationReads, 1)
    assertEquals(handler.profileReads, 1)
    assertEquals(handler.companionReads, 1)
    assertEquals(handler.compositionReads, 1)
  }

  List("annotationName", "targetProfile", "consumesExistingCompanion", "compositionPolicy").foreach:
    accessor =>
      test(s"$accessor NonFatal failure is a controlled loading diagnostic") {
        assertAccessorFailure(accessor, new IllegalStateException(s"broken  $accessor"))
      }

      test(s"$accessor LinkageError is a controlled loading diagnostic") {
        assertAccessorFailure(accessor, new LinkageError(s"missing  $accessor"))
      }

  test("null annotation name is rejected at descriptor creation") {
    val failure = failed(InstrumentedHandler(annotationValues = List(null)))

    assertDiagnostic(
      failure,
      "INVALID_HANDLER_ANNOTATION_NAME",
      "annotationName"
    )
  }

  test("blank annotation name is rejected at descriptor creation") {
    val failure = failed(InstrumentedHandler(annotationValues = List("  \t")))

    assertDiagnostic(
      failure,
      "INVALID_HANDLER_ANNOTATION_NAME",
      "annotationName"
    )
  }

  test("qualified annotation identity is retained exactly") {
    val loaded = captured(
      InstrumentedHandler(annotationValues = List("qualifiedone.audit"))
    )

    assertEquals(loaded.descriptor.annotationName, "qualifiedone.audit")
  }

  test("non-canonical qualified annotation identity is rejected") {
    val failure = failed(
      InstrumentedHandler(annotationValues = List("qualifiedone..audit"))
    )

    assertDiagnostic(
      failure,
      "INVALID_HANDLER_ANNOTATION_NAME",
      "annotationName"
    )
  }

  test("null target profile is rejected at descriptor creation") {
    val failure = failed(InstrumentedHandler(profileValues = List(null)))

    assertDiagnostic(failure, "NULL_TARGET_PROFILE", "targetProfile")
  }

  test("null composition policy is rejected at descriptor creation") {
    val failure = failed(InstrumentedHandler(compositionValues = List(null)))

    assertDiagnostic(failure, "NULL_COMPOSITION_POLICY", "compositionPolicy")
  }

  test("descriptor failure never invokes expand") {
    val handler = InstrumentedHandler(annotationFailure = Some(new RuntimeException("stop")))

    failed(handler)

    assertEquals(handler.expandCalls, 0)
  }

  private def assertAccessorFailure(accessor: String, error: Throwable): Unit =
    val handler =
      accessor match
        case "annotationName" => InstrumentedHandler(annotationFailure = Some(error))
        case "targetProfile" => InstrumentedHandler(profileFailure = Some(error))
        case "consumesExistingCompanion" => InstrumentedHandler(companionFailure = Some(error))
        case "compositionPolicy" => InstrumentedHandler(compositionFailure = Some(error))
        case other => fail(s"unknown accessor $other")

    val failure = failed(handler)

    assertDiagnostic(failure, "HANDLER_DECLARATION_FAILURE", accessor)
    assert(failure.diagnostic.contains(s"cause=${error.getClass.getName}"), failure.diagnostic)
    assert(failure.diagnostic.contains(s"message=${error.getMessage.replaceAll("\\s+", " ")}"), failure.diagnostic)
    assert(failure.diagnostic.contains("loaderPolicy=parent-first"), failure.diagnostic)
    assert(failure.diagnostic.contains("requestedLoader="), failure.diagnostic)
    assert(failure.diagnostic.contains("handlerLoader="), failure.diagnostic)
    assertEquals(handler.expandCalls, 0)

  private def assertDiagnostic(
      failure: ExternalHandlerDescriptor.Failure,
      category: String,
      accessor: String
  ): Unit =
    assert(failure.diagnostic.contains("stage=loading"), failure.diagnostic)
    assert(failure.diagnostic.contains(s"category=$category"), failure.diagnostic)
    assert(failure.diagnostic.contains(s"accessor=$accessor"), failure.diagnostic)
    assert(failure.diagnostic.contains("handler=macroparadise.ExternalHandlerDescriptorSpec$InstrumentedHandler"), failure.diagnostic)

  private def captured(handler: InstrumentedHandler): LoadedExternalHandler =
    ExternalHandlerDescriptor.capture(handler, getClass.getClassLoader) match
      case Right(loaded) => loaded
      case Left(failure) => fail(failure.diagnostic)

  private def failed(
      handler: InstrumentedHandler
  ): ExternalHandlerDescriptor.Failure =
    ExternalHandlerDescriptor.capture(handler, getClass.getClassLoader) match
      case Left(failure) => failure
      case Right(loaded) => fail(s"unexpected descriptor: ${loaded.descriptor}")

  private final case class InstrumentedHandler(
      annotationValues: List[String] = List("descriptorProbe"),
      profileValues: List[ExpansionTargetProfile] = List(
        ExpansionTargetProfile.CommonClassOnly
      ),
      companionValues: List[Boolean] = List(false),
      compositionValues: List[ExpansionCompositionPolicy] = List(
        ExpansionCompositionPolicy.StandaloneOnly
      ),
      annotationFailure: Option[Throwable] = None,
      profileFailure: Option[Throwable] = None,
      companionFailure: Option[Throwable] = None,
      compositionFailure: Option[Throwable] = None
  ) extends ParadiseAnnotationExpander:
    var annotationReads = 0
    var profileReads = 0
    var companionReads = 0
    var compositionReads = 0
    var expandCalls = 0

    def annotationName: String =
      annotationReads += 1
      annotationFailure.foreach(throw _)
      indexed(annotationValues, annotationReads)

    override def targetProfile: ExpansionTargetProfile =
      profileReads += 1
      profileFailure.foreach(throw _)
      indexed(profileValues, profileReads)

    override def consumesExistingCompanion: Boolean =
      companionReads += 1
      companionFailure.foreach(throw _)
      indexed(companionValues, companionReads)

    override def compositionPolicy: ExpansionCompositionPolicy =
      compositionReads += 1
      compositionFailure.foreach(throw _)
      indexed(compositionValues, compositionReads)

    def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
      expandCalls += 1
      ExpansionOutcome.NotApplicable

    private def indexed[A](values: List[A], reads: Int): A =
      values.lift(reads - 1).orElse(values.lastOption).get
