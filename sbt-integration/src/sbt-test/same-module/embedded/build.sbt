import java.nio.charset.StandardCharsets

enablePlugins(macroparadise.sbt.MacroParadiseSameModulePlugin)

scalaVersion := sys.props.getOrElse("test.scala.version", "3.8.4")

macroParadiseSameModuleEmbeddedBinding := Some(
  macroParadiseSameModuleEmbedded(
    annotationName = "demo.sameModuleGreeting",
    producerSource = macroParadiseLabelledSource(
      "embedded-producer-source",
      "demo/EmbeddedAnnotations.scala"
    )
  )
)

lazy val verifyInitial = taskKey[Unit]("Verify the bounded embedded same-module configuration")
lazy val editProducerV2 = taskKey[Unit]("Edit only the configured producer transform body")
lazy val verifyChanged = taskKey[Unit]("Verify producer source identity changes")
lazy val restoreProducerV1 = taskKey[Unit]("Restore the configured producer transform body")
lazy val verifyRestored = taskKey[Unit]("Verify producer source identity returns to baseline")
lazy val breakProducer = taskKey[Unit]("Break the producer with current output still present")
lazy val repairProducer = taskKey[Unit]("Repair the producer after the stale-output rejection")

verifyInitial := {
  val derived = macroParadiseSameModuleConfiguration.value
  assert(derived.sourceIdentity.sources == Vector(
    macroParadiseLabelledSource("embedded-producer-source", "demo/EmbeddedAnnotations.scala")
  ))
  assert(derived.sourceIdentity.manifest.linesIterator.size == 1)
  assert(derived.compilerOptions.count(_.startsWith("-P:macroparadise:sameModuleEmbedded=")) == 1)
  assert(derived.compilerOptions.count(_.startsWith("-P:macroparadise:sameModuleSourceIdentity=")) == 1)
  assert(derived.compilerOptions.count(_.startsWith("-P:macroparadise-embedded-producer:sameModuleEmbedded=")) == 1)
  assert(derived.compilerOptions.forall(!_.startsWith("-P:macroparadise:sameModuleHandler=")))
  IO.write(target.value / "initial-identity.txt", derived.sourceIdentity.identity, StandardCharsets.UTF_8)
}

editProducerV2 := {
  val producer = (Compile / scalaSource).value / "demo" / "EmbeddedAnnotations.scala"
  val current = IO.read(producer, StandardCharsets.UTF_8)
  assert(current.contains("same-module-v1"))
  IO.write(producer, current.replace("same-module-v1", "same-module-v2"), StandardCharsets.UTF_8)
}

verifyChanged := {
  val before = IO.read(target.value / "initial-identity.txt", StandardCharsets.UTF_8)
  val after = macroParadiseSameModuleSourceIdentity.value
  assert(after != before)
}

restoreProducerV1 := {
  val producer = (Compile / scalaSource).value / "demo" / "EmbeddedAnnotations.scala"
  val current = IO.read(producer, StandardCharsets.UTF_8)
  assert(current.contains("same-module-v2"))
  IO.write(producer, current.replace("same-module-v2", "same-module-v1"), StandardCharsets.UTF_8)
}

verifyRestored := {
  val before = IO.read(target.value / "initial-identity.txt", StandardCharsets.UTF_8)
  val restored = macroParadiseSameModuleSourceIdentity.value
  assert(restored == before)
}

breakProducer := {
  val producer = (Compile / scalaSource).value / "demo" / "EmbeddedAnnotations.scala"
  val current = IO.read(producer, StandardCharsets.UTF_8)
  assert(current.contains("yield prefix"))
  IO.write(producer, current.replace("yield prefix", "yield prefix +"), StandardCharsets.UTF_8)
}

repairProducer := {
  val producer = (Compile / scalaSource).value / "demo" / "EmbeddedAnnotations.scala"
  val current = IO.read(producer, StandardCharsets.UTF_8)
  assert(current.contains("yield prefix +"))
  IO.write(producer, current.replace("yield prefix +", "yield prefix"), StandardCharsets.UTF_8)
}
