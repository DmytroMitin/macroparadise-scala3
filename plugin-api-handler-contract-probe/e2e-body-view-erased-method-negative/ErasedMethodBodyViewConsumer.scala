package contractprobebodyerasedmethodnegative

import contractprobebody.IndependentBodyViewMarker
import scala.language.experimental.erasedDefinitions

trait Nat

@IndependentBodyViewMarker
trait ErasedMethodBodyView[N <: Nat, M <: Nat]:
  type Out <: Nat
  erased def zero: Int = 0
