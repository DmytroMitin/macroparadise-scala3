package contractprobeconsumer

import contractprobebody.IndependentBodyViewMarker

trait Nat

@IndependentBodyViewMarker
trait IndependentAdd[N <: Nat, M <: Nat]:
  type Out <: Nat

object IndependentBodyViewConsumer:
  def main(args: Array[String]): Unit =
    println(IndependentAdd.independentBodyView)
