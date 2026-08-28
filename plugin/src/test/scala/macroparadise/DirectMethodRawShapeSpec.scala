package macroparadise

import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.ast.Trees
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Contexts.{Context, ContextBase}
import dotty.tools.dotc.core.Flags.{Given, Implicit}
import dotty.tools.dotc.parsing.Parsers

class DirectMethodRawShapeSpec extends munit.FunSuite:
  test("pinned parser preserves no-clause empty-clause ordinary contextual and default distinctions") {
    val (template, context) = parsedTemplate(
      """trait ClauseShapes[A]:
        |  def noClause: A
        |  def emptyClause(): A
        |  def ordinary(first: A, second: A): A
        |  def contextual(using value: A): A
        |  def legacy(implicit value: A): A
        |  def defaulted(value: A = ???): A
        |""".stripMargin
    )
    given Context = context
    val methods = template.body.collect { case method: DefDef => method }.map(method => method.name.toString -> method).toMap

    assertEquals(methods("noClause").termParamss, Nil)
    assertEquals(methods("emptyClause").termParamss.map(_.size), List(0))
    assertEquals(methods("ordinary").termParamss.map(_.map(_.name.toString)), List(List("first", "second")))
    assert(methods("contextual").termParamss.head.forall(parameter => Trees.mods(parameter).is(Given)))
    assert(methods("legacy").termParamss.head.forall(parameter => Trees.mods(parameter).is(Implicit)))
    assert(!methods("defaulted").termParamss.head.head.rhs.isEmpty)
  }

  test("pinned parser exposes method type parameters and bounded raw type-tree categories") {
    val (template, context) = parsedTemplate(
      """trait TypeShapes[A]:
        |  def enclosing(value: A): A
        |  def polymorphic[B](value: B): B
        |  def applied(value: List[A]): List[A]
        |  def qualified(value: example.Types.Alias): example.Types.Alias
        |  def function(value: A => A): A => A
        |""".stripMargin
    )
    given Context = context
    val methods = template.body.collect { case method: DefDef => method }.map(method => method.name.toString -> method).toMap

    assertEquals(methods("enclosing").leadingTypeParams, Nil)
    assertEquals(methods("polymorphic").leadingTypeParams.map(_.name.toString), List("B"))
    assert(methods("enclosing").termParamss.head.head.tpt.isInstanceOf[Ident])
    assert(methods("applied").termParamss.head.head.tpt.isInstanceOf[AppliedTypeTree])
    assert(methods("qualified").termParamss.head.head.tpt.isInstanceOf[Select])
    assert(methods("function").termParamss.head.head.tpt.isInstanceOf[Function])
  }

  test("pinned parser preserves ordered concrete and non-method direct members") {
    val (template, context) = parsedTemplate(
      """trait MemberShapes[A]:
        |  def abstractMethod: A
        |  val abstractValue: A
        |  var abstractVariable: A
        |  type Member
        |  object NestedObject
        |  class NestedClass
        |  def concreteMethod: A = ???
        |""".stripMargin
    )
    given Context = context

    val names = template.body.map:
      case method: DefDef => method.name.toString
      case value: ValDef => value.name.toString
      case member: TypeDef => member.name.toString
      case module: ModuleDef => module.name.toString
      case other => other.getClass.getSimpleName

    assertEquals(
      names,
      List("abstractMethod", "abstractValue", "abstractVariable", "Member", "NestedObject", "NestedClass", "concreteMethod")
    )
    val methods = template.body.collect { case method: DefDef => method }.map(method => method.name.toString -> method).toMap
    assert(methods("abstractMethod").rhs.isEmpty)
    assert(!methods("concreteMethod").rhs.isEmpty)
  }

  private def parsedTemplate(code: String): (Template, Context) =
    val unit = CompilationUnit("DirectMethodRawShapeSpec.scala", code)
    val context = ContextBase().initialCtx.fresh.setCompilationUnit(unit)
    val parsed = new Parsers.Parser(unit.source)(using context).parse()
    val stats = parsed match
      case PackageDef(_, values) => values
      case tree => List(tree)
    val template = stats.collectFirst {
      case definition: TypeDef if definition.isClassDef => definition.rhs.asInstanceOf[Template]
    }.getOrElse(fail(s"missing class template in $stats"))
    (template, context)
