package macroparadise

import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.ast.Trees
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Contexts.{Context, ContextBase}
import dotty.tools.dotc.parsing.Parsers

class RawBoundTreeProbeSpec extends munit.FunSuite:
  test("records the raw enclosing-bound and direct type-member shapes used by the structural view") {
    val evidence = List(
      "canonical" ->
        """trait Nat
          |trait Add[N <: Nat, M <: Nat]:
          |  type Out <: Nat
          |""".stripMargin,
      "alias" ->
        """trait Nat
          |trait Alias[N <: Nat]:
          |  type Out = Nat
          |""".stripMargin,
      "meaningful-lower" ->
        """trait Nat
          |trait Lowered[N <: Nat]:
          |  type Out >: Nat <: Any
          |""".stripMargin,
      "polymorphic" ->
        """trait Nat
          |trait Poly[N <: Nat]:
          |  type Out[X] <: Nat
          |""".stripMargin,
      "modifier-bearing" ->
        """trait Nat
          |trait Modified[N <: Nat]:
          |  protected type Out <: Nat
          |""".stripMargin,
      "unsupported-applied" ->
        """trait Nat
          |trait Applied[N <: List[N]]:
          |  type Out <: List[N]
          |""".stripMargin
    ).map { case (label, code) => render(label, code) }

    evidence.foreach(println)

    val canonical = evidence.head
    assert(canonical.contains("type-parameter N rhs=TypeBoundsTree"))
    assert(canonical.contains("type-parameter M rhs=TypeBoundsTree"))
    assert(canonical.contains("type-member Out rhs=TypeBoundsTree"))
    assert(canonical.contains("lo=EmptyTree(empty=true"))
    assert(canonical.contains("hi=Ident(name=Nat"))
    assert(evidence(1).contains("type-member Out rhs=Ident(name=Nat"))
    assert(evidence(2).contains("lo=Ident(name=Nat"))
    assert(evidence(3).contains("type-member Out rhs=LambdaTypeTree"))
    assert(evidence(4).contains("protected=true"))
    assert(evidence(5).contains("hi=AppliedTypeTree"))
  }

  private def render(label: String, code: String): String =
    val (stats, context) = parsedStats(code)
    given Context = context
    val target = stats.collect { case definition: TypeDef if definition.name.toString != "Nat" => definition }.last
    val template = target.rhs.asInstanceOf[Template]
    val typeParameters = template.constr.leadingTypeParams.map(renderTypeParameter)
    val typeMembers = template.body.collect { case definition: TypeDef if !definition.isClassDef => renderTypeMember(definition) }
    (s"PROBE[$label] target=${target.name}" :: typeParameters ::: typeMembers).mkString("\n  ")

  private def renderTypeParameter(parameter: TypeDef)(using Context): String =
    s"type-parameter ${parameter.name} rhs=${renderTree(parameter.rhs)} mods=${renderModifiers(parameter)}"

  private def renderTypeMember(member: TypeDef)(using Context): String =
    s"type-member ${member.name} rhs=${renderTree(member.rhs)} mods=${renderModifiers(member)}"

  private def renderTree(tree: Tree)(using Context): String =
    tree match
      case bounds: TypeBoundsTree =>
        s"TypeBoundsTree(lo=${renderTree(bounds.lo)},hi=${renderTree(bounds.hi)},alias=${renderTree(bounds.alias)},pos=${renderPos(bounds)})"
      case identifier: Ident =>
        s"Ident(name=${Option(identifier.name).fold("<null>")(_.toString)},empty=${identifier.isEmpty},pos=${renderPos(identifier)})"
      case other =>
        s"${other.getClass.getSimpleName}(empty=${other.isEmpty},pos=${renderPos(other)},raw=${safeRaw(other)})"

  private def renderModifiers(definition: TypeDef)(using Context): String =
    val mods = Trees.mods(definition)
    s"private=${mods.is(dotty.tools.dotc.core.Flags.Private)},protected=${mods.is(dotty.tools.dotc.core.Flags.Protected)},annotations=${Option(mods.annotations).fold(0)(_.size)},raw=$mods"

  private def renderPos(tree: Tree)(using Context): String =
    val pos = tree.sourcePos
    if pos.span.exists then s"exists=true,start=${pos.start},end=${pos.end}"
    else "exists=false"

  private def safeRaw(tree: Tree): String =
    try tree.toString.replace('\n', ' ')
    catch case _: Throwable => "<unprintable>"

  private def parsedStats(code: String): (List[Tree], Context) =
    val unit = CompilationUnit("RawBoundTreeProbeSpec.scala", code)
    val context = ContextBase().initialCtx.fresh.setCompilationUnit(unit)
    val parsed = new Parsers.Parser(unit.source)(using context).parse()
    val stats = parsed match
      case PackageDef(_, values) => values
      case tree => List(tree)
    (stats, context)
