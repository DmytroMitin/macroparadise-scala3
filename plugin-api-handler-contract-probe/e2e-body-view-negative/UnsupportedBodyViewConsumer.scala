package contractprobeconsumernegative

import contractprobebody.IndependentBodyViewMarker

trait Nat

@IndependentBodyViewMarker
trait UnsupportedBodyView[N <: Nat, M <: Nat]:
  type Out = Nat
