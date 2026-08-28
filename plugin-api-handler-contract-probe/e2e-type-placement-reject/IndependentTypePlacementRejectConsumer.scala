package contractprobetypeconsumerreject

import contractprobetype.IndependentTypePlacementRejectMarker

sealed trait Nat

@IndependentTypePlacementRejectMarker
trait RejectConflictAdd[N <: Nat, M <: Nat]:
  type Out <: Nat

object RejectConflictAdd:
  type Aux[N <: Nat, M <: Nat, Out0 <: Nat] = String
