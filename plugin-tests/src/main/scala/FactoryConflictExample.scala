package factoryconflict

import paradise3.gen

@gen
class User(val name: String)

object User:
  def generatedFactory(name: String): User = new User(name + "!")

object FactoryConflictExample:
  val result = User.generatedFactory("A").generatedHello
