package macroparadise

import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.ast.Trees
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Contexts.{Context, ContextBase}
import dotty.tools.dotc.core.Flags.{
  Abstract,
  Case,
  Enum,
  Final,
  Private,
  Protected,
  Sealed,
  Trait
}
import dotty.tools.dotc.parsing.Parsers
import paradise3.api.AnnotatedClassView

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

class AnnotatedClassAdmissionSpec extends munit.FunSuite:
  test("plugin admission delegates normalization to the shared AnnotatedClassView decoder") {
    val source =
      Files.readString(
        Path.of("plugin/src/main/scala/macroparadise/AnnotatedClassAdmission.scala"),
        StandardCharsets.UTF_8
      )

    assert(source.contains("AnnotatedClassView.decode(typeDef)"))
    assert(!source.contains("Trees.mods"))
    assert(!source.contains("ParamAccessor"))
    assert(!source.contains("termParamss.map"))
  }

  test("pinned parser distinguishes top-level class, trait, object, enum, and type alias") {
    val (stats, context) =
      parsedStats(
        """class Ordinary
          |trait Contract
          |object Singleton
          |enum Choice:
          |  case One
          |type Alias = String
          |""".stripMargin
      )
    given Context = context

    val ordinary = typeDefNamed(stats, "Ordinary")
    assert(ordinary.isClassDef)
    assert(!Trees.mods(ordinary).is(Trait))
    assert(!Trees.mods(ordinary).is(Enum))

    val contract = typeDefNamed(stats, "Contract")
    assert(contract.isClassDef)
    assert(Trees.mods(contract).is(Trait))

    assert(stats.exists:
      case module: ModuleDef => module.name.toString == "Singleton"
      case _ => false
    )

    val choice = typeDefNamed(stats, "Choice")
    assert(choice.isClassDef)
    assert(Trees.mods(choice).is(Enum))

    val alias = typeDefNamed(stats, "Alias")
    assert(!alias.isClassDef)
  }

  test("pinned parser records class-family modifiers structurally") {
    val ordinary = shape("class Ordinary")
    val finalShape = shape("final class FinalUser")
    val sealedShape = shape("sealed class SealedUser")
    val abstractShape = shape("abstract class AbstractUser")
    val caseShape = shape("case class CaseUser(name: String)")

    assertEquals(AnnotatedClassAdmission.modifierSummary(ordinary), Nil)
    assert(finalShape.modifiers.isFinal)
    assert(sealedShape.modifiers.isSealed)
    assert(abstractShape.modifiers.isAbstract)
    assert(caseShape.modifiers.isCase)
  }

  test("pinned parser exposes nested, local, private, and protected class boundaries") {
    val (stats, context) =
      parsedStats(
        """object Outer:
          |  private class PrivateNested
          |  protected class ProtectedNested
          |def make =
          |  class Local
          |  new Local
          |""".stripMargin
      )
    given Context = context

    val outer =
      stats.collectFirst:
        case module: ModuleDef if module.name.toString == "Outer" => module
      .getOrElse(fail(s"missing Outer in $stats"))
    val nested = outer.impl.body.collect:
      case typeDef: TypeDef if typeDef.isClassDef => typeDef
    assertEquals(nested.map(_.name.toString), List("PrivateNested", "ProtectedNested"))
    assert(Trees.mods(nested.head).is(Private))
    assert(Trees.mods(nested(1)).is(Protected))
    assert(nested.forall(_.sourcePos.span.exists))

    val make =
      stats.collectFirst:
        case method: DefDef if method.name.toString == "make" => method
      .getOrElse(fail(s"missing make in $stats"))
    val localClasses =
      make.rhs match
        case Block(blockStats, _) =>
          blockStats.collect:
            case typeDef: TypeDef if typeDef.isClassDef => typeDef
        case other =>
          fail(s"expected local-class block, found $other")
    assertEquals(localClasses.map(_.name.toString), List("Local"))
    assert(localClasses.head.sourcePos.span.exists)
  }

  test("pinned parser exposes generic parameters and their source positions") {
    val generic = shape("class Generic[A]")
    val bounded = shape("class Bounded[A <: Product]")

    assertEquals(generic.typeParameters.map(_.name), List("A"))
    assertEquals(bounded.typeParameters.map(_.name), List("A"))
    assert(generic.typeParameters.head.pos.span.exists)
    assert(bounded.typeParameters.head.pos.span.exists)
    assert(
      AnnotatedClassAdmission
        .commonRejection(generic, "@debug")
        .exists(_.message.contains("unsupported generic class shape"))
    )
  }

  test("pinned parser distinguishes omitted and explicit empty constructor clauses") {
    val omitted = shape("class Omitted")
    val explicit = shape("class Explicit()")

    assertEquals(omitted.constructorClauses, Nil)
    assertEquals(explicit.constructorClauses.map(_.parameters.size), List(0))
    assert(omitted.classPos.span.exists)
    assert(explicit.constructorPos.span.exists)
  }

  test("pinned parser preserves bare, val, and var constructor parameter modifiers") {
    val bare = onlyParameter(shape("class Bare(name: String)"))
    val immutable = onlyParameter(shape("class Immutable(val name: String)"))
    val mutable = onlyParameter(shape("class Mutable(var name: String)"))

    assert(
      !bare.isVal,
      s"bare=$bare val=$immutable var=$mutable"
    )
    assert(!bare.isVar)
    assert(immutable.isVal)
    assert(!immutable.isVar)
    assert(mutable.isVar)
    assert(bare.pos.span.exists)
    assert(bare.typePos.span.exists)
  }

  test("pinned parser preserves constructor names, raw types, clauses, and defaults") {
    val wrongName = onlyParameter(shape("class Wrong(other: String)"))
    val wrongType = onlyParameter(shape("class WrongType(name: Int)"))
    val extra = shape("class Extra(name: String, age: Int)")
    val clauses = shape("class Clauses(name: String)(age: Int)")
    val contextual = shape("class Contextual(name: String)(using ord: Ordering[String])")
    val defaulted = onlyParameter(shape("class Defaulted(name: String = \"x\")"))

    assertEquals(wrongName.name, "other")
    assertEquals(rawTypeName(wrongType.rawType), "Int")
    assertEquals(extra.constructorClauses.map(_.parameters.map(_.name)), List(List("name", "age")))
    assertEquals(clauses.constructorClauses.map(_.parameters.map(_.name)), List(List("name"), List("age")))
    assert(contextual.constructorClauses.last.isContextual)
    assert(defaulted.hasDefault)
  }

  test("pinned parser records a private primary constructor") {
    val privateConstructor = shape("class Private private (name: String)")
    assert(privateConstructor.modifiers.constructorIsPrivate)
    assert(privateConstructor.constructorPos.span.exists)
  }

  test("@gen syntactic contract admits exactly bare or val name String") {
    val bare = shape("class Bare(name: String)")
    val immutable = shape("final class Immutable(val name: String)")
    val sealedShape = shape("sealed class SealedUser(name: String)")

    assertEquals(AnnotatedClassAdmission.genRejection(bare), None)
    assertEquals(AnnotatedClassAdmission.genRejection(immutable), None)
    assertEquals(AnnotatedClassAdmission.genRejection(sealedShape), None)
  }

  test("@gen syntactic contract rejects unsupported constructor and class shapes") {
    val rejected =
      List(
        shape("class Missing"),
        shape("class Empty()"),
        shape("class Wrong(other: String)"),
        shape("class WrongType(name: Int)"),
        shape("class Qualified(name: java.lang.String)"),
        shape("class Mutable(var name: String)"),
        shape("class Extra(name: String, age: Int)"),
        shape("class Clauses(name: String)(age: Int)"),
        shape("class Contextual(name: String)(using ord: Ordering[String])"),
        shape("class Defaulted(name: String = \"x\")"),
        shape("abstract class AbstractUser(name: String)"),
        shape("class Private private (name: String)")
      )

    rejected.foreach: candidate =>
      assert(
        AnnotatedClassAdmission.genRejection(candidate).nonEmpty,
        s"expected @gen rejection for ${candidate.className}"
      )
  }

  test("case classes are rejected by the common envelope before handler admission") {
    val candidate = shape("case class CaseUser(name: String)")
    val rejection =
      AnnotatedClassAdmission.commonRejection(
        candidate,
        "handled annotations @gen, @externalDebug"
      )
    assert(rejection.exists(_.message.contains("case class CaseUser")))
    assert(rejection.exists(_.pos.span.exists))
  }

  test("restricted generic trait profile admits only the requested raw Show shape") {
    val admitted = shape("trait Show[A]")
    assertEquals(
      AnnotatedClassAdmission.restrictedGenericTraitApplyRejection(
        admitted,
        "@externalRestrictedTraitApply"
      ),
      None
    )

    val rejected = List(
      shape("class NotATrait[A]"),
      shape("sealed trait SealedShow[A]"),
      shape("trait MissingParameter"),
      shape("trait TwoParameters[A, B]"),
      shape("trait CovariantShow[+A]"),
      shape("trait ContravariantShow[-A]"),
      shape("trait BoundedShow[A <: Product]"),
      shape("trait ContextualShow[A: Ordering]"),
      shape("trait ConstructorShow[A](val value: A)")
    )
    rejected.foreach: candidate =>
      assert(
        AnnotatedClassAdmission
          .restrictedGenericTraitApplyRejection(candidate, "@externalRestrictedTraitApply")
          .nonEmpty,
        s"expected restricted trait rejection for ${candidate.className}"
      )
  }

  test("admission diagnostics select the most precise defensible raw positions") {
    val wrongName = shape("class WrongName(other: String)")
    val wrongNameParameter = onlyParameter(wrongName)
    assertEquals(
      AnnotatedClassAdmission.genRejection(wrongName).map(_.pos.span),
      Some(wrongNameParameter.pos.span)
    )

    val wrongType = shape("class WrongType(name: Int)")
    val wrongTypeParameter = onlyParameter(wrongType)
    assertEquals(
      AnnotatedClassAdmission.genRejection(wrongType).map(_.pos.span),
      Some(wrongTypeParameter.typePos.span)
    )

    val multiple = shape("class Multiple(name: String)(age: Int)")
    assertEquals(
      AnnotatedClassAdmission.genRejection(multiple).map(_.pos.span),
      Some(multiple.constructorClauses(1).pos.span)
    )

    val generic = shape("class Generic[A]")
    assertEquals(
      AnnotatedClassAdmission.commonRejection(generic, "@debug").map(_.pos.span),
      Some(generic.typeParameters.head.pos.span)
    )
  }

  private def onlyParameter(
      shape: AnnotatedClassView
  ): AnnotatedClassView.ConstructorParameter =
    shape.constructorClauses match
      case AnnotatedClassView.ConstructorClause(parameter :: Nil, _, _) :: Nil =>
        parameter
      case other =>
        fail(s"expected one constructor parameter, found $other")

  private def rawTypeName(tree: Tree): String =
    tree match
      case Ident(name) => name.toString
      case Select(_, name) => name.toString
      case other => other.getClass.getSimpleName

  private def shape(code: String): AnnotatedClassView =
    val (stats, context) = parsedStats(code)
    given Context = context
    val typeDef =
      stats.collectFirst:
        case candidate: TypeDef if candidate.isClassDef => candidate
      .getOrElse(fail(s"no class in parsed tree: $stats"))
    AnnotatedClassAdmission.decode(typeDef) match
      case Right(value) => value
      case Left(rejection) => fail(rejection.message)

  private def typeDefNamed(stats: List[Tree], name: String): TypeDef =
    stats.collectFirst:
      case typeDef: TypeDef if typeDef.name.toString == name => typeDef
    .getOrElse(fail(s"missing TypeDef $name in $stats"))

  private def parsedStats(code: String): (List[Tree], Context) =
    val unit = CompilationUnit("AnnotatedClassShapeSpec.scala", code)
    val context = ContextBase().initialCtx.fresh.setCompilationUnit(unit)
    val parsed = new Parsers.Parser(unit.source)(using context).parse()
    val stats =
      parsed match
        case PackageDef(_, packageStats) => packageStats
        case tree => List(tree)
    (stats, context)
