package contractprobeconsumer

import contractprobebody.IndependentBodyViewMarker

@IndependentBodyViewMarker
trait IndependentShow[A]:
  def show(a: A): String

object IndependentBodyViewConsumer:
  def main(args: Array[String]): Unit =
    println(IndependentShow.independentBodyView)
