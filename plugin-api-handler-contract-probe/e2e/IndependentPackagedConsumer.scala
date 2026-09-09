package contractprobeconsumer

import contractprobe.IndependentMarker

@IndependentMarker
final class IndependentConsumerUser

@IndependentMarker
object IndependentConsumerObject

object IndependentPackagedConsumer:
  def main(args: Array[String]): Unit =
    println(new IndependentConsumerUser().independentHandlerName)
    println(IndependentConsumerObject.independentHandlerName)
