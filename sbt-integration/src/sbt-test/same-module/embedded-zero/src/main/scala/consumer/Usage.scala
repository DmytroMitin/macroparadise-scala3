package consumer

@demo.sameModuleIdentity
final class IdentityTarget

object Main:
  def main(args: Array[String]): Unit =
    val value = new IdentityTarget
    assert(value.isInstanceOf[IdentityTarget])
    println("SAME_MODULE_EMBEDDED_ZERO:identity")
