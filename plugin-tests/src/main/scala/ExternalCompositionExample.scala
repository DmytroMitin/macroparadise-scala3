import paradise3.{externalCompanionDebug, externalDebug, externalLabel}

@externalDebug
@externalCompanionDebug
class ExternalComposedUser

@externalCompanionDebug
@externalDebug
class ExternalComposedReversedUser

@externalDebug
@externalCompanionDebug
class ExternalComposedExistingUser

object ExternalComposedExistingUser:
  def existing: Int = 42

@externalLabel
@externalCompanionDebug
class ExternalLabelCompanionUser

@externalCompanionDebug
@externalLabel
class ExternalLabelCompanionReversedUser

@externalDebug
@externalLabel
@externalCompanionDebug
class ExternalThreeStepUser

object ExternalThreeStepUser:
  def existing: Int = 42

object ExternalCompositionExample:
  val directClassResult = new ExternalComposedUser().externalDebugName
  val directCompanionResult = ExternalComposedUser.externalCompanionDebugName

  val reversedClassResult = new ExternalComposedReversedUser().externalDebugName
  val reversedCompanionResult = ExternalComposedReversedUser.externalCompanionDebugName

  val existingClassResult = new ExternalComposedExistingUser().externalDebugName
  val existingCompanionResult = ExternalComposedExistingUser.externalCompanionDebugName
  val existingPreservedResult = ExternalComposedExistingUser.existing

  val labelCompanionLabelResult = new ExternalLabelCompanionUser().externalLabel
  val labelCompanionCompanionResult = ExternalLabelCompanionUser.externalCompanionDebugName
  val reversedLabelCompanionLabelResult = new ExternalLabelCompanionReversedUser().externalLabel
  val reversedLabelCompanionCompanionResult = ExternalLabelCompanionReversedUser.externalCompanionDebugName

  val threeStepDebugResult = new ExternalThreeStepUser().externalDebugName
  val threeStepLabelResult = new ExternalThreeStepUser().externalLabel
  val threeStepCompanionResult = ExternalThreeStepUser.externalCompanionDebugName
  val threeStepExistingResult = ExternalThreeStepUser.existing
