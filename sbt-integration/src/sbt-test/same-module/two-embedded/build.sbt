import java.nio.charset.StandardCharsets

resolvers ++= sys.props.get("p229.ivy").toSeq.map { root =>
  Resolver.file("p236-task-ivy", file(root) / "local")(Resolver.ivyStylePatterns)
}

enablePlugins(macroparadise.sbt.MacroParadiseSameModulePlugin)

scalaVersion := sys.props.getOrElse("test.scala.version", "3.8.4")

macroParadiseSameModuleCompilerProductVersion :=
  sys.props.getOrElse("macroparadise.product.version", "0.2.0-SNAPSHOT")

macroParadiseSameModuleEmbeddedBindings := Seq(
  macroParadiseSameModuleEmbedded(
    "demo.markerA",
    macroParadiseLabelledSource("a-producer", "demo/ProducerA.scala")
  ),
  macroParadiseSameModuleEmbedded(
    "demo.markerB",
    macroParadiseLabelledSource("b-producer", "demo/ProducerB.scala")
  )
)

lazy val verifyInitial = taskKey[Unit]("Verify the exact-two embedded compiler wire")
lazy val editAtoV2 = taskKey[Unit]("Edit only producer A")
lazy val verifyChanged = taskKey[Unit]("Verify aggregate identity changed")
lazy val editConsumerOnly = taskKey[Unit]("Edit only the co-located consumer and Main source")
lazy val breakA = taskKey[Unit]("Break producer A with prior output present")
lazy val repairA = taskKey[Unit]("Repair producer A without clean")
lazy val selectFifthSourceTopology = taskKey[Unit]("Move Main into a separately unsuspended fifth source")

verifyInitial := {
  val derived = macroParadiseSameModuleConfiguration.value
  val macroRelationships = derived.compilerOptions.filter(_.startsWith("-P:macroparadise:sameModuleEmbedded="))
  val producerRelationships = derived.compilerOptions.filter(_.startsWith("-P:macroparadise-embedded-producer:sameModuleEmbedded="))
  assert(macroRelationships == Seq(
    "-P:macroparadise:sameModuleEmbedded=demo.markerA:demo/ProducerA.scala",
    "-P:macroparadise:sameModuleEmbedded=demo.markerB:demo/ProducerB.scala"
  ))
  assert(producerRelationships.map(_.stripPrefix("-P:macroparadise-embedded-producer:")) ==
    macroRelationships.map(_.stripPrefix("-P:macroparadise:")))
  assert(derived.compilerOptions.count(_.startsWith("-P:macroparadise:sameModuleSourceIdentity=")) == 1)
  assert(derived.compilerOptions.count(_.startsWith("-P:macroparadise:handlerClasspath=")) == 1)
  assert(derived.sourceIdentity.manifest.linesIterator.size == 2)
  IO.write(target.value / "initial-identity.txt", derived.sourceIdentity.identity, StandardCharsets.UTF_8)
}

editAtoV2 := {
  val producer = (Compile / scalaSource).value / "demo" / "ProducerA.scala"
  val current = IO.read(producer, StandardCharsets.UTF_8)
  assert(current.contains("A-v1"))
  IO.write(producer, current.replace("A-v1", "A-v2"), StandardCharsets.UTF_8)
}

verifyChanged := {
  val before = IO.read(target.value / "initial-identity.txt", StandardCharsets.UTF_8)
  assert(macroParadiseSameModuleSourceIdentity.value != before)
}

editConsumerOnly := {
  val consumer = (Compile / scalaSource).value / "consumer" / "ConsumerA.scala"
  val current = IO.read(consumer, StandardCharsets.UTF_8)
  assert(!current.contains("consumer-only-edit"))
  IO.write(consumer, current + "\n// consumer-only-edit\n", StandardCharsets.UTF_8)
}

breakA := {
  val producer = (Compile / scalaSource).value / "demo" / "ProducerA.scala"
  val current = IO.read(producer, StandardCharsets.UTF_8)
  assert(current.contains("yield next"))
  IO.write(producer, current.replace("yield next", "yield next +"), StandardCharsets.UTF_8)
}

repairA := {
  val producer = (Compile / scalaSource).value / "demo" / "ProducerA.scala"
  val current = IO.read(producer, StandardCharsets.UTF_8)
  assert(current.contains("yield next +"))
  IO.write(producer, current.replace("yield next +", "yield next"), StandardCharsets.UTF_8)
}

selectFifthSourceTopology := {
  val consumer = (Compile / scalaSource).value / "consumer" / "ConsumerA.scala"
  val main = (Compile / scalaSource).value / "consumer" / "Main.scala"
  val current = IO.read(consumer, StandardCharsets.UTF_8)
  val marker = "\nobject Main:"
  val split = current.indexOf(marker)
  assert(split >= 0)
  IO.write(consumer, current.substring(0, split) + "\n", StandardCharsets.UTF_8)
  IO.write(
    main,
    """package consumer
      |
      |object Main:
      |  def main(args: Array[String]): Unit =
      |    val a = new ConsumerA().generatedValueA
      |    val b = new ConsumerB().generatedValueB
      |    assert(a == "A-v2")
      |    assert(b == "B-v1")
      |""".stripMargin,
    StandardCharsets.UTF_8
  )
}
