package contractprobebodyinfixmethodnegative

import contractprobebody.IndependentBodyViewMarker

trait Nat

@IndependentBodyViewMarker
trait InfixMethodBodyView[N <: Nat, M <: Nat]:
  type Out <: Nat
  infix def zero: Int = 0
