package macroparadise

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.{
  ExpansionInput,
  ExpansionOutcome,
  ExpansionTargetProfile,
  ParadiseAnnotationExpander
}

class MetadataHandlerRunCacheSpec extends munit.FunSuite:
  test("metadata-only success is resolved once across unit-level calls") {
    var constructions = 0
    var constructedHandler: InstrumentedHandler | Null = null
    val cache = MetadataHandlerRunCache(Nil)
    val className = classOf[InstrumentedHandler].getName

    def load(): Option[LoadedExternalHandler] =
      constructions += 1
      val handler = InstrumentedHandler("RunMarker")
      constructedHandler = handler
      Some(captured(handler))

    val first = cache.resolve(className)(load())
    val second = cache.resolve(className)(load())
    val handler = constructedHandler.asInstanceOf[InstrumentedHandler]

    assertEquals(constructions, 1)
    assert(first.loadedHandler.get eq second.loadedHandler.get)
    assertEquals(first.origin, cache.Origin.Discovered)
    assertEquals(second.origin, cache.Origin.Discovered)
    assertEquals(handler.annotationReads, 1)
    assertEquals(handler.profileReads, 1)
    assertEquals(handler.companionReads, 1)
  }

  test("explicit seed is the exact instance across unit-level calls") {
    val loaded = captured(InstrumentedHandler("ExplicitRunMarker"))
    var loads = 0
    val cache = MetadataHandlerRunCache(List(loaded))

    val first = cache.resolve(loaded.descriptor.handlerClassName):
      loads += 1
      None
    val second = cache.resolve(loaded.descriptor.handlerClassName):
      loads += 1
      None

    assertEquals(loads, 0)
    assert(first.loadedHandler.exists(_ eq loaded))
    assert(second.loadedHandler.exists(_ eq loaded))
    assertEquals(first.origin, cache.Origin.Explicit)
    assertEquals(second.origin, cache.Origin.Explicit)
  }

  test("binding decisions remain relation-local around one cached handler") {
    val loaded = captured(InstrumentedHandler("MatchingRunMarker"))
    val cache = MetadataHandlerRunCache(Nil)
    var loads = 0

    val first = cache.resolve(loaded.descriptor.handlerClassName):
      loads += 1
      Some(loaded)
    val second = cache.resolve(loaded.descriptor.handlerClassName):
      loads += 1
      Some(loaded)

    assertEquals(loads, 1)
    assert(
      MetadataHandlerBinding
        .validate(
          "MatchingRunMarker",
          loaded.descriptor.handlerClassName,
          first.loadedHandler.get,
          getClass.getClassLoader
        )
        .isRight
    )
    val mismatch =
      MetadataHandlerBinding.validate(
        "MismatchingRunMarker",
        loaded.descriptor.handlerClassName,
        second.loadedHandler.get,
        getClass.getClassLoader
      )
    assert(mismatch.isLeft)
    assert(
      mismatch.left.toOption.get.diagnostic.contains(
        "category=METADATA_HANDLER_ANNOTATION_MISMATCH"
      )
    )
  }

  test("failed resolution is attempted once in one run cache") {
    val cache = MetadataHandlerRunCache(Nil)
    var attempts = 0

    val first = cache.resolve("external.MissingHandler"):
      attempts += 1
      None
    val second = cache.resolve("external.MissingHandler"):
      attempts += 1
      None

    assertEquals(attempts, 1)
    assertEquals(first.loadedHandler, None)
    assertEquals(second.loadedHandler, None)
  }

  test("failed resolution is retried by a fresh run cache") {
    var attempts = 0

    def resolve(cache: MetadataHandlerRunCache): Unit =
      val result = cache.resolve("external.MissingHandler"):
        attempts += 1
        None
      assertEquals(result.loadedHandler, None)

    resolve(MetadataHandlerRunCache(Nil))
    resolve(MetadataHandlerRunCache(Nil))

    assertEquals(attempts, 2)
  }

  test("separate run caches share neither success nor instance state") {
    var constructions = 0
    val className = classOf[InstrumentedHandler].getName

    def resolve(cache: MetadataHandlerRunCache): LoadedExternalHandler =
      val resolution = cache.resolve(className):
        constructions += 1
        Some(captured(InstrumentedHandler("RunMarker")))
      resolution.loadedHandler.getOrElse(fail("expected loaded handler"))

    val first = resolve(MetadataHandlerRunCache(Nil))
    val second = resolve(MetadataHandlerRunCache(Nil))

    assertEquals(constructions, 2)
    assert(!(first eq second))
    assert(!(first.instance eq second.instance))
    assert(!(first.descriptor eq second.descriptor))
  }

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
