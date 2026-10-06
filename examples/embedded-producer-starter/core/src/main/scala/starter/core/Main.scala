package starter.core

import starter.embedded.{addGreeting, identityEmbedded}

@identityEmbedded
final class IdentityUser

@addGreeting("Hello")
final class PositionalGreeter

@addGreeting(prefix = "Welcome")
final class NamedGreeter

object Main:
  def main(args: Array[String]): Unit =
    assert(new IdentityUser().getClass.getName == "starter.core.IdentityUser")
    assert(new PositionalGreeter().generatedGreeting == "Hello, Greeter!")
    assert(new NamedGreeter().generatedGreeting == "Welcome, Greeter!")
    println("PUBLIC_EMBEDDED_STARTER_PASS")
