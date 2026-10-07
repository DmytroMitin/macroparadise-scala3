package consumer

@demo.sameModuleGreeting("Hello")
final class QualifiedGreeter

object Main:
  def main(args: Array[String]): Unit =
    val qualified = new QualifiedGreeter().generatedGreeting
    val imported = new ImportedGreeter().generatedGreeting
    assert(qualified == "Hello:same-module-v1" || qualified == "Hello:same-module-v2")
    assert(imported == "Welcome:same-module-v1" || imported == "Welcome:same-module-v2")
    assert(qualified.stripPrefix("Hello:") == imported.stripPrefix("Welcome:"))
    println("SAME_MODULE_EMBEDDED_RUNTIME:" + qualified.stripPrefix("Hello:"))
