import paradise3.externalDebug

@externalDebug
class ExternalDebugConflictUser:
  def externalDebugName: String = "user-defined"

object ExternalDebugConflictExample:
  val result = new ExternalDebugConflictUser().externalDebugName
