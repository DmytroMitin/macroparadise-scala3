package demo

import scala.quoted.*

object SuspendedMacro:
  inline def generated: String = ${ generatedImpl }

  def generatedImpl(using Quotes): Expr[String] =
    Expr("suspension-ok")
