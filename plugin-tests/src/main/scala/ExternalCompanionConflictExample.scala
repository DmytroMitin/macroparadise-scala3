import paradise3.externalCompanionDebug

@externalCompanionDebug
class ExternalCompanionConflictUser

object ExternalCompanionConflictUser:
  def externalCompanionDebugName: String = "user-defined"

object ExternalCompanionConflictExample:
  val result = ExternalCompanionConflictUser.externalCompanionDebugName
