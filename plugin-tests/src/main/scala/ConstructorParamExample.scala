import paradise3.gen

@gen
class ConstructorParamUser(name: String)

object ConstructorParamExample:
  val result = new ConstructorParamUser("B").generatedHello
