package macroparadise

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.{
  ExpansionInput,
  ExpansionOutcome,
  ExpansionTargetProfile,
  ParadiseAnnotationExpander
}

class MetadataHandlerBindingSpec extends munit.FunSuite:
  test("exact qualified metadata and descriptor identities bind") {
    val binding = validated(
      "qualifiedone.audit",
      captured(InstrumentedHandler("qualifiedone.audit"))
    )

    assertEquals(binding.metadataAnnotationName, "qualifiedone.audit")
    assertEquals(binding.loadedHandler.descriptor.annotationName, "qualifiedone.audit")
  }

  test("same simple name under a different qualifier does not bind") {
    val failure = failed(
      "qualifiedone.audit",
      captured(InstrumentedHandler("qualifiedtwo.audit"))
    )

    assert(failure.diagnostic.contains("annotation=@qualifiedone.audit"), failure.diagnostic)
    assert(failure.diagnostic.contains("declaredAnnotation=@qualifiedtwo.audit"), failure.diagnostic)
  }

  test("matching metadata relation accepts the captured handler") {
    val handler = InstrumentedHandler("MatchingMarker")
    val loaded = captured(handler)

    val binding = validated("MatchingMarker", loaded)

    assert(binding.loadedHandler eq loaded)
    assertEquals(handler.annotationReads, 1)
    assertEquals(handler.profileReads, 1)
    assertEquals(handler.companionReads, 1)
  }

  test("mismatched metadata relation has a stable controlled loading diagnostic") {
    val loaded = captured(InstrumentedHandler("DeclaredMarker"))

    val failure = failed("MetadataMarker", loaded)

    List(
      "stage=loading",
      "category=METADATA_HANDLER_ANNOTATION_MISMATCH",
      "annotation=@MetadataMarker",
      s"metadataHandler=${loaded.descriptor.handlerClassName}",
      "declaredAnnotation=@DeclaredMarker",
      "loaderPolicy=parent-first",
      "requestedLoader=",
      "selects",
      "captured descriptor declares"
    ).foreach(fragment => assert(failure.diagnostic.contains(fragment), failure.diagnostic))
  }

  test("explicit class resolution reuses the exact loaded handler without loading") {
    val loaded = captured(InstrumentedHandler("ExplicitMarker"))
    var loads = 0
    val cache = MetadataHandlerRunCache(List(loaded))

    val first = cache.resolve(loaded.descriptor.handlerClassName):
      loads += 1
      None
    val second = cache.resolve(loaded.descriptor.handlerClassName):
      loads += 1
      None

    assertEquals(first.origin, cache.Origin.Explicit)
    assertEquals(second.origin, cache.Origin.Explicit)
    assert(first.loadedHandler.exists(_ eq loaded))
    assert(second.loadedHandler.exists(_ eq loaded))
    assertEquals(loads, 0)
  }

  test("discovered class resolution captures one success for repeated relations") {
    val loaded = captured(InstrumentedHandler("MatchingMarker"))
    var loads = 0
    val cache = MetadataHandlerRunCache(Nil)

    val matchResolution = cache.resolve(loaded.descriptor.handlerClassName):
      loads += 1
      Some(loaded)
    val mismatchResolution = cache.resolve(loaded.descriptor.handlerClassName):
      loads += 1
      Some(loaded)

    assertEquals(matchResolution.origin, cache.Origin.Discovered)
    assertEquals(mismatchResolution.origin, cache.Origin.Discovered)
    assert(matchResolution.loadedHandler.exists(_ eq loaded))
    assert(mismatchResolution.loadedHandler.exists(_ eq loaded))
    assertEquals(loads, 1)
    assert(validated("MatchingMarker", matchResolution.loadedHandler.get).loadedHandler eq loaded)
    assertEquals(
      failed("OtherMarker", mismatchResolution.loadedHandler.get).diagnostic
        .split("category=METADATA_HANDLER_ANNOTATION_MISMATCH", -1)
        .length - 1,
      1
    )
  }

  test("failed discovered class resolution is cached") {
    var loads = 0
    val cache = MetadataHandlerRunCache(Nil)

    val first = cache.resolve("missing.Handler"):
      loads += 1
      None
    val second = cache.resolve("missing.Handler"):
      loads += 1
      None

    assertEquals(first.loadedHandler, None)
    assertEquals(second.loadedHandler, None)
    assertEquals(loads, 1)
  }

  private def validated(
      annotationName: String,
      loaded: LoadedExternalHandler
  ): MetadataHandlerBinding =
    MetadataHandlerBinding.validate(
      annotationName,
      loaded.descriptor.handlerClassName,
      loaded,
      getClass.getClassLoader
    ) match
      case Right(binding) => binding
      case Left(failure) => fail(failure.diagnostic)

  private def failed(
      annotationName: String,
      loaded: LoadedExternalHandler
  ): MetadataHandlerBinding.Failure =
    MetadataHandlerBinding.validate(
      annotationName,
      loaded.descriptor.handlerClassName,
      loaded,
      getClass.getClassLoader
    ) match
      case Left(failure) => failure
      case Right(binding) => fail(s"unexpected binding: $binding")

  private def captured(handler: InstrumentedHandler): LoadedExternalHandler =
    ExternalHandlerDescriptor.capture(handler, getClass.getClassLoader) match
      case Right(loaded) => loaded
      case Left(failure) => fail(failure.diagnostic)

  private final class InstrumentedHandler(declaredAnnotationName: String)
      extends ParadiseAnnotationExpander:
    var annotationReads = 0
    var profileReads = 0
    var companionReads = 0

    def annotationName: String =
      annotationReads += 1
      declaredAnnotationName

    override def targetProfile: ExpansionTargetProfile =
      profileReads += 1
      ExpansionTargetProfile.CommonClassOnly

    override def consumesExistingCompanion: Boolean =
      companionReads += 1
      false

    def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
      ExpansionOutcome.NotApplicable
