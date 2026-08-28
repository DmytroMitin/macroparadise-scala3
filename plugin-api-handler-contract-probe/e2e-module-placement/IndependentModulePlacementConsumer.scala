package contractprobemoduleconsumer

import contractprobemodule.IndependentModulePlacementMarker

sealed trait Nat
final class Zero extends Nat

@IndependentModulePlacementMarker
trait MissingModuleAdd[N <: Nat, M <: Nat]

@IndependentModulePlacementMarker
trait ExistingModuleAdd[N <: Nat, M <: Nat]

object ExistingModuleAdd:
  val existingValue: Int = 7

@IndependentModulePlacementMarker
trait PreserveModuleConflict[N <: Nat, M <: Nat]

object PreserveModuleConflict:
  val syntax: String = "preserved"

object IndependentModulePlacementConsumer:
  def main(args: Array[String]): Unit =
    println(MissingModuleAdd.syntax.marker)
    println(ExistingModuleAdd.syntax.marker)
    println(ExistingModuleAdd.existingValue)
    println(PreserveModuleConflict.syntax)
