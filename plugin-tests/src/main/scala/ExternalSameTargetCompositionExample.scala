import paradise3.{PreservedRuntimeMarker, externalDebug, externalLabel}

@externalDebug
@externalLabel
class ExternalSameTargetUser:
  def existing: Int = 42

@externalLabel
@externalDebug
class ExternalSameTargetReversedUser

@externalDebug
@PreservedRuntimeMarker("kept")
@externalLabel
class ExternalSameTargetWithUnhandledUser

object ExternalSameTargetCompositionExample:
  val directDebugResult = new ExternalSameTargetUser().externalDebugName
  val directLabelResult = new ExternalSameTargetUser().externalLabel
  val directExistingResult = new ExternalSameTargetUser().existing

  val reversedDebugResult = new ExternalSameTargetReversedUser().externalDebugName
  val reversedLabelResult = new ExternalSameTargetReversedUser().externalLabel

  val unhandledDebugResult = new ExternalSameTargetWithUnhandledUser().externalDebugName
  val unhandledLabelResult = new ExternalSameTargetWithUnhandledUser().externalLabel
  val unhandledMarkerValue =
    classOf[ExternalSameTargetWithUnhandledUser]
      .getAnnotation(classOf[PreservedRuntimeMarker])
      .value()
