package starter.handler

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.*
import paradise3.api.helpers.ExpansionHelpers
import quasiquotes.definitions.dotty.ScalametaDefinitionGeneratedOriginBridge
import scala.meta.*
import scala.meta.dialects.Scala3

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}

final class GenerateGreetingHandler extends ExpansionHandler:
  val annotationName: String = "starter.marker.generateGreeting"

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    recordExpansion()
    ExpansionEdit.finish:
      for
        edit <- ExpansionEdit.start(input)
        _ <- input.primary match
          case ExpansionTarget.Class(_) => Right(())
          case _ => Left(ExpansionDiagnostic("@generateGreeting requires a class primary", input.currentAnnotation.sourcePos))
        definition = q"""def generatedGreeting: String = "Hello, Greeter!" """.asInstanceOf[Defn.Def]
        lowered <- ScalametaDefinitionGeneratedOriginBridge
          .lower(
            definition,
            "<macroparadise-generated:GenerateGreetingHandler:generatedGreeting>"
          )
          .left
          .map(error => ExpansionDiagnostic(s"${error.code}: ${error.detail}", input.currentAnnotation.sourcePos))
        result <- ExpansionHelpers.placeMemberInPrimary(edit, lowered.tree)
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
