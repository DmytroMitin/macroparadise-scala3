package macroparadise

import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.ast.Trees
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Contexts.{Context, ContextBase}
import dotty.tools.dotc.core.Names.{termName, typeName}
import dotty.tools.dotc.parsing.Parsers
import paradise3.api.*
import paradise3.api.helpers.{ExpansionHelpers, MemberConflictPolicy, MissingCompanionPolicy}

class UnifiedExpansionEditSpec extends munit.FunSuite:
  test("immutable edits compose generic primary and companion member placement") {
    withInput("@a class A; object A") { (ctx: Context) ?=> input =>
      val first = ExpansionEdit.start(input).toOption.get
      val edited = for
        primary <- ExpansionHelpers.placeMemberInPrimary(first, stringMethod("primaryMember", "p"))
        companion <- ExpansionHelpers.placeMemberInCompanion(primary, stringMethod("companionMember", "c"))
      yield companion

      assert(edited.isRight)
      assertEquals(first.changes, ExpansionChanges())
      val changes = ExpansionEdit.finish(edited).asInstanceOf[ExpansionOutcome.Structured].changes
      assert(changes.primary.isInstanceOf[PrimaryChange.Merge])
      assert(changes.companion.isInstanceOf[CompanionChange.Merge])
    }
  }

  test("missing companion creation stays a dedicated Create through later merges") {
    withInput("@a class A") { (ctx: Context) ?=> input =>
      val edited = for
        start <- ExpansionEdit.start(input)
        created <- ExpansionHelpers.placeMemberInCompanion(
          start,
          stringMethod("one", "1"),
          MissingCompanionPolicy.Create(ExpansionTargetKind.Object, DefinitionPlacement.AfterPrimary)
        )
        merged <- ExpansionHelpers.placeMemberInCompanion(created, stringMethod("two", "2"))
      yield merged
      val changes = ExpansionEdit.finish(edited).asInstanceOf[ExpansionOutcome.Structured].changes

      changes.companion match
        case CompanionChange.Create(ExpansionTarget.Object(value), DefinitionPlacement.AfterPrimary) =>
          assertEquals(value.impl.body.collect { case member: MemberDef => member.name.toString }, List("one", "two"))
        case other => fail(s"expected create-then-merge normalization, found $other")
    }
  }

  test("generic conflict policy rejects or preserves an existing direct member") {
    withInput("@a class A { def existing: String = \"kept\" }") { (ctx: Context) ?=> input =>
      val start = ExpansionEdit.start(input).toOption.get
      val rejected = ExpansionHelpers.placeMemberInPrimary(start, stringMethod("existing", "new"))
      val preserved = ExpansionHelpers.placeMemberInPrimary(
        start,
        stringMethod("existing", "new"),
        MemberConflictPolicy.PreserveExisting
      )

      assert(rejected.isLeft)
      assertEquals(preserved.toOption.get.changes, ExpansionChanges())
    }
  }

  test("annotation replacement and sibling creation are sparse independent lanes") {
    withInput("@a class A") { (ctx: Context) ?=> input =>
      val primary = input.primary.tree.asInstanceOf[TypeDef]
      val sibling = ExpansionTarget.Class(cpy.TypeDef(primary)(typeName("B"), primary.rhs))
      val edited = for
        start <- ExpansionEdit.start(input)
        stripped <- ExpansionHelpers.replacePrimaryAnnotations(start, Nil)
        withSibling <- ExpansionHelpers.createSibling(stripped, sibling, DefinitionPlacement.BeforePrimary)
      yield withSibling
      val changes = ExpansionEdit.finish(edited).asInstanceOf[ExpansionOutcome.Structured].changes

      assertEquals(changes.companion, CompanionChange.Preserve)
      assertEquals(changes.siblings.size, 1)
      assert(changes.primary.isInstanceOf[PrimaryChange.Merge])
    }
  }

  test("public member placement rejects a root with neither source nor span") {
    withInput("@a class A") { (ctx: Context) ?=> input =>
      val sourceFree =
        given Context = ContextBase().initialCtx
        stringMethod("sourceFree", "no")
      val start = ExpansionEdit.start(input).toOption.get
      val result = ExpansionHelpers.placeMemberInPrimary(start, sourceFree)

      assert(!sourceFree.source.exists)
      assert(!sourceFree.span.exists)
      assert(result.left.toOption.exists(_.message.contains("neither source nor span provenance")))
      assertEquals(start.changes, ExpansionChanges())
    }
  }

  test("public member placement accepts source provenance without a span") {
    withInput("@a class A") { (ctx: Context) ?=> input =>
      val sourceFree =
        given Context = ContextBase().initialCtx
        stringMethod("sourceOnly", "yes")
      val generated = sourceFree.cloneIn(input.primary.tree.source).asInstanceOf[DefDef]
      assert(generated.source.exists)
      assert(!generated.span.exists)
      val result = ExpansionHelpers.placeMemberInPrimary(ExpansionEdit.start(input).toOption.get, generated)
      assert(result.isRight)
    }
  }

  private def stringMethod(name: String, value: String)(using Context): DefDef =
    DefDef(termName(name), Nil, Ident(typeName("String")), Literal(Constant(value)))

  private def withInput[A](source: String)(run: Context ?=> ExpansionInput => A): A =
    val unit = CompilationUnit("UnifiedExpansionEditFixture.scala", source)
    given Context = ContextBase().initialCtx.fresh.setCompilationUnit(unit)
    val stats = new Parsers.Parser(unit.source).parse() match
      case PackageDef(_, values) => values
      case other => fail(s"missing package stats in $other")
    val primary = stats.collectFirst { case value: TypeDef => value }.get
    val companion = stats.collectFirst { case value: ModuleDef => value }.map(ExpansionTarget.Object(_))
    run(
      PluginInvocationMinting.input(
        ExpansionTarget.Class(primary),
        companion,
        PluginInvocationMinting.container(
          stats.collect { case value: MemberDef => value.name.toString }.toSet
        ),
        Trees.mods(primary).annotations.head
      )
    )
