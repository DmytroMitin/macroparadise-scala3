import java.nio.charset.StandardCharsets

resolvers ++= sys.props.get("p229.ivy").toSeq.map { root =>
  Resolver.file("p229-task-ivy", file(root) / "local")(Resolver.ivyStylePatterns)
}

enablePlugins(macroparadise.sbt.MacroParadiseSameModulePlugin)

scalaVersion := sys.props.getOrElse("test.scala.version", "3.8.4")

macroParadiseSameModuleBindings := Seq(
  macroParadiseSameModuleHandler(
    "demo.markerA",
    "demo.HandlerA",
    macroParadiseLabelledSource("a-marker-source", "demo/MarkerA.scala"),
    macroParadiseLabelledSource("a-handler-source", "demo/HandlerA.scala")
  ),
  macroParadiseSameModuleHandler(
    "demo.markerB",
    "demo.HandlerB",
    macroParadiseLabelledSource("b-marker-source", "demo/MarkerB.scala"),
    macroParadiseLabelledSource("b-handler-source", "demo/HandlerB.scala")
  )
)

lazy val selectG3 = taskKey[Unit]("Make HandlerA a direct markerB consumer")
lazy val selectG4 = taskKey[Unit]("Make HandlerA and HandlerB a direct cycle")
lazy val selectAmbiguous = taskKey[Unit]("Put markerA and markerB on one consumer unit")
lazy val selectDynamic = taskKey[Unit]("Make HandlerA introduce markerB during expansion")
lazy val selectShorthand = taskKey[Unit]("Use an unsupported short pair annotation")
lazy val selectWildcard = taskKey[Unit]("Use an unsupported wildcard-imported pair annotation")
lazy val selectAlias = taskKey[Unit]("Use an unsupported aliased pair annotation")
lazy val selectMixedSpelling = taskKey[Unit]("Mix direct A with imported-short B in one unit")

val sourceDirectory = file("src/main/scala/demo")
val handlerAFile = sourceDirectory / "HandlerA.scala"
val handlerBFile = sourceDirectory / "HandlerB.scala"
val consumerAFile = sourceDirectory / "ConsumerA.scala"
val initialHandlerA = IO.read(handlerAFile, StandardCharsets.UTF_8)
val initialHandlerB = IO.read(handlerBFile, StandardCharsets.UTF_8)
val initialConsumerA = IO.read(consumerAFile, StandardCharsets.UTF_8)

def resetSources(): Unit = {
  IO.write(handlerAFile, initialHandlerA, StandardCharsets.UTF_8)
  IO.write(handlerBFile, initialHandlerB, StandardCharsets.UTF_8)
  IO.write(consumerAFile, initialConsumerA, StandardCharsets.UTF_8)
}

selectG3 := {
  resetSources()
  IO.write(handlerAFile, initialHandlerA.replace("final class HandlerA", "@demo.markerB\nfinal class HandlerA"), StandardCharsets.UTF_8)
}

selectG4 := {
  resetSources()
  IO.write(handlerAFile, initialHandlerA.replace("final class HandlerA", "@demo.markerB\nfinal class HandlerA"), StandardCharsets.UTF_8)
  IO.write(handlerBFile, initialHandlerB.replace("final class HandlerB", "@demo.markerA\nfinal class HandlerB"), StandardCharsets.UTF_8)
}

selectAmbiguous := {
  resetSources()
  IO.write(consumerAFile, initialConsumerA.replace("@demo.markerA", "@demo.markerA\n@demo.markerB"), StandardCharsets.UTF_8)
}

selectDynamic := {
  resetSources()
  IO.write(
    handlerAFile,
    """package demo
      |
      |import dotty.tools.dotc.core.Contexts.Context
      |import dotty.tools.dotc.ast.Trees
      |import dotty.tools.dotc.ast.untpd
      |import dotty.tools.dotc.ast.untpd.*
      |import dotty.tools.dotc.core.Names.{termName, typeName}
      |import paradise3.api.*
      |import paradise3.api.helpers.ExpansionHelpers
      |
      |final class HandlerA extends ExpansionHandler:
      |  val annotationName: String = "demo.markerA"
      |  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
      |    ExpansionEdit.finish:
      |      for
      |        edit <- ExpansionEdit.start(input)
      |        markerB = untpd.Apply(
      |          untpd.Select(
      |            untpd.New(untpd.Select(untpd.Ident(termName("demo")), typeName("markerB"))),
      |            termName("<init>")
      |          ),
      |          Nil
      |        )
      |        annotations = Trees.mods(input.primary.tree.asInstanceOf[TypeDef]).annotations :+ markerB
      |        result <- ExpansionHelpers.replacePrimaryAnnotations(edit, annotations)
      |      yield result
      |""".stripMargin,
    StandardCharsets.UTF_8
  )
}

selectShorthand := {
  resetSources()
  IO.write(
    consumerAFile,
    """package consumer
      |
      |import demo.markerA
      |@markerA final class ConsumerA
      |""".stripMargin,
    StandardCharsets.UTF_8
  )
}

selectWildcard := {
  resetSources()
  IO.write(
    consumerAFile,
    """package consumer
      |
      |import demo.*
      |@markerA final class ConsumerA
      |""".stripMargin,
    StandardCharsets.UTF_8
  )
}

selectAlias := {
  resetSources()
  IO.write(
    consumerAFile,
    """package consumer
      |
      |import demo.{markerA => aliasA}
      |@aliasA final class ConsumerA
      |""".stripMargin,
    StandardCharsets.UTF_8
  )
}

selectMixedSpelling := {
  resetSources()
  IO.write(
    consumerAFile,
    """package demo
      |
      |import demo.markerB
      |@demo.markerA
      |@markerB final class ConsumerA
      |""".stripMargin,
    StandardCharsets.UTF_8
  )
}
