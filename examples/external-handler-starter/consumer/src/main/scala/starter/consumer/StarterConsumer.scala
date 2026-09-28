package starter.consumer

import starter.marker.{addFoo, generateGreeting}

@generateGreeting
final class Greeter

@addFoo
class A:
  def classMethod(x: Int): String = x.toString

object A:
  def objectMethod(x: Int): String = x.toString

@addFoo
class B:
  def classMethod(x: Int): String = x.toString

object StarterConsumer:
  def main(args: Array[String]): Unit =
    val greeting: String = new Greeter().generatedGreeting
    assert(greeting == "Hello, Greeter!", greeting)

    val existing = List(A().foo(10), A().foo1(10), A().foo2(10), A.foo(10))
    assert(existing == List.fill(4)("10"), existing)

    val created = List(B().foo(10), B().foo1(10), B().foo2(10), B.foo(10))
    assert(created == List.fill(4)("10"), created)

    println(greeting)
    println("addFoo-existing=" + existing.mkString(","))
    println("addFoo-created=" + created.mkString(","))
