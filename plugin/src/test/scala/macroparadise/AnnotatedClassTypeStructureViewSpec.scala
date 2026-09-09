package macroparadise

import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Contexts.{Context, ContextBase}
import dotty.tools.dotc.core.Names.{TypeName, typeName}
import dotty.tools.dotc.parsing.Parsers
import paradise3.api.{ExpansionContainerContext, ExpansionInput, ExpansionTarget, ExpansionTargetBodyView, ExpansionTargetKind, ExpansionTargetTypeStructureView, ExpansionTargetView, PluginInvocationMinting}
import paradise3.api.ExpansionTargetBodyView.DirectTypeShape
import paradise3.api.ExpansionTargetTypeStructureView.*

class ExpansionTargetTypeStructureViewSpec extends munit.FunSuite:
  test("normalizes canonical enclosing and direct abstract type-member bounds in source order") {
    val decoded = structure(
      """trait Nat
        |trait Add[N <: Nat, M <: Nat]:
        |  type Out <: Nat
        |""".stripMargin
    )

    assertEquals(decoded.typeParameters.map(_.name), List("N", "M"))
    assertEquals(decoded.typeParameters.map(_.variance), List.fill(2)(ExpansionTargetView.Variance.Invariant))
    assert(decoded.typeParameters.forall(_.lowerBound == Bound.Absent))
    assertEquals(decoded.typeParameters.map(parameter => namedBound(parameter.upperBound)), List("Nat", "Nat"))

    val out = only(decoded.directTypeMembers)
    assertEquals(out.name, "Out")
    assertEquals(out.bodyIndex, 0)
    assertEquals(out.kind, DirectTypeMemberKind.AbstractBounds)
    assertEquals(out.typeParameters, Nil)
    assertEquals(out.lowerBound, Bound.Absent)
    assertEquals(namedBound(out.upperBound), "Nat")
    assertEquals(out.aliasTarget, None)
    assertEquals(out.modifiers.visibility, ExpansionTargetBodyView.DirectVisibility.Public)
    assertEquals(out.modifiers.annotationCount, 0)
    assertEquals(out.modifiers.unsupportedFlags, Nil)
    assert(out.pos.span.exists)
    assert(typeShape(out.upperBound).exists(shapePosition(_).span.exists))
  }

  test("keeps aliases meaningful lower bounds polymorphism modifiers and unsupported bounds distinct") {
    val decoded = List(
      only(structure("trait Nat\ntrait Alias[N <: Nat]:\n  type Out = Nat").directTypeMembers),
      only(structure("trait Nat\ntrait Lowered[N <: Nat]:\n  type Out >: Nat <: Any").directTypeMembers),
      only(structure("trait Nat\ntrait Poly[N <: Nat]:\n  type Out[X] <: Nat").directTypeMembers),
      only(structure("trait Nat\ntrait Modified[N <: Nat]:\n  protected type Out <: Nat").directTypeMembers),
      only(structure("trait Nat\ntrait Applied[N <: Nat]:\n  type Out <: List[N]").directTypeMembers)
    )

    val alias :: lowered :: poly :: modified :: applied :: Nil = decoded: @unchecked
    assertEquals(alias.kind, DirectTypeMemberKind.Alias)
    assertEquals(alias.aliasTarget.map(namedType), Some("Nat"))
    assertEquals(alias.lowerBound, Bound.Absent)
    assertEquals(alias.upperBound, Bound.Absent)

    assertEquals(lowered.kind, DirectTypeMemberKind.AbstractBounds)
    assertEquals(namedBound(lowered.lowerBound), "Nat")
    assertEquals(namedBound(lowered.upperBound), "Any")

    assertEquals(poly.kind, DirectTypeMemberKind.AbstractBounds)
    assertEquals(poly.typeParameters.map(_.name), List("X"))
    assertEquals(namedBound(poly.upperBound), "Nat")

    assertEquals(modified.modifiers.visibility, ExpansionTargetBodyView.DirectVisibility.Protected)
    assert(modified.modifiers.unsupportedFlags.contains("protected"))

    assertEquals(applied.kind, DirectTypeMemberKind.AbstractBounds)
    assertEquals(unsupportedKind(typeShape(applied.upperBound).get), "applied-type")
  }

  test("distinguishes plain and infix aliases without changing normalized type-member structure") {
    val plain = only(structure("trait Plain[A]:\n  type Item = A").directTypeMembers)
    val infix = only(structure("trait Infix[A]:\n  infix type Item = A").directTypeMembers)

    assertEquals(plain.modifiers.unsupportedFlags, Nil)
    assertEquals(infix.modifiers.unsupportedFlags, List("infix"))
    assertEquals(infix.name, plain.name)
    assertEquals(infix.bodyIndex, plain.bodyIndex)
    assertEquals(infix.kind, plain.kind)
    assertEquals(infix.typeParameters, plain.typeParameters)
    assertEquals(infix.lowerBound, plain.lowerBound)
    assertEquals(infix.upperBound, plain.upperBound)
    assertEquals(infix.aliasTarget.map(enclosingTypeParameter), plain.aliasTarget.map(enclosingTypeParameter))
    assertEquals(infix.modifiers.visibility, plain.modifiers.visibility)
    assertEquals(infix.modifiers.hasAnnotations, plain.modifiers.hasAnnotations)
    assertEquals(infix.modifiers.annotationCount, plain.modifiers.annotationCount)
    assert(infix.pos.span.exists)
    assert(infix.aliasTarget.exists(shapePosition(_).span.exists))
  }

  test("retains established type-member modifier and annotation evidence beside infix") {
    val members = structure(
      """trait ModifierMatrix[A]:
        |  @deprecated private type PrivateItem = A
        |  protected type ProtectedItem = A
        |  final type FinalItem = A
        |  override type OverrideItem = A
        |  opaque type OpaqueItem = A
        |  infix type InfixItem = A
        |""".stripMargin
    ).directTypeMembers.map(member => member.name -> member).toMap

    assertEquals(members("PrivateItem").modifiers.visibility, ExpansionTargetBodyView.DirectVisibility.Private)
    assertEquals(members("PrivateItem").modifiers.annotationCount, 1)
    assert(members("PrivateItem").modifiers.hasAnnotations)
    assertEquals(members("PrivateItem").modifiers.unsupportedFlags, List("private"))
    assertEquals(members("ProtectedItem").modifiers.unsupportedFlags, List("protected"))
    assertEquals(members("FinalItem").modifiers.unsupportedFlags, List("final"))
    assertEquals(members("OverrideItem").modifiers.unsupportedFlags, List("override"))
    assertEquals(members("OpaqueItem").modifiers.unsupportedFlags, List("opaque"))
    assertEquals(members("InfixItem").modifiers.unsupportedFlags, List("infix"))
  }

  test("preserves type-member body indices while the existing inventory exposes extra direct members") {
    val code =
      """trait Nat
        |trait Mixed[N <: Nat]:
        |  def extra: N
        |  type Out <: Nat
        |  val another: N
        |""".stripMargin
    val decoded = structure(code)
    val body = bodyView(code)

    assertEquals(decoded.directTypeMembers.map(member => member.name -> member.bodyIndex), List("Out" -> 1))
    assertEquals(
      body.members.map(_.kind),
      List(
        ExpansionTargetBodyView.DirectMemberKind.Method,
        ExpansionTargetBodyView.DirectMemberKind.Type,
        ExpansionTargetBodyView.DirectMemberKind.Val
      )
    )
  }

  test("fails hostile names and null bound children closed without leaking an exception") {
    val (stats, context) = parsedStats("trait Nat\ntrait Hostile[N <: Nat]:\n  type Out <: Nat")
    given Context = context
    val primary = only(stats.collect { case definition: TypeDef if definition.name.toString == "Hostile" => definition })
    val template = primary.rhs.asInstanceOf[Template]
    val original = template.body.collectFirst { case definition: TypeDef => definition }.getOrElse(fail("missing type member"))
    given dotty.tools.dotc.util.SourceFile = original.source
    val hostileBounds = TypeBoundsTree(null.asInstanceOf[Tree], Ident(typeName("Nat")))
    val hostileMember = cpy.TypeDef(original)(null.asInstanceOf[TypeName], hostileBounds)
    val hostileTemplate = cpy.Template(template)(
      template.constr,
      template.parentsOrDerived,
      template.derived,
      template.self,
      List(hostileMember)
    )
    val hostilePrimary = cpy.TypeDef(primary)(primary.name, hostileTemplate)

    val decoded = ExpansionTargetTypeStructureView.decode(hostilePrimary).fold(error => fail(error.message), identity)
    val member = only(decoded.directTypeMembers)
    assertEquals(member.kind, DirectTypeMemberKind.Unsupported)
    assertEquals(member.name, "<unknown>")
    assertEquals(unsupportedKind(typeShape(member.lowerBound).get), "null-type")
  }

  test("returns controlled diagnostics for null wrong-kind and malformed raw structures") {
    val (stats, context) = parsedStats("object WrongKind\ntrait ContextOwner")
    given Context = context
    val wrongKind = stats.collectFirst { case module: ModuleDef => module }.getOrElse(fail("missing object"))
    given dotty.tools.dotc.util.SourceFile = stats.head.source
    val malformed = TypeDef(typeName("Malformed"), Ident(typeName("String")))

    val failures = List(
      ExpansionTargetTypeStructureView.decode(null),
      ExpansionTargetTypeStructureView.decode(wrongKind),
      ExpansionTargetTypeStructureView.decode(malformed)
    )
    assert(failures.forall(_.isLeft))
    assert(failures.forall(_.left.toOption.exists(_.message.nonEmpty)))
  }

  test("ExpansionInput delegates to the single bounded type-structure decoder") {
    val (stats, context) = parsedStats("trait Nat\ntrait Input[N <: Nat]:\n  type Out <: Nat")
    given Context = context
    val target = only(stats.collect { case definition: TypeDef if definition.name.toString == "Input" => definition })
    val input = PluginInvocationMinting.input(
      ExpansionTarget.Trait(target),
      None,
      PluginInvocationMinting.container(Set("Input")),
      target
    )

    assertEquals(input.targetTypeStructureView.map(_.directTypeMembers.map(_.name)), Right(List("Out")))
  }

  private def structure(code: String): ExpansionTargetTypeStructureView =
    val (stats, context) = parsedStats(code)
    given Context = context
    val candidate = stats.collect { case definition: TypeDef if definition.name.toString != "Nat" => definition }.last
    ExpansionTargetTypeStructureView.decode(candidate).fold(error => fail(error.message), identity)

  private def bodyView(code: String): ExpansionTargetBodyView =
    val (stats, context) = parsedStats(code)
    given Context = context
    val candidate = stats.collect { case definition: TypeDef if definition.name.toString != "Nat" => definition }.last
    ExpansionTargetBodyView.decode(candidate).fold(error => fail(error.message), identity)

  private def namedBound(bound: Bound): String = namedType(typeShape(bound).getOrElse(fail("expected present bound")))

  private def typeShape(bound: Bound): Option[DirectTypeShape] = bound match
    case Bound.Absent => None
    case Bound.Present(shape) => Some(shape)

  private def namedType(shape: DirectTypeShape): String = shape match
    case DirectTypeShape.NamedType(name, _) => name
    case other => fail(s"expected named type, found $other")

  private def enclosingTypeParameter(shape: DirectTypeShape): String = shape match
    case DirectTypeShape.EnclosingTypeParameter(name, _) => name
    case other => fail(s"expected enclosing type parameter, found $other")

  private def unsupportedKind(shape: DirectTypeShape): String = shape match
    case DirectTypeShape.Unsupported(kind, _, _) => kind
    case other => fail(s"expected unsupported type, found $other")

  private def shapePosition(shape: DirectTypeShape): dotty.tools.dotc.util.SrcPos = shape match
    case DirectTypeShape.EnclosingTypeParameter(_, pos) => pos
    case DirectTypeShape.Unsupported(_, _, pos) => pos
    case DirectTypeShape.NamedType(_, pos) => pos

  private def only[A](values: List[A]): A =
    assertEquals(values.size, 1)
    values.head

  private def parsedStats(code: String): (List[Tree], Context) =
    val unit = CompilationUnit("ExpansionTargetTypeStructureViewSpec.scala", code)
    val context = ContextBase().initialCtx.fresh.setCompilationUnit(unit)
    val parsed = new Parsers.Parser(unit.source)(using context).parse()
    val stats = parsed match
      case PackageDef(_, values) => values
      case tree => List(tree)
    (stats, context)
