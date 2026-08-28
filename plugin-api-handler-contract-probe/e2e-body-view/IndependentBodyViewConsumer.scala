package contractprobeconsumer

import contractprobebody.IndependentBodyViewMarker

@IndependentBodyViewMarker
trait IndependentMonoid[A]:
  def empty: A
  def combine(a: A, a1: A): A

object IndependentBodyViewConsumer:
  def main(args: Array[String]): Unit =
    println(IndependentMonoid.independentBodyView)
