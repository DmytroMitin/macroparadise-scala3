package contractprobebodyinfixtypenegative

import contractprobebody.IndependentBodyViewMarker

trait Nat

@IndependentBodyViewMarker
trait InfixTypeAliasBodyView[N <: Nat, M <: Nat]:
  infix type Out = Nat
