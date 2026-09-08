package starter.handler

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Names.{termName, typeName}
import paradise3.api.*
import paradise3.api.helpers.ExpansionHelpers

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}

final class GenerateGreetingHandler extends ExpansionHandler:
  val annotationName: String = "starter.marker.generateGreeting"
  val admissions = List(ExpansionAdmission(ExpansionTargetKind.Class, ExpansionShapeProfile.NonCaseNonGenericTemplate))

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    recordExpansion()
    ExpansionEdit.finish:
      for
        edit <- ExpansionEdit.start(input)
        view <- input.targetView
        member = untpd.DefDef(
          termName("generatedGreeting"),
          Nil,
          untpd.Ident(typeName("String")),
          untpd.Literal(Constant(s"Hello, ${view.className}!"))
        )
        result <- ExpansionHelpers.placeMemberInPrimary(edit, member)
      yield result

  private def recordExpansion(): Unit =
    Option(System.getProperty("macroparadise.starter.expandTrace")).foreach: rawPath =>
      Files.writeString(
        Path.of(rawPath),
        "expand\n",
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND
      )
