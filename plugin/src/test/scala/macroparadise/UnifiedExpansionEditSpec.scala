package macroparadise

import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.ast.Trees
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Contexts.{Context, ContextBase}
import dotty.tools.dotc.core.Names.{termName, typeName}
import dotty.tools.dotc.parsing.Parsers
import paradise3.api.*
import paradise3.api.helpers.{ExpansionHelpers, ExpansionTransforms, MemberConflictPolicy, MissingCompanionPolicy}

class UnifiedExpansionEditSpec extends munit.FunSuite:
  test("transform factories match primitive primary and companion placement") {
    withInput("@a class A; object A") { (ctx: Context) ?=> input =>
      val one = stringMethod("one", "1")
      val two = stringMethod("two", "2")
      val start = ExpansionEdit.start(input)
      val transformed = for
        edit <- start
        primary <- ExpansionTransforms.placeMemberInPrimary(one)(edit)
        primaryBatch <- ExpansionTransforms.placeMembersInPrimary(List(two))(primary)
        companion <- ExpansionTransforms.placeMemberInCompanion(stringMethod("three", "3"))(primaryBatch)
        companionBatch <- ExpansionTransforms.placeMembersInCompanion(List(stringMethod("four", "4")))(companion)
      yield companionBatch
      val primitive = for
        edit <- ExpansionEdit.start(input)
        primary <- ExpansionHelpers.placeMemberInPrimary(edit, one)
        primaryBatch <- ExpansionHelpers.placeMembersInPrimary(primary, List(two))
        companion <- ExpansionHelpers.placeMemberInCompanion(primaryBatch, stringMethod("three", "3"))
        companionBatch <- ExpansionHelpers.placeMembersInCompanion(companion, List(stringMethod("four", "4")))
      yield companionBatch

      assertEquals(memberNames(transformed.toOption.get.primary), memberNames(primitive.toOption.get.primary))
      assertEquals(memberNames(transformed.toOption.get.companion.get), memberNames(primitive.toOption.get.companion.get))
    }
  }

  test("companion transforms expose missing-companion rejection and explicit creation") {
    withInput("@a class A") { (ctx: Context) ?=> input =>
      val start = ExpansionEdit.start(input)
      val rejected = start.flatMap(ExpansionTransforms.placeMemberInCompanion(stringMethod("one", "1")))
      val created = start.flatMap(ExpansionTransforms.placeMembersInCompanion(
        List(stringMethod("one", "1"), stringMethod("two", "2")),
        MissingCompanionPolicy.Create(ExpansionTargetKind.Object, DefinitionPlacement.AfterPrimary)
      ))

      assert(rejected.left.toOption.exists(_.message.contains("is missing")))
      created.toOption.get.changes.companion match
        case CompanionChange.Create(target, DefinitionPlacement.AfterPrimary) =>
          assertEquals(memberNames(target), List("one", "two"))
        case other => fail(s"expected explicit companion creation, found $other")
    }
  }

  test("annotation, sibling, and trait-self transforms match primitive helpers") {
    withInput("@a trait A; @b object A") { (ctx: Context) ?=> input =>
      val annotation = input.currentAnnotation
      val primary = input.primary.tree.asInstanceOf[TypeDef]
      val sibling = ExpansionTarget.Class(cpy.TypeDef(primary)(typeName("B"), primary.rhs))
      val self = ValDef(termName("self"), Ident(typeName("A")), EmptyTree)
      val generated = List(stringMethod("generated", "yes"))
      val transformed = for
        edit <- ExpansionEdit.start(input)
        primaryAnnotations <- ExpansionTransforms.replacePrimaryAnnotations(Nil)(edit)
        companionAnnotations <- ExpansionTransforms.replaceCompanionAnnotations(List(annotation))(primaryAnnotations)
        siblingCreated <- ExpansionTransforms.createSibling(sibling, DefinitionPlacement.BeforePrimary)(companionAnnotations)
        prepared <- ExpansionTransforms.prepareTraitSelf(self, generated)(siblingCreated)
      yield prepared
      val primitive = for
        edit <- ExpansionEdit.start(input)
        primaryAnnotations <- ExpansionHelpers.replacePrimaryAnnotations(edit, Nil)
        companionAnnotations <- ExpansionHelpers.replaceCompanionAnnotations(primaryAnnotations, List(annotation))
        siblingCreated <- ExpansionHelpers.createSibling(companionAnnotations, sibling, DefinitionPlacement.BeforePrimary)
        prepared <- ExpansionHelpers.prepareTraitSelf(siblingCreated, self, generated)
      yield prepared

      assert(transformed.isRight, transformed.left.toOption.map(_.message).getOrElse("missing transform result"))
      assert(primitive.isRight, primitive.left.toOption.map(_.message).getOrElse("missing primitive result"))
      assertEquals(transformed.toOption.get.changes.siblings.size, primitive.toOption.get.changes.siblings.size)
      assertEquals(memberNames(transformed.toOption.get.primary), memberNames(primitive.toOption.get.primary))
      assertEquals(Trees.mods(transformed.toOption.get.primary.tree.asInstanceOf[DefTree]).annotations.size, 0)
      assertEquals(Trees.mods(transformed.toOption.get.companion.get.tree.asInstanceOf[DefTree]).annotations.size, 1)
    }
  }

  test("transform failures short-circuit an Either flatMap pipeline") {
    withInput("@a class A { def existing: String = \"kept\" }") { (ctx: Context) ?=> input =>
      var continued = false
      val start = ExpansionEdit.start(input)
      val result = start
        .flatMap(ExpansionTransforms.placeMemberInPrimary(stringMethod("existing", "new")))
        .flatMap: edit =>
          continued = true
          ExpansionTransforms.placeMemberInPrimary(stringMethod("never", "called"))(edit)
      val preserved = start.flatMap(
        ExpansionTransforms.placeMemberInPrimary(
          stringMethod("existing", "new"),
          MemberConflictPolicy.PreserveExisting
        )
      )

      assert(result.left.toOption.exists(_.message.contains("conflicts")))
      assert(!continued)
      assertEquals(preserved.toOption.get.changes, ExpansionChanges())
    }
  }

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
  private def memberNames(target: ExpansionTarget)(using Context): List[String] =
    target.tree match
      case value: TypeDef => value.rhs.asInstanceOf[Template].body.collect { case member: MemberDef => member.name.toString }
      case value: ModuleDef => value.impl.body.collect { case member: MemberDef => member.name.toString }

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
        ExpansionTarget.fromTree(primary).toOption.get,
        companion,
        PluginInvocationMinting.container(
          stats.collect { case value: MemberDef => value.name.toString }.toSet
        ),
        Trees.mods(primary).annotations.head,
        List("fixture")
      )
    )
