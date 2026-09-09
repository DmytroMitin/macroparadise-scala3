package starter.precheckfixtures

import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.{ExpansionChanges, ExpansionHandler, ExpansionInput, ExpansionOutcome, expander}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import scala.annotation.StaticAnnotation

@expander("starter.precheckfixtures.ValidHandler")
final class ValidMarker extends StaticAnnotation

final class ValidHandler extends ExpansionHandler:
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
    ExpansionOutcome.Structured(ExpansionChanges())

@expander("starter.precheckfixtures.DoesNotExist")
final class MissingHandlerMarker extends StaticAnnotation

@expander("starter.precheckfixtures.NotAHandler")
final class InvalidContractMarker extends StaticAnnotation

final class NotAHandler

@expander("starter.precheckfixtures.BindingMismatchHandler")
final class BindingMismatchMarker extends StaticAnnotation

final class BindingMismatchHandler extends ExpansionHandler:
  val annotationName: String = "starter.precheckfixtures.OtherMarker"

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.Structured(ExpansionChanges())

@expander("   ")
final class WhitespaceMetadataMarker extends StaticAnnotation
