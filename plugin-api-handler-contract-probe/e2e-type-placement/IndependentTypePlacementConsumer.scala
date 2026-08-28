package contractprobetypeconsumer

import contractprobetype.IndependentTypePlacementMarker

sealed trait Nat
final class Zero extends Nat

@IndependentTypePlacementMarker
trait MissingCompanionAdd[N <: Nat, M <: Nat]:
  type Out <: Nat

@IndependentTypePlacementMarker
trait ExistingCompanionAdd[N <: Nat, M <: Nat]:
  type Out <: Nat

object ExistingCompanionAdd:
  val existingValue: Int = 7

@IndependentTypePlacementMarker
trait PreserveConflictAdd[N <: Nat, M <: Nat]:
  type Out <: Nat

object PreserveConflictAdd:
  type Aux[N <: Nat, M <: Nat, Out0 <: Nat] = String

object IndependentTypePlacementConsumer:
  private val missing: MissingCompanionAdd.Aux[Zero, Zero, Zero] = null
  private val existing: ExistingCompanionAdd.Aux[Zero, Zero, Zero] = null
  private val preserved: PreserveConflictAdd.Aux[Zero, Zero, Zero] = "preserved"

  def main(args: Array[String]): Unit =
    println(missing == null)
    println(existing == null)
    println(ExistingCompanionAdd.existingValue)
    println(preserved)
