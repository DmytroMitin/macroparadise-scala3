import paradise3.externalCompanionDebug

@externalCompanionDebug
class ExternalCompanionExistingUser

object ExternalCompanionExistingUser:
  def existing: Int = 42

object ExternalCompanionExistingExample:
  val existingResult = ExternalCompanionExistingUser.existing
  val generatedResult = ExternalCompanionExistingUser.externalCompanionDebugName
