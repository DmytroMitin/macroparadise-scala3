package macroparadise

import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Contexts.{Context, ContextBase}
import dotty.tools.dotc.core.Names.{Name, typeName}
import dotty.tools.dotc.parsing.Parsers
import paradise3.api.{AnnotatedClassBodyView, ExpansionInput}
import paradise3.api.AnnotatedClassBodyView.*

class AnnotatedClassBodyViewSpec extends munit.FunSuite:
  test("decodes the representative Monoid body as ordered abstract methods") {
    val decoded = body(
      """trait Monoid[A]:
        |  def empty: A
        |  def combine(a: A, a1: A): A
        |""".stripMargin
    )

    assertEquals(decoded.members.map(_.name), List("empty", "combine"))
    assertEquals(decoded.members.map(_.kind), List.fill(2)(DirectMemberKind.Method))
    assertEquals(decoded.members.flatMap(_.method).map(_.status), List.fill(2)(DirectMethodStatus.Abstract))
  }

  test("distinguishes no clause empty parentheses and an ordinary clause") {
    val methods = methodMap(
      body(
        """trait Clauses[A]:
          |  def noClause: A
          |  def emptyClause(): A
          |  def ordinary(first: A, second: A): A
          |""".stripMargin
      )
    )

    assertEquals(methods("noClause").parameterClauses, Nil)
    assertEquals(methods("emptyClause").parameterClauses.map(_.parameters), List(Nil))
    assertEquals(methods("ordinary").parameterClauses.map(_.parameters.map(_.name)), List(List("first", "second")))
    assert(!methods("ordinary").parameterClauses.head.isContextual)
  }

  test("normalizes ordered Monoid parameter and result types to the enclosing type parameter") {
    val combine = methodMap(body("trait Monoid[A]:\n  def combine(a: A, a1: A): A"))("combine")

    assertEquals(combine.parameterClauses.head.parameters.map(_.name), List("a", "a1"))
    assertEquals(
      combine.parameterClauses.head.parameters.map(parameter => enclosingName(parameter.parameterType)),
      List("A", "A")
    )
    assertEquals(enclosingName(combine.resultType), "A")
  }

  test("normalizes the exact delegated Show shape without resolving its simple result name") {
    val show = methodMap(body("trait Show[A]:\n  def show(a: A): String"))("show")
    val parameter = show.parameterClauses.head.parameters.head

    assertEquals(parameter.name, "a")
    assertEquals(enclosingName(parameter.parameterType), "A")
    assertEquals(namedType(show.resultType), "String")
    assert(show.resultTypePos.span.exists)
    assertEquals(typePosition(show.resultType), show.resultTypePos)
  }

  test("normalizes an ordinary simple named parameter and preserves its raw type position") {
    val echo = methodMap(body("trait Echo:\n  def echo(s: String): String"))("echo")
    val parameter = echo.parameterClauses.head.parameters.head

    assertEquals(namedType(parameter.parameterType), "String")
    assertEquals(typePosition(parameter.parameterType), parameter.typePos)
    assert(parameter.typePos.span.exists)
  }

  test("exposes using implicit and default evidence without treating clauses as ordinary") {
    val methods = methodMap(
      body(
        """trait UnsupportedClauses[A]:
          |  def contextual(using value: A): A
          |  def legacy(implicit value: A): A
          |  def defaulted(value: A = ???): A
          |""".stripMargin
      )
    )

    assert(methods("contextual").parameterClauses.head.isContextual)
    assert(methods("contextual").parameterClauses.head.isGiven)
    assert(methods("legacy").parameterClauses.head.isContextual)
    assert(methods("legacy").parameterClauses.head.isImplicit)
    assert(methods("defaulted").parameterClauses.head.parameters.head.hasDefault)
  }

  test("exposes method type parameters and never classifies a method-local reference as enclosing") {
    val polymorphic = methodMap(body("trait Poly[A]:\n  def convert[A](value: A): A"))("convert")

    assertEquals(polymorphic.typeParameters.map(_.name), List("A"))
    assertEquals(unsupportedKind(polymorphic.parameterClauses.head.parameters.head.parameterType), "method-type-parameter-reference")
    assertEquals(unsupportedKind(polymorphic.resultType), "method-type-parameter-reference")
  }

  test("keeps applied qualified and function types as explicit unsupported shapes") {
    val methods = methodMap(
      body(
        """trait TypeShapes[A]:
          |  def applied(value: List[A]): List[A]
          |  def qualified(value: example.Types.Alias): example.Types.Alias
          |  def function(value: A => A): A => A
          |""".stripMargin
      )
    )

    assertEquals(unsupportedKind(methods("applied").resultType), "applied-type")
    assertEquals(unsupportedKind(methods("qualified").resultType), "qualified-type")
    assertEquals(unsupportedKind(methods("function").resultType), "function-type")
  }

  test("keeps inferred and absent parameter and result types explicitly unsupported") {
    val inferred = methodMap(body("trait InferredType:\n  def inferred = ???"))("inferred")
    assertEquals(unsupportedKind(inferred.resultType), "inferred-or-missing-type")

    val (stats, context) = parsedStats("trait MissingType:\n  def missing(value: String): String")
    given Context = context
    val primary = stats.collectFirst { case definition: TypeDef => definition }.getOrElse(fail("missing trait"))
    val template = primary.rhs.asInstanceOf[Template]
    val method = template.body.collectFirst { case definition: DefDef => definition }.getOrElse(fail("missing method"))
    val missingMethod = cpy.DefDef(method)(method.name, method.paramss, TypeTree(), method.rhs)
    val missingTemplate =
      cpy.Template(template)(
        template.constr,
        template.parentsOrDerived,
        template.derived,
        template.self,
        List(missingMethod)
      )
    val missingPrimary = cpy.TypeDef(primary)(primary.name, missingTemplate)
    val missing = AnnotatedClassBodyView.decode(missingPrimary).fold(error => fail(error.message), identity)

    assertEquals(unsupportedKind(methodMap(missing)("missing").resultType), "inferred-or-missing-type")

    val parameter = method.termParamss.head.head
    val missingParameter = cpy.ValDef(parameter)(parameter.name, TypeTree(), parameter.rhs)
    val missingParameterMethod =
      cpy.DefDef(method)(method.name, method.paramss.map(_.map(_ => missingParameter)), method.tpt, method.rhs)
    val missingParameterTemplate =
      cpy.Template(template)(
        template.constr,
        template.parentsOrDerived,
        template.derived,
        template.self,
        List(missingParameterMethod)
      )
    val missingParameterPrimary = cpy.TypeDef(primary)(primary.name, missingParameterTemplate)
    val missingParameterView =
      AnnotatedClassBodyView.decode(missingParameterPrimary).fold(error => fail(error.message), identity)

    assertEquals(
      unsupportedKind(methodMap(missingParameterView)("missing").parameterClauses.head.parameters.head.parameterType),
      "inferred-or-missing-type"
    )
  }

  test("keeps a parser-recovery parameter identifier fail-closed") {
    val (stats, context) = parsedStats("trait Recovery:\n  def missing(value: String): String")
    given Context = context
    val primary = stats.collectFirst { case definition: TypeDef => definition }.getOrElse(fail("missing trait"))
    val template = primary.rhs.asInstanceOf[Template]
    val method = template.body.collectFirst { case definition: DefDef => definition }.getOrElse(fail("missing method"))
    val parameter = method.termParamss.head.head
    val recoveredParameter =
      cpy.ValDef(parameter)(parameter.name, Ident(typeName("<error>")), parameter.rhs)
    val recoveredMethod =
      cpy.DefDef(method)(method.name, method.paramss.map(_.map(_ => recoveredParameter)), method.tpt, method.rhs)
    val recoveredTemplate =
      cpy.Template(template)(
        template.constr,
        template.parentsOrDerived,
        template.derived,
        template.self,
        List(recoveredMethod)
      )
    val recoveredPrimary = cpy.TypeDef(primary)(primary.name, recoveredTemplate)

    val recovered = AnnotatedClassBodyView.decode(recoveredPrimary).fold(error => fail(error.message), identity)
    val shape = methodMap(recovered)("missing").parameterClauses.head.parameters.head.parameterType
    assertEquals(unsupportedKind(shape), "unqualified-reference")
  }

  test("keeps a hostile null-name parameter identifier fail-closed") {
    val (stats, context) = parsedStats("trait HostileName:\n  def hostile(value: String): String")
    given Context = context
    val primary = stats.collectFirst { case definition: TypeDef => definition }.getOrElse(fail("missing trait"))
    val template = primary.rhs.asInstanceOf[Template]
    val method = template.body.collectFirst { case definition: DefDef => definition }.getOrElse(fail("missing method"))
    val parameter = method.termParamss.head.head
    val hostileParameter =
      cpy.ValDef(parameter)(parameter.name, Ident(null.asInstanceOf[Name]), parameter.rhs)
    val hostileMethod =
      cpy.DefDef(method)(method.name, method.paramss.map(_.map(_ => hostileParameter)), method.tpt, method.rhs)
    val hostileTemplate =
      cpy.Template(template)(
        template.constr,
        template.parentsOrDerived,
        template.derived,
        template.self,
        List(hostileMethod)
      )
    val hostilePrimary = cpy.TypeDef(primary)(primary.name, hostileTemplate)

    val hostile = AnnotatedClassBodyView.decode(hostilePrimary).fold(error => fail(error.message), identity)
    val shape = methodMap(hostile)("hostile").parameterClauses.head.parameters.head.parameterType
    assertEquals(unsupportedKind(shape), "unqualified-reference")
  }

  test("retains concrete methods in the ordered inventory") {
    val methods = methodMap(body("trait Mixed[A]:\n  def abstractMethod: A\n  def concreteMethod: A = ???"))

    assertEquals(methods.keys.toList, List("abstractMethod", "concreteMethod"))
    assertEquals(methods("abstractMethod").status, DirectMethodStatus.Abstract)
    assertEquals(methods("concreteMethod").status, DirectMethodStatus.Concrete)
  }

  test("classifies abstract vals vars type members and nested definitions in source order") {
    val decoded = body(
      """trait Inventory[A]:
        |  val value: A
        |  var variable: A
        |  type Member
        |  object NestedObject
        |  trait NestedTrait
        |  class NestedClass
        |""".stripMargin
    )

    assertEquals(decoded.members.map(_.name), List("value", "variable", "Member", "NestedObject", "NestedTrait", "NestedClass"))
    assertEquals(
      decoded.members.map(_.kind),
      List(
        DirectMemberKind.Val,
        DirectMemberKind.Var,
        DirectMemberKind.Type,
        DirectMemberKind.NestedObject,
        DirectMemberKind.NestedTrait,
        DirectMemberKind.NestedClass
      )
    )
    assert(decoded.members.forall(_.method.isEmpty))
  }

  test("exposes annotations visibility and relevant unsupported method modifiers") {
    val methods = methodMap(
      body(
        """trait Modifiers[A]:
          |  @deprecated private def hidden: A
          |  protected def guarded: A
          |  final def concrete: A = ???
          |""".stripMargin
      )
    )

    assert(methods("hidden").modifiers.hasAnnotations)
    assertEquals(methods("hidden").modifiers.visibility, DirectVisibility.Private)
    assertEquals(methods("guarded").modifiers.visibility, DirectVisibility.Protected)
    assert(methods("concrete").modifiers.unsupportedFlags.contains("final"))
  }

  test("retains method parameter and type positions when source spans exist") {
    val combine = methodMap(body("trait Positioned[A]:\n  def combine(first: A, second: A): A"))("combine")

    assert(combine.pos.span.exists)
    assert(combine.resultTypePos.span.exists)
    assert(combine.typeParameters.forall(_.pos.span.exists))
    assert(combine.parameterClauses.head.pos.span.exists)
    assert(combine.parameterClauses.head.parameters.forall(_.pos.span.exists))
    assert(combine.parameterClauses.head.parameters.forall(_.typePos.span.exists))
  }

  test("returns controlled diagnostics for null wrong-kind and malformed raw structures") {
    val (stats, context) = parsedStats("object WrongKind\ntrait ContextOwner")
    given Context = context
    val wrongKind = stats.collectFirst { case module: ModuleDef => module }.getOrElse(fail("missing object"))
    given dotty.tools.dotc.util.SourceFile = stats.head.source
    val malformed = TypeDef(typeName("Malformed"), Ident(typeName("String")))

    val failures = List(
      AnnotatedClassBodyView.decode(null),
      AnnotatedClassBodyView.decode(wrongKind),
      AnnotatedClassBodyView.decode(malformed)
    )
    assert(failures.forall(_.isLeft))
    assert(failures.forall(_.left.toOption.exists(_.message.nonEmpty)))
  }

  test("ExpansionInput delegates to the single bounded body decoder") {
    val (stats, context) = parsedStats("trait Input[A]:\n  def empty: A")
    given Context = context
    val target = stats.collectFirst { case definition: TypeDef => definition }.getOrElse(fail("missing trait"))
    val input = ExpansionInput("instanceProbe", target, None, Set("Input"), None)

    assertEquals(input.annotatedClassBodyView.map(_.members.map(_.name)), Right(List("empty")))
  }

  private def body(code: String): AnnotatedClassBodyView =
    val (stats, context) = parsedStats(code)
    given Context = context
    val candidate = stats.collectFirst { case definition: TypeDef => definition }.getOrElse(fail(s"missing TypeDef in $stats"))
    AnnotatedClassBodyView.decode(candidate) match
      case Right(value) => value
      case Left(diagnostic) => fail(diagnostic.message)

  private def methodMap(view: AnnotatedClassBodyView): scala.collection.immutable.ListMap[String, DirectMethod] =
    scala.collection.immutable.ListMap.from(view.members.flatMap(member => member.method.map(method => member.name -> method)))

  private def enclosingName(shape: DirectTypeShape): String = shape match
    case DirectTypeShape.EnclosingTypeParameter(name, _) => name
    case other => fail(s"expected enclosing type parameter, found $other")

  private def namedType(shape: DirectTypeShape): String = shape match
    case DirectTypeShape.NamedType(name, _) => name
    case other => fail(s"expected simple named type, found $other")

  private def typePosition(shape: DirectTypeShape): dotty.tools.dotc.util.SrcPos = shape match
    case DirectTypeShape.EnclosingTypeParameter(_, pos) => pos
    case DirectTypeShape.Unsupported(_, _, pos) => pos
    case DirectTypeShape.NamedType(_, pos) => pos

  private def unsupportedKind(shape: DirectTypeShape): String = shape match
    case DirectTypeShape.Unsupported(kind, _, _) => kind
    case other => fail(s"expected unsupported type shape, found $other")

  private def parsedStats(code: String): (List[Tree], Context) =
    val unit = CompilationUnit("AnnotatedClassBodyViewSpec.scala", code)
    val context = ContextBase().initialCtx.fresh.setCompilationUnit(unit)
    val parsed = new Parsers.Parser(unit.source)(using context).parse()
    val stats = parsed match
      case PackageDef(_, values) => values
      case tree => List(tree)
    (stats, context)
