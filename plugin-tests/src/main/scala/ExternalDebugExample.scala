import paradise3.externalDebug

@externalDebug
class ExternalUser

object ExternalDebugExample:
  val directResult = new ExternalUser().externalDebugName
  def useExternalUser(user: ExternalUser): String = user.externalDebugName
