import paradise3.{externalCompanionDebug, gen}

@gen
@externalCompanionDebug
class GenCompanionUser(val name: String)

@externalCompanionDebug
@gen
class ReversedGenCompanionUser(val name: String)

@gen
@externalCompanionDebug
class ExistingGenCompanionUser(val name: String)

object ExistingGenCompanionUser:
  def existing: Int = 42

@externalCompanionDebug
@gen
class ReversedExistingGenCompanionUser(val name: String)

object ReversedExistingGenCompanionUser:
  def existing: Int = 84

@gen
@externalCompanionDebug
class UserFactoryGenCompanionUser(val name: String)

object UserFactoryGenCompanionUser:
  def generatedFactory(name: String): UserFactoryGenCompanionUser =
    new UserFactoryGenCompanionUser(s"user-$name")

@externalCompanionDebug
@gen
class UserDebugGenCompanionUser(val name: String)

object UserDebugGenCompanionUser:
  def externalCompanionDebugName: String = "user-defined"

object GenCompanionCompositionExample:
  val directHello = new GenCompanionUser("A").generatedHello
  val directFactoryHello = GenCompanionUser.generatedFactory("A").generatedHello
  val directCompanionDebug = GenCompanionUser.externalCompanionDebugName
  val directMeta = new GenCompanionUserMeta

  val reversedHello = new ReversedGenCompanionUser("A").generatedHello
  val reversedFactoryHello = ReversedGenCompanionUser.generatedFactory("A").generatedHello
  val reversedCompanionDebug = ReversedGenCompanionUser.externalCompanionDebugName
  val reversedMeta = new ReversedGenCompanionUserMeta

  val existingHello = new ExistingGenCompanionUser("A").generatedHello
  val existingFactoryHello = ExistingGenCompanionUser.generatedFactory("A").generatedHello
  val existingCompanionDebug = ExistingGenCompanionUser.externalCompanionDebugName
  val existingMember = ExistingGenCompanionUser.existing
  val existingMeta = new ExistingGenCompanionUserMeta

  val reversedExistingFactoryHello = ReversedExistingGenCompanionUser.generatedFactory("A").generatedHello
  val reversedExistingCompanionDebug = ReversedExistingGenCompanionUser.externalCompanionDebugName
  val reversedExistingMember = ReversedExistingGenCompanionUser.existing
  val reversedExistingMeta = new ReversedExistingGenCompanionUserMeta

  val userFactoryName = UserFactoryGenCompanionUser.generatedFactory("A").name
  val userFactoryCompanionDebug = UserFactoryGenCompanionUser.externalCompanionDebugName

  val userDebugFactoryHello = UserDebugGenCompanionUser.generatedFactory("A").generatedHello
  val userDebugCompanionDebug = UserDebugGenCompanionUser.externalCompanionDebugName
