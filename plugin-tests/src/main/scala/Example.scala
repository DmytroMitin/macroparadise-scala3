import paradise3.gen

@gen
class User(val name: String)

object Example:
  val directResult = new User("A").generatedHello
  val companionResult = User.generatedFactory("A").generatedHello
  val siblingMeta = new UserMeta
  def useUser(u: User): String = u.generatedHello
  def createUser(): User = User.generatedFactory("A")
  def createMeta(): UserMeta = new UserMeta
