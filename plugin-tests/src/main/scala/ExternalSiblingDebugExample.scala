import paradise3.externalSiblingDebug

@externalSiblingDebug
class ExternalSiblingUser

object ExternalSiblingDebugExample:
  val originalUser: ExternalSiblingUser = new ExternalSiblingUser()
  val externalSiblingDebugResult: String =
    new ExternalSiblingUserExternalMeta().externalSiblingDebugName

  def useOriginal(user: ExternalSiblingUser): String =
    user.getClass.getSimpleName
