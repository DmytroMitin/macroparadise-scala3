package starter.consumer

import starter.marker.generateGreeting

@generateGreeting
final class Greeter

object StarterConsumer:
  def main(args: Array[String]): Unit =
    val greeting: String = new Greeter().generatedGreeting
    assert(greeting == "Hello, Greeter!", greeting)
    println(greeting)
