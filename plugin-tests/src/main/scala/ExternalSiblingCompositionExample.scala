import paradise3.{externalDebug, externalSiblingDebug}

@externalSiblingDebug
@externalDebug
class ExternalSiblingThenDebugUser

object ExternalSiblingThenDebugUser:
  val preserved: Int = 42

@externalDebug
@externalSiblingDebug
class ExternalDebugThenSiblingUser

object ExternalSiblingCompositionExample:
  val siblingThenDebugClassResult: String =
    new ExternalSiblingThenDebugUser().externalDebugName
  val siblingThenDebugSibling: ExternalSiblingThenDebugUserExternalMeta =
    new ExternalSiblingThenDebugUserExternalMeta()
  val siblingThenDebugSiblingResult: String =
    siblingThenDebugSibling.externalSiblingDebugName
  val siblingThenDebugCompanionResult: Int =
    ExternalSiblingThenDebugUser.preserved

  val debugThenSiblingClassResult: String =
    new ExternalDebugThenSiblingUser().externalDebugName
  val debugThenSiblingSibling: ExternalDebugThenSiblingUserExternalMeta =
    new ExternalDebugThenSiblingUserExternalMeta()
  val debugThenSiblingSiblingResult: String =
    debugThenSiblingSibling.externalSiblingDebugName
