package macroparadise

import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.ast.Trees
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Contexts.{Context, ContextBase}
import dotty.tools.dotc.core.Names.termName
import dotty.tools.dotc.parsing.Parsers

class RawSelfRepresentationSpec extends munit.FunSuite:
  test("exact raw Template.self representation is stable across the supported compiler line") {
    val anonymousFlags =
      val (anonymousPrimary, anonymousContext) = parsePrimary("trait Nat")
      given Context = anonymousContext
      val anonymousTemplate = anonymousPrimary.rhs.asInstanceOf[Template]
      val anonymousSelf = anonymousTemplate.self

      assert(anonymousSelf.eq(EmptyValDef), clue(describe(anonymousSelf)))
      assertEquals(anonymousSelf.name.toString, "_")
      assert(anonymousSelf.tpt.eq(EmptyTree), clue(anonymousSelf.tpt))
      assert(anonymousSelf.rhs.eq(EmptyTree), clue(anonymousSelf.rhs))
      assert(!anonymousSelf.sourcePos.span.exists)
      Trees.mods(anonymousSelf).flags

    locally:
      val (namedPrimary, namedContext) = parsePrimary("trait Nat:\n  self =>")
      given Context = namedContext
      val namedSelf = namedPrimary.rhs.asInstanceOf[Template].self
      assert(!namedSelf.eq(EmptyValDef))
      assertEquals(namedSelf.name.toString, "self")
      assert(namedSelf.tpt.isInstanceOf[TypeTree], clue(namedSelf.tpt))
      assert(namedSelf.rhs.eq(EmptyTree), clue(namedSelf.rhs))
      assert(namedSelf.sourcePos.span.exists)
      assertEquals(Trees.mods(namedSelf).flags, anonymousFlags)

    locally:
      val (typedPrimary, typedContext) = parsePrimary("trait Nat:\n  self: SomeParent =>")
      given Context = typedContext
      val typedSelf = typedPrimary.rhs.asInstanceOf[Template].self
      assertEquals(typedSelf.name.toString, "self")
      typedSelf.tpt match
        case Ident(name) => assertEquals(name.toString, "SomeParent")
        case other => fail(s"expected typed self Ident, found $other")
      assert(typedSelf.rhs.eq(EmptyTree), clue(typedSelf.rhs))
      assert(typedSelf.sourcePos.span.exists)
      assertEquals(Trees.mods(typedSelf).flags, anonymousFlags)
  }

  test("Template copy preserves a named self exactly and anonymous replacement changes only the self slot") {
    locally:
      val (namedPrimary, namedContext) = parsePrimary("trait Nat:\n  alias: SomeParent =>\n  type Existing = String")
      given Context = namedContext
      val namedTemplate = namedPrimary.rhs.asInstanceOf[Template]
      val copiedNamed = copyTemplate(namedTemplate, namedTemplate.self)
      assert(copiedNamed.self.eq(namedTemplate.self), clue(copiedNamed.self))
      assert(
        copiedNamed.body.zip(namedTemplate.body).forall:
          case (left, right) => left.eq(right)
      )

    locally:
      val (anonymousPrimary, anonymousContext) = parsePrimary("trait Nat:\n  type Existing = String")
      given Context = anonymousContext
      val anonymousTemplate = anonymousPrimary.rhs.asInstanceOf[Template]
      val originalSelf = anonymousTemplate.self
      val replacementType = TypeTree()
      val replacement =
        ValDef(termName("self"), replacementType, originalSelf.rhs)
          .withMods(Trees.mods(originalSelf))
          .withSpan(anonymousPrimary.span)
          .asInstanceOf[ValDef]
      val copiedAnonymous = copyTemplate(anonymousTemplate, replacement)

      assert(!replacement.eq(EmptyValDef))
      assertEquals(replacement.name.toString, "self")
      assert(replacement.tpt.eq(replacementType), clue(replacement.tpt))
      assert(replacement.rhs.eq(originalSelf.rhs), clue(replacement.rhs))
      assertEquals(Trees.mods(replacement).flags, Trees.mods(originalSelf).flags)
      assertEquals(replacement.source, anonymousPrimary.source)
      assert(replacement.sourcePos.span.exists)
      assert(copiedAnonymous.self.eq(replacement), clue(copiedAnonymous.self))
      assert(copiedAnonymous.constr.eq(anonymousTemplate.constr), clue(copiedAnonymous.constr))
      assertEquals(copiedAnonymous.parentsOrDerived, anonymousTemplate.parentsOrDerived)
      assertEquals(copiedAnonymous.derived, anonymousTemplate.derived)
      assert(
        copiedAnonymous.body.zip(anonymousTemplate.body).forall:
          case (left, right) => left.eq(right)
      )
  }

  test("deterministic fresh alias spellings parse on the supported compiler line") {
    List("self", "self$1", "self$2").foreach: alias =>
      val (primary, context) = parsePrimary(s"trait Nat:\n  $alias =>")
      given Context = context
      val self = primary.rhs.asInstanceOf[Template].self
      assertEquals(self.name.toString, alias)
      assert(self.sourcePos.span.exists)
  }

  private def copyTemplate(template: Template, self: ValDef)(using Context): Template =
    cpy.Template(template)(
      template.constr,
      template.parentsOrDerived,
      template.derived,
      self,
      template.body
    )

  private def describe(self: ValDef)(using Context): String =
    List(
      s"empty=${self.eq(EmptyValDef)}",
      s"name=${self.name.toString}",
      s"flags=${Trees.mods(self).flags.toString}",
      s"tpt=${self.tpt.getClass.getSimpleName}:${self.tpt.toString}",
      s"rhs=${self.rhs.getClass.getSimpleName}:${self.rhs.toString}",
      s"span=${self.sourcePos.span}"
    ).mkString("{", ",", "}")

  private def parsePrimary(source: String): (TypeDef, Context) =
    val unit = CompilationUnit("RawSelfRepresentationSpec.scala", source)
    val context = ContextBase().initialCtx.fresh.setCompilationUnit(unit)
    val parsed = new Parsers.Parser(unit.source)(using context).parse()
    val stats = parsed match
      case PackageDef(_, values) => values
      case tree => List(tree)
    val primary = stats.collectFirst:
      case value: TypeDef if value.name.toString == "Nat" => value
    .getOrElse(fail(s"missing Nat in $stats"))
    (primary, context)
