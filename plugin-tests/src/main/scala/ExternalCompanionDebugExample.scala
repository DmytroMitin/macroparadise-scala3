import paradise3.externalCompanionDebug

@externalCompanionDebug
class ExternalCompanionUser

object ExternalCompanionDebugExample:
  val directResult = ExternalCompanionUser.externalCompanionDebugName
  def companionResult: String = ExternalCompanionUser.externalCompanionDebugName
