package fixture

import fixture.marker.{markerA, markerB}

@markerA class SubjectA
@markerB class SubjectB

object Consumer:
  def main(args: Array[String]): Unit =
    println(new SubjectA().generatedA + ";" + new SubjectB().generatedB)
