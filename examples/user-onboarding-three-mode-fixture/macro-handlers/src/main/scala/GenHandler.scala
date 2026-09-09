package com.example.macros.handlers

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Names.{termName, typeName}
import paradise3.api.*
import paradise3.api.helpers.ExpansionHelpers

final class GenHandler extends ExpansionHandler:
  override def annotationName: String = "com.example.macros.annotations.gen"

  override def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionEdit.finish:
      ExpansionEdit.start(input).flatMap: edit =>
        ExpansionHelpers.placeMemberInPrimary(
          edit,
          untpd.DefDef(termName("generatedHello"), Nil, untpd.Ident(typeName("String")), untpd.Literal(Constant(s"hello ${input.primary.name}")))
        )
