import paradise3.debug

@debug
class DebugUser(val name: String)

object DebugExample:
  val directResult = new DebugUser("A").debugName
  def useDebugUser(user: DebugUser): String = user.debugName
