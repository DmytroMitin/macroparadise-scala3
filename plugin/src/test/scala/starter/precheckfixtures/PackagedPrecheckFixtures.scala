package starter.precheckfixtures

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.{ExpansionInput, ExpansionOutcome, ParadiseAnnotationExpander, expander}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import scala.annotation.StaticAnnotation

@expander("starter.precheckfixtures.ValidHandler")
final class ValidMarker extends StaticAnnotation

final class ValidHandler extends ParadiseAnnotationExpander:
  val annotationName: String = "starter.precheckfixtures.ValidMarker"

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    Option(System.getProperty("macroparadise.precheck.expandTrace")).foreach: rawPath =>
      Files.writeString(
        Path.of(rawPath),
        "expand\n",
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND
      )
    ExpansionOutcome.NotApplicable
