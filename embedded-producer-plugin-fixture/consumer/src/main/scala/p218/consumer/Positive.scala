package p218.consumer

import p218.marker.{addGreeting, companionEmbedded, defaultedEmbedded, genericEmbedded, identityEmbedded, ordinary}

@defaultedEmbedded()
class DefaultedUser

@genericEmbedded[String]
class GenericUser

@identityEmbedded
class IdentityUser

@addGreeting("Hello")
class PositionalUser

@addGreeting(prefix = "Named")
class NamedUser

@addGreeting("EmbeddedFirst")
@ordinary
class EmbeddedThenOrdinary

@ordinary
@addGreeting("OrdinaryFirst")
class OrdinaryThenEmbedded

@companionEmbedded
class ExistingCompanion
object ExistingCompanion:
  val original: String = "original"

@companionEmbedded
class MissingCompanion

object Positive:
  def main(args: Array[String]): Unit =
    val edit = sys.props.getOrElse("p218.expectedEdit", "P218_EDIT_V1")
    assert(new DefaultedUser().getClass.getName == "p218.consumer.DefaultedUser")
    assert(new GenericUser().getClass.getName == "p218.consumer.GenericUser")
    val identity = new IdentityUser
    assert(identity.getClass.getName == "p218.consumer.IdentityUser")
    assert(new PositionalUser().generatedGreeting == s"Hello:before-ordinary:$edit")
    assert(new NamedUser().generatedGreeting == s"Named:before-ordinary:$edit")
    val embeddedFirst = new EmbeddedThenOrdinary
    assert(embeddedFirst.generatedGreeting == s"EmbeddedFirst:before-ordinary:$edit")
    assert(embeddedFirst.ordinaryStage == "saw-embedded")
    val ordinaryFirst = new OrdinaryThenEmbedded
    assert(ordinaryFirst.ordinaryStage == "before-embedded")
    assert(ordinaryFirst.generatedGreeting == s"OrdinaryFirst:saw-ordinary:$edit")
    assert(ExistingCompanion.original == "original")
    assert(ExistingCompanion.embeddedCompanionValue == "ExistingCompanion")
    assert(MissingCompanion.embeddedCompanionValue == "MissingCompanion")
    println("P218_POSITIVE_RUNTIME_PASS")
