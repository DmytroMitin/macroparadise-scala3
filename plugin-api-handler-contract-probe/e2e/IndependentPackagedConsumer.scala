package contractprobeconsumer

import contractprobe.IndependentMarker

@IndependentMarker
final class IndependentConsumerUser

object IndependentPackagedConsumer:
  def main(args: Array[String]): Unit =
    println(new IndependentConsumerUser().independentHandlerName)
