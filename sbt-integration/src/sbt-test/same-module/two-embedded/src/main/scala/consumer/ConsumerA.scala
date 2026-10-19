package consumer

@demo.markerA
final class ConsumerA

object Main:
  def main(args: Array[String]): Unit =
    val a = new ConsumerA().generatedValueA
    val b = new ConsumerB().generatedValueB
    assert(a == "A-v1" || a == "A-v2")
    assert(b == "B-v1")
    println("G1_A=" + a)
    println("G1_B=" + b)
