package starter.handler

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.{ExpansionInput, ExpansionOutcome, ParadiseAnnotationExpander}
import paradise3.api.helpers.ExpansionHelpers

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}

final class GenerateGreetingHandler extends ParadiseAnnotationExpander:
  val annotationName: String = "starter.marker.generateGreeting"

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    recordExpansion()
    ExpansionHelpers.withAnnotatedClassView(input): view =>
      ExpansionHelpers.addStringMethodToClass(
        input,
        methodName = "generatedGreeting",
        value = s"Hello, ${view.className}!"
      )

  private def recordExpansion(): Unit =
    Option(System.getProperty("macroparadise.starter.expandTrace")).foreach: rawPath =>
      Files.writeString(
        Path.of(rawPath),
        "expand\n",
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND
      )
