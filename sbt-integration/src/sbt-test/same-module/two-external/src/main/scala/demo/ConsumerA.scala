package demo

@demo.markerA
final class ConsumerA

object G0Check:
  def main(args: Array[String]): Unit =
    assert(args.length == 2, s"expected A and B values, got: ${args.toList}")
    val a = new ConsumerA().generatedValue
    val b = new ConsumerB().generatedValue
    assert(a == args(0), s"expected A=${args(0)}, got $a")
    assert(b == args(1), s"expected B=${args(1)}, got $b")
    println(s"G0_A=$a")
    println(s"G0_B=$b")
