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

lazy val selectProducerEdge = taskKey[Unit]("Make producer A consume B")
lazy val selectCycle = taskKey[Unit]("Make producers A and B consume each other")
lazy val selectDual = taskKey[Unit]("Make one consumer select both bindings")
lazy val selectAlias = taskKey[Unit]("Use an aliased configured embedded annotation")
lazy val selectWildcard = taskKey[Unit]("Use a wildcard configured embedded annotation")
lazy val selectCollision = taskKey[Unit]("Collide with producer A private adapter name")
lazy val selectWrongBinding = taskKey[Unit]("Put the wrong embedded declaration in producer A")
lazy val selectDuplicateDeclaration = taskKey[Unit]("Put two opted-in declarations in producer A")

val demoDirectory = file("src/main/scala/demo")
val consumerDirectory = file("src/main/scala/consumer")
val producerAFile = demoDirectory / "ProducerA.scala"
val producerBFile = demoDirectory / "ProducerB.scala"
val consumerAFile = consumerDirectory / "ConsumerA.scala"
val consumerBFile = consumerDirectory / "ConsumerB.scala"
val initialProducerA = IO.read(producerAFile, StandardCharsets.UTF_8)
val initialProducerB = IO.read(producerBFile, StandardCharsets.UTF_8)
val initialConsumerA = IO.read(consumerAFile, StandardCharsets.UTF_8)
val initialConsumerB = IO.read(consumerBFile, StandardCharsets.UTF_8)

def resetSources(): Unit = {
  IO.write(producerAFile, initialProducerA, StandardCharsets.UTF_8)
  IO.write(producerBFile, initialProducerB, StandardCharsets.UTF_8)
  IO.write(consumerAFile, initialConsumerA, StandardCharsets.UTF_8)
  IO.write(consumerBFile, initialConsumerB, StandardCharsets.UTF_8)
}

selectProducerEdge := {
  resetSources()
  IO.write(producerAFile, initialProducerA.replace("@embeddedExpander", "@demo.markerB\n@embeddedExpander"), StandardCharsets.UTF_8)
}

selectCycle := {
  resetSources()
  IO.write(producerAFile, initialProducerA.replace("@embeddedExpander", "@demo.markerB\n@embeddedExpander"), StandardCharsets.UTF_8)
  IO.write(producerBFile, initialProducerB.replace("@embeddedExpander", "@demo.markerA\n@embeddedExpander"), StandardCharsets.UTF_8)
}

selectDual := {
  resetSources()
  IO.write(consumerAFile, initialConsumerA.replace("@demo.markerA", "@demo.markerA\n@demo.markerB"), StandardCharsets.UTF_8)
}

selectAlias := {
  resetSources()
  IO.write(consumerBFile, "package consumer\n\nimport demo.{markerB => aliasB}\n\n@aliasB final class ConsumerB\n", StandardCharsets.UTF_8)
}

selectWildcard := {
  resetSources()
  IO.write(consumerBFile, "package consumer\n\nimport demo.*\n\n@markerB final class ConsumerB\n", StandardCharsets.UTF_8)
}

selectCollision := {
  resetSources()
  IO.write(producerAFile, initialProducerA + "\nfinal class markerA__MacroParadiseEmbeddedExpansionHandler\n", StandardCharsets.UTF_8)
}

selectWrongBinding := {
  resetSources()
  IO.write(producerAFile, initialProducerA.replace("class markerA", "class wrongMarker").replace("object markerA", "object wrongMarker"), StandardCharsets.UTF_8)
}

selectDuplicateDeclaration := {
  resetSources()
  IO.write(producerAFile, initialProducerA + "\n@embeddedExpander\nfinal class extraMarker extends StaticAnnotation\n", StandardCharsets.UTF_8)
}
