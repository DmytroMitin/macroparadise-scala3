import paradise3.legacyExternalDebug

@legacyExternalDebug
class LegacyMetadataUser

object LegacyMetadataExample:
  val legacyMetadataResult =
    new LegacyMetadataUser().legacyExternalDebugName

  def useLegacyMetadataUser(user: LegacyMetadataUser): String =
    user.legacyExternalDebugName
