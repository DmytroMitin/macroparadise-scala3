import paradise3.{
  PreservedRuntimeMarker,
  externalDebug,
  externalTypedLabel
}

@externalDebug
@externalTypedLabel[String]("kept")
class ExternalTypedLabelUser

@externalTypedLabel[String]("kept")
@externalDebug
class ExternalTypedLabelReversedUser

@externalTypedLabel[Int](value = "named-label")
class ExternalTypedNamedUser

@externalTypedLabel[Int]("simple-import-label")
class ExternalTypedSimpleImportedUser

@PreservedRuntimeMarker("typed-marker-kept")
@externalTypedLabel[Int]("preserved-label")
class ExternalTypedUnhandledUser

object ExternalTypedLabelCompositionExample:
  val directDebugResult = new ExternalTypedLabelUser().externalDebugName
  val directTypedLabelResult = new ExternalTypedLabelUser().externalTypedLabel

  val reversedDebugResult = new ExternalTypedLabelReversedUser().externalDebugName
  val reversedTypedLabelResult = new ExternalTypedLabelReversedUser().externalTypedLabel

  val namedTypedLabelResult = new ExternalTypedNamedUser().externalTypedLabel
  val simpleImportedTypedLabelResult =
    new ExternalTypedSimpleImportedUser().externalTypedLabel
  val unhandledTypedLabelResult =
    new ExternalTypedUnhandledUser().externalTypedLabel
  val unhandledMarkerValue =
    classOf[ExternalTypedUnhandledUser]
      .getAnnotation(classOf[PreservedRuntimeMarker])
      .value()
