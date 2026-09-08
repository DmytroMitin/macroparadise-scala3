package com.example.`macro`.handlers

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Names.*
import paradise3.api.{ExpansionAdmission, ExpansionEdit, ExpansionHandler, ExpansionInput, ExpansionOutcome, ExpansionShapeProfile, ExpansionTargetKind}
import paradise3.api.helpers.ExpansionHelpers

final class GenHandler extends ExpansionHandler:
  override def annotationName: String =
    "com.example.macro.annotations.gen"

  override val admissions = List(ExpansionAdmission(ExpansionTargetKind.Class, ExpansionShapeProfile.OrdinaryTemplate))

  override def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    val method = DefDef(termName("generatedHello"), Nil, Ident(typeName("String")), Literal(Constant(s"hello ${input.primary.name}")))
    ExpansionEdit.finish(ExpansionEdit.start(input).flatMap(edit => ExpansionHelpers.placeMemberInPrimary(edit, method)))
