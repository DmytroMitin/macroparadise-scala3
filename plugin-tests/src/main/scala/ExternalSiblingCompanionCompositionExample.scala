import paradise3.{externalCompanionDebug, externalSiblingDebug}

@externalSiblingDebug
@externalCompanionDebug
class ExternalSiblingThenCompanionUser

object ExternalSiblingThenCompanionUser:
  val existing: Int = 42

@externalCompanionDebug
@externalSiblingDebug
class ExternalCompanionThenSiblingUser

object ExternalCompanionThenSiblingUser:
  val existing: Int = 84

object ExternalSiblingCompanionCompositionExample:
  val siblingThenCompanionCompanionResult: String =
    ExternalSiblingThenCompanionUser.externalCompanionDebugName
  val siblingThenCompanionExistingResult: Int =
    ExternalSiblingThenCompanionUser.existing
  val siblingThenCompanionSibling: ExternalSiblingThenCompanionUserExternalMeta =
    new ExternalSiblingThenCompanionUserExternalMeta()
  val siblingThenCompanionSiblingResult: String =
    siblingThenCompanionSibling.externalSiblingDebugName

  val companionThenSiblingCompanionResult: String =
    ExternalCompanionThenSiblingUser.externalCompanionDebugName
  val companionThenSiblingExistingResult: Int =
    ExternalCompanionThenSiblingUser.existing
  val companionThenSiblingSibling: ExternalCompanionThenSiblingUserExternalMeta =
    new ExternalCompanionThenSiblingUserExternalMeta()
  val companionThenSiblingSiblingResult: String =
    companionThenSiblingSibling.externalSiblingDebugName
