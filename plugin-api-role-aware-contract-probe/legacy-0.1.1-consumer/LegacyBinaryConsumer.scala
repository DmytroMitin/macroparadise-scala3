package legacybinaryconsumer

import legacybinaryprobe.{LegacyBinaryClassMarker, LegacyBinaryTraitMarker}

@LegacyBinaryClassMarker class LegacyClass
@LegacyBinaryTraitMarker trait LegacyTrait

object LegacyBinaryConsumer:
  def main(args: Array[String]): Unit =
    println(new LegacyClass().legacyClass)
    println(new LegacyTrait {}.legacyTrait)
