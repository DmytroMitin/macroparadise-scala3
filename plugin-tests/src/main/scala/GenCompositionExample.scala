import paradise3.{externalDebug, gen}

@gen
@externalDebug
class GenCompositionUser(val name: String)

@externalDebug
@gen
class GenCompositionReversedUser(val name: String)

@gen
@externalDebug
class GenCompositionExistingUser(val name: String)

object GenCompositionExistingUser:
  def existing: Int = 42

object GenCompositionExample:
  val directGeneratedResult = new GenCompositionUser("A").generatedHello
  val directExternalResult = new GenCompositionUser("A").externalDebugName
  val directCompanionResult = GenCompositionUser.generatedFactory("A").generatedHello
  val directSiblingMeta = new GenCompositionUserMeta

  val reversedGeneratedResult = new GenCompositionReversedUser("A").generatedHello
  val reversedExternalResult = new GenCompositionReversedUser("A").externalDebugName
  val reversedCompanionResult = GenCompositionReversedUser.generatedFactory("A").generatedHello
  val reversedSiblingMeta = new GenCompositionReversedUserMeta

  val existingGeneratedResult = new GenCompositionExistingUser("A").generatedHello
  val existingExternalResult = new GenCompositionExistingUser("A").externalDebugName
  val existingCompanionResult = GenCompositionExistingUser.generatedFactory("A").generatedHello
  val existingPreservedResult = GenCompositionExistingUser.existing
  val existingSiblingMeta = new GenCompositionExistingUserMeta
