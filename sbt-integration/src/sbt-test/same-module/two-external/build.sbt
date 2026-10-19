import java.nio.charset.StandardCharsets

resolvers ++= sys.props.get("p229.ivy").toSeq.map { root =>
  Resolver.file("p229-task-ivy", file(root) / "local")(Resolver.ivyStylePatterns)
}

enablePlugins(macroparadise.sbt.MacroParadiseSameModulePlugin)

scalaVersion := sys.props.getOrElse("test.scala.version", "3.8.4")
macroParadiseSameModuleCompilerProductVersion :=
  sys.props.getOrElse("macroparadise.product.version", "0.2.0-SNAPSHOT")

macroParadiseSameModuleBindings := Seq(
  macroParadiseSameModuleHandler(
    annotationName = "demo.markerB",
    handlerClassName = "demo.HandlerB",
    markerSource = macroParadiseLabelledSource("b-marker-source", "demo/MarkerB.scala"),
    handlerSource = macroParadiseLabelledSource("b-handler-source", "demo/HandlerB.scala")
  ),
  macroParadiseSameModuleHandler(
    annotationName = "demo.markerA",
    handlerClassName = "demo.HandlerA",
    markerSource = macroParadiseLabelledSource("a-marker-source", "demo/MarkerA.scala"),
    handlerSource = macroParadiseLabelledSource("a-handler-source", "demo/HandlerA.scala")
  )
)

lazy val verifyInitial = taskKey[Unit]("Verify exact-two options and record the aggregate identity")
lazy val editHandlerA = taskKey[Unit]("Edit only HandlerA from A-v1 to A-v2")
lazy val verifyChanged = taskKey[Unit]("Verify the aggregate identity changes after the A-only edit")
lazy val restoreHandlerA = taskKey[Unit]("Restore HandlerA to the exact original bytes")
lazy val verifyRestored = taskKey[Unit]("Verify the original aggregate identity is restored")
lazy val breakHandlerA = taskKey[Unit]("Make HandlerA syntactically invalid while old output exists")
lazy val repairHandlerA = taskKey[Unit]("Repair HandlerA to A-v2 without clean")

def handlerA(value: String): String =
  s"""package demo
     |
     |import dotty.tools.dotc.core.Contexts.Context
     |import paradise3.api.*
     |import paradise3.api.helpers.ExpansionHelpers
     |
     |final class HandlerA extends ExpansionHandler:
     |  val annotationName: String = "demo.markerA"
     |  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
     |    ExpansionEdit.finish:
     |      ExpansionEdit.start(input).flatMap: edit =>
     |        ExpansionHelpers.placeMemberInPrimary(
     |          edit,
     |          dotty.tools.dotc.ast.untpd.DefDef(
     |            dotty.tools.dotc.core.Names.termName("generatedValue"),
     |            Nil,
     |            dotty.tools.dotc.ast.untpd.Ident(dotty.tools.dotc.core.Names.typeName("String")),
     |            dotty.tools.dotc.ast.untpd.Literal(dotty.tools.dotc.core.Constants.Constant("$value"))
     |          )
     |        )
     |""".stripMargin

verifyInitial := {
  val derived = macroParadiseSameModuleConfiguration.value
  val relationships = derived.compilerOptions.filter(_.startsWith("-P:macroparadise:sameModuleHandler="))
  assert(relationships.size == 2)
  assert(relationships == relationships.sorted)
  assert(derived.compilerOptions.count(_.startsWith("-P:macroparadise:sameModuleSourceIdentity=")) == 1)
  assert(derived.compilerOptions.count(_.startsWith("-P:macroparadise:handlerClasspath=")) == 1)
  assert(derived.sourceIdentity.sources.map(_.label) == Vector(
    "a-handler-source",
    "a-marker-source",
    "b-handler-source",
    "b-marker-source"
  ))
  IO.write(target.value / "initial-identity.txt", derived.sourceIdentity.identity, StandardCharsets.UTF_8)
}

editHandlerA := {
  IO.write((Compile / scalaSource).value / "demo" / "HandlerA.scala", handlerA("A-v2"), StandardCharsets.UTF_8)
}

verifyChanged := {
  val before = IO.read(target.value / "initial-identity.txt", StandardCharsets.UTF_8)
  val after = macroParadiseSameModuleConfiguration.value.sourceIdentity.identity
  assert(after != before)
}

restoreHandlerA := {
  IO.write((Compile / scalaSource).value / "demo" / "HandlerA.scala", handlerA("A-v1"), StandardCharsets.UTF_8)
}

verifyRestored := {
  val before = IO.read(target.value / "initial-identity.txt", StandardCharsets.UTF_8)
  val after = macroParadiseSameModuleConfiguration.value.sourceIdentity.identity
  assert(after == before)
}

breakHandlerA := {
  IO.write(
    (Compile / scalaSource).value / "demo" / "HandlerA.scala",
    handlerA("A-v2") + "\n  this is not valid Scala\n",
    StandardCharsets.UTF_8
  )
}

repairHandlerA := {
  IO.write((Compile / scalaSource).value / "demo" / "HandlerA.scala", handlerA("A-v2"), StandardCharsets.UTF_8)
}
