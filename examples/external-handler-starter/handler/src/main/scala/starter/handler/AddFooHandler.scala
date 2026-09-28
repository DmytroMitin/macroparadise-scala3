package starter.handler

import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.*
import paradise3.api.helpers.{ExpansionTransforms, MissingCompanionPolicy}
import quasiquotes.definitions.dotty.ScalametaDefinitionGeneratedOriginBridge
import scala.meta.*
import scala.meta.dialects.Scala3

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}

final class AddFooHandler extends ExpansionHandler:
  val annotationName: String = "starter.marker.addFoo"

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    recordExpansion()

    def lower(definition: Defn, virtualSource: String): Either[ExpansionDiagnostic, untpd.Tree] =
      ScalametaDefinitionGeneratedOriginBridge
        .lower(definition, virtualSource)
        .map(_.tree)
        .left
        .map(error =>
          ExpansionDiagnostic(
            error.code + ": " + error.detail,
            input.currentAnnotation.sourcePos
          )
        )

    val owner = Term.Name(input.primary.name)
    ExpansionEdit.finish:
      for
        edit <- ExpansionEdit.start(input)
        _ <- input.primary match
          case ExpansionTarget.Class(_) => Right(())
          case _ => Left(ExpansionDiagnostic("@addFoo requires a class primary", input.currentAnnotation.sourcePos))
        primaryFoo <- lower(
          q"""def foo(x: Int): String = x.toString""",
          "<macroparadise-generated:AddFooHandler:primaryFoo>"
        )
        primaryFoo1 <- lower(
          q"""def foo1(x: Int): String = foo(x)""",
          "<macroparadise-generated:AddFooHandler:primaryFoo1>"
        )
        primaryFoo2 <- lower(
          q"""def foo2(x: Int): String = $owner.objectMethod(x)""",
          "<macroparadise-generated:AddFooHandler:primaryFoo2>"
        )
        companionFoo <- lower(
          q"""def foo(x: Int): String = x.toString""",
          "<macroparadise-generated:AddFooHandler:companionFoo>"
        )
        missingObjectMethod <- lower(
          q"""def objectMethod(x: Int): String = x.toString""",
          "<macroparadise-generated:AddFooHandler:missingObjectMethod>"
        )
        primaryWithFoo <- ExpansionTransforms.placeMemberInPrimary(primaryFoo)(edit)
        primaryWithFoo1 <- ExpansionTransforms.placeMemberInPrimary(primaryFoo1)(primaryWithFoo)
        primaryWithFoo2 <- ExpansionTransforms.placeMemberInPrimary(primaryFoo2)(primaryWithFoo1)
        companionReady <-
          if input.companion.isEmpty then
            ExpansionTransforms.placeMemberInCompanion(
              missingObjectMethod,
              MissingCompanionPolicy.Create(
                ExpansionTargetKind.Object,
                DefinitionPlacement.AfterPrimary
              )
            )(primaryWithFoo2)
          else Right(primaryWithFoo2)
        result <- ExpansionTransforms.placeMemberInCompanion(
          companionFoo,
          MissingCompanionPolicy.Create(
            ExpansionTargetKind.Object,
            DefinitionPlacement.AfterPrimary
          )
        )(companionReady)
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
