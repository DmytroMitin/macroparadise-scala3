package macroparadise

import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.ast.Trees
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Contexts.{Context, ContextBase}
import dotty.tools.dotc.core.Flags.{Abstract, Case, Enum, Final, Sealed, Trait}
import dotty.tools.dotc.core.Names.typeName
import dotty.tools.dotc.parsing.Parsers
import paradise3.api.{AnnotatedClassView, ExpansionInput}

class AnnotatedClassViewSpec extends munit.FunSuite:
  test("decodes ordinary empty classes and explicit empty-clause position fallback") {
    val omitted = view("class Omitted")
    val explicit = view("class Explicit()")

    assertEquals(omitted.className, "Omitted")
    assertEquals(omitted.typeParameters, Nil)
    assertEquals(omitted.constructorClauses, Nil)
    assertEquals(explicit.constructorClauses.map(_.parameters.size), List(0))
    assertEquals(explicit.constructorClauses.head.pos.span, explicit.constructorPos.span)
    assert(omitted.classPos.span.exists)
    assert(explicit.constructorPos.span.exists)
  }

  test("distinguishes bare val var defaulted and source-ordered parameters") {
    val bare = onlyParameter(view("class Bare(name: String)"))
    val immutable = onlyParameter(view("class Immutable(val name: String)"))
    val mutable = onlyParameter(view("class Mutable(var name: String)"))
    val defaulted = onlyParameter(view("class Defaulted(name: String = \"x\")"))
    val ordered = view("class Ordered(first: String, second: Int, third: Boolean)")

    assert(!bare.isVal && !bare.isVar)
    assert(immutable.isVal && !immutable.isVar)
    assert(mutable.isVar)
    assert(defaulted.hasDefault)
    assertEquals(ordered.constructorClauses.head.parameters.map(_.name), List("first", "second", "third"))
    assert(ordered.constructorClauses.head.parameters.forall(_.pos.span.exists))
    assert(ordered.constructorClauses.head.parameters.forall(_.typePos.span.exists))
  }

  test("preserves multiple constructor clauses and contextual or implicit evidence") {
    val multiple = view("class Multiple(first: String)(second: Int)")
    val contextual = view("class Contextual(first: String)(using ord: Ordering[String])")
    val implicitClause = view("class Legacy(first: String)(implicit evidence: Ordering[String])")

    assertEquals(multiple.constructorClauses.map(_.parameters.map(_.name)), List(List("first"), List("second")))
    assert(!multiple.constructorClauses.exists(_.isContextual))
    assert(contextual.constructorClauses.last.isContextual)
    assert(contextual.constructorClauses.last.parameters.forall(_.isContextual))
    assert(implicitClause.constructorClauses.last.isContextual)
    assert(implicitClause.constructorClauses.last.parameters.forall(_.isContextual))
  }

  test("preserves raw type parameters in source order") {
    val decoded = view("class Generic[First, Second <: Product]")
    assertEquals(decoded.typeParameters.map(_.name), List("First", "Second"))
    assert(decoded.typeParameters.forall(_.pos.span.exists))
  }

  test("retains simple qualified and applied raw type trees without semantic interpretation") {
    val simple = onlyParameter(view("class Simple(value: String)"))
    val qualified = onlyParameter(view("class Qualified(value: java.lang.String)"))
    val applied = onlyParameter(view("class Applied(value: List[String])"))

    assert(simple.rawType.isInstanceOf[Ident])
    assert(qualified.rawType.isInstanceOf[Select])
    assert(applied.rawType.isInstanceOf[AppliedTypeTree])
  }

  test("records case abstract final sealed and private-constructor modifiers syntactically") {
    val caseView = view("case class CaseUser(name: String)")
    val abstractView = view("abstract class AbstractUser")
    val finalView = view("final class FinalUser")
    val sealedView = view("sealed class SealedUser")
    val privateView = view("class PrivateUser private (name: String)")

    assert(caseView.modifiers.isCase)
    assert(abstractView.modifiers.isAbstract)
    assert(finalView.modifiers.isFinal)
    assert(sealedView.modifiers.isSealed)
    assert(privateView.modifiers.constructorIsPrivate)
    assert(privateView.constructorPos.span.exists)
  }

  test("decodes traits while returning controlled failures for object enum alias and malformed TypeDef targets") {
    val (stats, context) =
      parsedStats(
        """object Singleton
          |trait Contract
          |enum Choice:
          |  case One
          |type Alias = String
          |""".stripMargin
      )
    given Context = context

    val singleton = stats.collectFirst { case value: ModuleDef => value }.getOrElse(fail("missing object"))
    val contract = typeDefNamed(stats, "Contract")
    val choice = typeDefNamed(stats, "Choice")
    val alias = typeDefNamed(stats, "Alias")
    given dotty.tools.dotc.util.SourceFile = contract.source
    val malformed = TypeDef(typeName("Malformed"), Ident(typeName("String")))

    val decodedTrait = AnnotatedClassView.decode(contract)
    assertEquals(decodedTrait.map(_.definitionKind), Right(AnnotatedClassView.DefinitionKind.Trait))

    val failures = List(singleton, choice, alias, malformed).map(AnnotatedClassView.decode)
    assert(failures.forall(_.isLeft))
    assert(failures.forall(_.left.toOption.exists(_.message.nonEmpty)))
    assert(Trees.mods(contract).is(Trait))
    assert(Trees.mods(choice).is(Enum))
  }

  test("records restricted trait type-parameter variance and raw bound categories") {
    val invariant = view("trait Invariant[A]")
    val covariant = view("trait Covariant[+A]")
    val contravariant = view("trait Contravariant[-A]")
    val bounded = view("trait Bounded[A <: Product]")
    val contextual = view("trait Contextual[A: Ordering]")

    assertEquals(invariant.definitionKind, AnnotatedClassView.DefinitionKind.Trait)
    assertEquals(invariant.typeParameters.head.variance, AnnotatedClassView.Variance.Invariant)
    assert(invariant.typeParameters.head.isOrdinaryUnbounded)
    assertEquals(covariant.typeParameters.head.variance, AnnotatedClassView.Variance.Covariant)
    assertEquals(contravariant.typeParameters.head.variance, AnnotatedClassView.Variance.Contravariant)
    assert(!bounded.typeParameters.head.isOrdinaryUnbounded)
    assert(contextual.typeParameters.head.hasContextBounds)
    assert(!contextual.typeParameters.head.isOrdinaryUnbounded)
  }

  test("legacy structured-view and type-parameter apply and copy shapes remain source-callable") {
    val decoded = view("class Compatibility[A]")
    val parameter = AnnotatedClassView.TypeParameter("A", decoded.typeParameters.head.pos)
    val copiedParameter = parameter.copy("B", parameter.pos)
    val reconstructed = AnnotatedClassView(
      decoded.className,
      List(copiedParameter),
      decoded.constructorClauses,
      decoded.modifiers,
      decoded.classPos,
      decoded.constructorPos
    )
    val copied = reconstructed.copy(
      reconstructed.className,
      reconstructed.typeParameters,
      reconstructed.constructorClauses,
      reconstructed.modifiers,
      reconstructed.classPos,
      reconstructed.constructorPos
    )

    assertEquals(copied.definitionKind, AnnotatedClassView.DefinitionKind.Class)
    assertEquals(copied.typeParameters.map(_.name), List("B"))
  }

  test("null direct API construction produces a controlled view diagnostic") {
    val (_, context) = parsedStats("class ContextOwner")
    given Context = context
    val hostile = ExpansionInput("externalDebug", null.asInstanceOf[TypeDef], None, Set.empty)

    val result = hostile.annotatedClassView
    assert(result.isLeft)
    assert(result.left.toOption.exists(_.message.contains("null annotated class")))
  }

  test("view decoding is read-only across raw input companion names annotations and positions") {
    val (stats, context) = parsedStats("@deprecated class Observed(value: List[String])\nobject Observed")
    given Context = context
    val annotated = typeDefNamed(stats, "Observed")
    val companion = stats.collectFirst { case value: ModuleDef => value }.getOrElse(fail("missing companion"))
    val rawTemplate = annotated.rhs
    val rawAnnotations = Trees.mods(annotated).annotations
    val rawClassSpan = annotated.sourcePos.span
    val rawCompanionSpan = companion.sourcePos.span
    val currentAnnotation = rawAnnotations.headOption
    val names = Set("Observed", "Neighbor")
    val input = ExpansionInput("externalDebug", annotated, Some(companion), names, currentAnnotation)

    val decoded = input.annotatedClassView
    assert(decoded.isRight)
    assert(input.annotatedClass eq annotated)
    assert(input.annotatedClass.rhs eq rawTemplate)
    assertEquals(Trees.mods(input.annotatedClass).annotations, rawAnnotations)
    assertEquals(input.existingCompanion, Some(companion))
    assertEquals(input.topLevelNames, names)
    assertEquals(input.currentAnnotation, currentAnnotation)
    assertEquals(input.annotatedClass.sourcePos.span, rawClassSpan)
    assertEquals(companion.sourcePos.span, rawCompanionSpan)
  }

  test("nested collections and positions are never null on successful decoding") {
    val decoded = view("class Complete[A](value: Option[A])(using ord: Ordering[A])")
    assert(decoded.typeParameters != null)
    assert(decoded.constructorClauses != null)
    assert(decoded.constructorClauses.forall(_.parameters != null))
    assert(decoded.constructorClauses.flatMap(_.parameters).forall(_.rawType != null))
    assert(decoded.classPos != null)
    assert(decoded.constructorPos != null)
  }

  private def onlyParameter(view: AnnotatedClassView): AnnotatedClassView.ConstructorParameter =
    view.constructorClauses match
      case AnnotatedClassView.ConstructorClause(parameter :: Nil, _, _) :: Nil => parameter
      case other => fail(s"expected one constructor parameter, found $other")

  private def view(code: String): AnnotatedClassView =
    val (stats, context) = parsedStats(code)
    given Context = context
    val candidate = stats.collectFirst { case value: TypeDef => value }.getOrElse(fail(s"missing TypeDef in $stats"))
    AnnotatedClassView.decode(candidate) match
      case Right(value) => value
      case Left(diagnostic) => fail(diagnostic.message)

  private def typeDefNamed(stats: List[Tree], name: String): TypeDef =
    stats.collectFirst { case value: TypeDef if value.name.toString == name => value }
      .getOrElse(fail(s"missing TypeDef $name in $stats"))

  private def parsedStats(code: String): (List[Tree], Context) =
    val unit = CompilationUnit("AnnotatedClassViewSpec.scala", code)
    val context = ContextBase().initialCtx.fresh.setCompilationUnit(unit)
    val parsed = new Parsers.Parser(unit.source)(using context).parse()
    val stats = parsed match
      case PackageDef(_, values) => values
      case tree => List(tree)
    (stats, context)
