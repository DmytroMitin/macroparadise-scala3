import scala.util.Try

class PlainUser(val name: String)

object UnannotatedExample:
  val methodNames: Set[String] = new PlainUser("plain").getClass.getMethods.map(_.getName).toSet
  val siblingClassExists: Boolean = Try(Class.forName("PlainUserMeta")).isSuccess
