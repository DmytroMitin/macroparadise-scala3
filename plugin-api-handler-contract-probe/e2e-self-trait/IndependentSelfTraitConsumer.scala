package contractprobeselfconsumer

import contractprobeself.IndependentSelfTraitMarker

@IndependentSelfTraitMarker
trait AnonymousNat:
  type Existing = String
  def existing: String = "anonymous"

@IndependentSelfTraitMarker
trait ExistingNamedNat:
  stable =>
  type Existing = String
  def existing: String = "existing"

@IndependentSelfTraitMarker
trait CollisionNat:
  val self: String = "occupied"
  def self$1: String = "occupied-1"
  type self = String
  type Existing = String
  def existing: String = "collision"

object IndependentSelfTraitConsumer:
  def main(args: Array[String]): Unit =
    val anonymous = new AnonymousNat {}
    val anonymousSelf: anonymous.Self = anonymous
    val anonymousExisting: anonymous.Existing = "original"

    val existingNamed = new ExistingNamedNat {}
    val existingNamedSelf: existingNamed.Self = existingNamed
    val existingNamedExisting: existingNamed.Existing = "original"

    val collision = new CollisionNat {}
    val collisionSelf: collision.Self = collision
    val collisionExisting: collision.Existing = "original"

    println(s"${anonymousSelf.existing}|$anonymousExisting")
    println(s"${existingNamedSelf.existing}|$existingNamedExisting")
    println(s"${collisionSelf.existing}|$collisionExisting")
