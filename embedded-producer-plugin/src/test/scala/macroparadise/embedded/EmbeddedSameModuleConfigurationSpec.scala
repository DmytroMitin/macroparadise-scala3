package macroparadise.embedded

object EmbeddedSameModuleConfigurationSpec:
  def main(args: Array[String]): Unit =
    producerOptionIsSingularCanonicalAndNormalized()
    producerPairIsCanonicalSortedAndBounded()
    sameModuleModeConditionallyOrdersProducerBeforeParadiseGen()
    println("EMBEDDED_SAME_MODULE_CONFIGURATION_SPEC_PASS")

  private def producerOptionIsSingularCanonicalAndNormalized(): Unit =
    assert(EmbeddedSameModuleConfiguration.parse(Nil) == Right(Vector.empty))
    assert(
      EmbeddedSameModuleConfiguration
        .parse(List("sameModuleEmbedded=demo.Marker:demo/Producer.scala"))
        .map(_.map(value => value.annotationName -> value.producerSource)) ==
        Right(Vector("demo.Marker" -> "demo/Producer.scala"))
    )
    assert(EmbeddedSameModuleConfiguration.parse(List(
      "sameModuleEmbedded=demo.Marker:demo/Producer.scala",
      "sameModuleEmbedded=demo.Marker:demo/Producer.scala"
    )).isLeft)
    assert(EmbeddedSameModuleConfiguration.parse(List(
      "sameModuleEmbedded=Marker:demo/Producer.scala"
    )).isLeft)
    assert(EmbeddedSameModuleConfiguration.parse(List(
      "sameModuleEmbedded=demo.Marker:Producer.scala"
    )).isLeft)
    assert(EmbeddedSameModuleConfiguration.parse(List(
      "sameModuleEmbedded=demo.Marker:demo\\Producer.scala"
    )).isLeft)
    assert(EmbeddedSameModuleConfiguration.parse(List(
      "sameModuleEmbedded=demo.Marker:demo/../Producer.scala"
    )).isLeft)

  private def sameModuleModeConditionallyOrdersProducerBeforeParadiseGen(): Unit =
    assert(new EmbeddedProducerPhase(Nil).phaseName == "macroparadiseEmbeddedProducer")
    assert(new EmbeddedProducerPhase(Nil).runsBefore == Set("typer"))
    assert(new EmbeddedProducerPhase(Nil).splitMarkerUnitsEnabledForTesting)
    val sameModule = new EmbeddedProducerPhase(
      List("sameModuleEmbedded=demo.Marker:demo/Producer.scala")
    )
    assert(sameModule.runsBefore == Set("paradiseGen", "typer"))
    assert(!sameModule.splitMarkerUnitsEnabledForTesting)

  private def producerPairIsCanonicalSortedAndBounded(): Unit =
    val a = "sameModuleEmbedded=demo.MarkerA:demo/ProducerA.scala"
    val b = "sameModuleEmbedded=demo.MarkerB:demo/ProducerB.scala"
    assert(
      EmbeddedSameModuleConfiguration.parse(List(b, a)).map(_.map(value => value.annotationName -> value.producerSource)) ==
        Right(Vector("demo.MarkerA" -> "demo/ProducerA.scala", "demo.MarkerB" -> "demo/ProducerB.scala"))
    )
    assert(EmbeddedSameModuleConfiguration.parse(List(a, b, b)).isLeft)
    assert(EmbeddedSameModuleConfiguration.parse(List(a, a)).isLeft)
    assert(
      EmbeddedSameModuleConfiguration.parse(List(
        a,
        "sameModuleEmbedded=demo.MarkerB:demo/ProducerA.scala"
      )).isLeft
    )
