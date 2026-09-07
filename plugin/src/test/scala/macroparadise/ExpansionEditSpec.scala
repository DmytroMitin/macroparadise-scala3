package macroparadise

import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.ast.{Trees, untpd}
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Contexts.{Context, ContextBase}
import dotty.tools.dotc.parsing.Parsers
import paradise3.api.*
import paradise3.api.helpers.*

class ExpansionEditSpec extends munit.FunSuite:
  private def parsed(source: String)(use: Context ?=> List[Tree] => Unit): Unit =
    val unit = CompilationUnit("ExpansionEditSpec.scala", source)
    given Context = ContextBase().initialCtx.fresh.setCompilationUnit(unit)
    val tree = new Parsers.Parser(unit.source).parse()
    use(tree.asInstanceOf[PackageDef].stats)

  private def members(stats: List[Tree])(using Context): List[MemberDef] =
    stats.collectFirst { case m: ModuleDef if m.name.toString == "Generated" =>
      m.impl.body.collect { case d: DefDef => d; case v: ValDef => v }
    }.get

  private val generated = "\nobject Generated { def foo: String = \"ok\"; val answer: Int = 42 }"

  for kind <- List("class", "trait") do
    test(s"legacy $kind composes both sides and preserves earlier immutable states") {
      parsed(s"@current @later $kind A { val before = 1 }; object A { val old = 2 }" + generated) { stats =>
        val primary = stats.collectFirst { case t: TypeDef => t }.get
        val companion = stats.collectFirst { case m: ModuleDef => m }.get
        val annotations = Trees.mods(primary).annotations
        val input = ExpansionInput("current", primary, Some(companion), Set("A"), Some(annotations.head))
        val start = LegacyExpansionEdit.start(input).toOption.get
        assert(start.primary eq primary)
        assert(start.companion.get eq companion)
        val one = ExpansionHelpers.addStringMethodToClass(start, "a", "A").toOption.get
        val two = ExpansionHelpers.placeMembersInPrimary(one, members(stats)).toOption.get
        val program = ExpansionHelpers.placeMembersInCompanion(two, members(stats))
        val output = LegacyExpansionEdit.finish(input, program).asInstanceOf[ExpansionOutcome.Structured].output
        assertEquals(output.primary.rhs.asInstanceOf[Template].body.collect { case m: MemberDef => m.name.toString }, List("before", "a", "foo", "answer"))
        assertEquals(output.companion.get.impl.body.size, 3)
        assert(output.primary.rhs.asInstanceOf[Template].body.takeRight(2).zip(members(stats)).forall((a,b) => a eq b))
        assert(Trees.mods(output.primary).annotations.head eq annotations.last)
        assertEquals(Trees.mods(primary).annotations, annotations)
        assertEquals(primary.rhs.asInstanceOf[Template].body.size, 1)
        assertEquals(companion.impl.body.size, 1)
        assertEquals(one.primary.rhs.asInstanceOf[Template].body.size, 2)
        assert(LegacyExpansionEdit.finish(one).asInstanceOf[ExpansionOutcome.Structured].output.companion.get eq companion)
        val failed = program.flatMap(e => ExpansionHelpers.placeMembersInPrimary(e, members(stats)))
        val rejection = LegacyExpansionEdit.finish(input, failed).asInstanceOf[ExpansionOutcome.Rejected]
        assert(rejection.fallback eq primary)
        assertEquals(rejection.diagnostics.size, 1)
        assertEquals(two.primary.rhs.asInstanceOf[Template].body.size, 4)
        val historical = ExpansionHelpers.placeMembersInPrimary(input, members(stats)).asInstanceOf[ExpansionOutcome.Structured].output
        assertEquals(historical.companion, None)
      }
    }

  for kind <- List("class", "trait"); existing <- List(true, false) do
    test(s"role-aware $kind existing=$existing composes primary and opposite preserving creation provenance") {
      val opposite = if existing then s"$kind A { val before = 1 };" else ""
      parsed(opposite + "@current @later object A { val old = 2 }" + generated) { stats =>
        val primary = stats.collectFirst { case m: ModuleDef if m.name.toString == "A" => m }.get
        val lease = stats.collectFirst { case t: TypeDef =>
          if kind == "class" then ExpansionOppositeRole.Class(t) else ExpansionOppositeRole.Trait(t)
        }
        val annotations = Trees.mods(primary).annotations
        val input = RoleAwareExpansionInput("current", ExpansionPrimaryRole.Object(primary), lease, Set("A"), annotations.head, RoleAwareTargetAdmission.OrdinaryTopLevelObject)
        val start = RoleAwareExpansionEdit.start(input).toOption.get
        assertEquals(start.primary, input.primary)
        assertEquals(start.opposite, lease)
        val placement = if kind == "class" then OppositePlacement.BeforePrimary else OppositePlacement.AfterPrimary
        val policy = if kind == "class" then RoleAwareMissingOppositePolicy.CreateClass(placement) else RoleAwareMissingOppositePolicy.CreateTrait(placement)
        val first = ExpansionHelpers.placeMembersInPrimary(start, members(stats)).toOption.get
        assertEquals(RoleAwareExpansionEdit.finish(first).asInstanceOf[RoleAwareExpansionOutcome.Expanded].output.opposite, OppositeChange.Preserve)
        val program = ExpansionHelpers.placeMembersInOpposite(first, members(stats).take(1), policy)
          .flatMap(e => ExpansionHelpers.placeMembersInOpposite(e, members(stats).drop(1), RoleAwareMissingOppositePolicy.Reject))
        val output = RoleAwareExpansionEdit.finish(program).asInstanceOf[RoleAwareExpansionOutcome.Expanded].output
        val changed = output.opposite match
          case OppositeChange.Replace(value) =>
            assert(existing)
            value
          case OppositeChange.Create(value, p) =>
            assert(!existing)
            assertEquals(p, placement)
            value
          case other => fail(s"unexpected $other")
        val tree = changed match
          case ExpansionOppositeRole.Class(t) =>
            assertEquals(kind, "class")
            t
          case ExpansionOppositeRole.Trait(t) =>
            assertEquals(kind, "trait")
            t
          case _ => fail("object opposite")
        assertEquals(tree.rhs.asInstanceOf[Template].body.size, if existing then 3 else 2)
        val resultPrimary = output.primary.asInstanceOf[ExpansionPrimaryRole.Object].tree
        assertEquals(resultPrimary.impl.body.size, 3)
        assert(Trees.mods(resultPrimary).annotations.zip(annotations).forall((a,b) => a eq b))
        assertEquals(primary.impl.body.size, 1)
        val failed = program.flatMap(e => ExpansionHelpers.placeMembersInPrimary(e, Nil))
        assert(RoleAwareExpansionEdit.finish(failed).isInstanceOf[RoleAwareExpansionOutcome.Rejected])
        assertEquals(first.primary.asInstanceOf[ExpansionPrimaryRole.Object].tree.impl.body.size, 3)
        assertEquals(start.opposite, lease)
      }
    }

  test("role-aware missing opposite rejection discards a successful primary proposal") {
    parsed("@current object A" + generated) { stats =>
      val primary = stats.head.asInstanceOf[ModuleDef]
      val input = RoleAwareExpansionInput("current", ExpansionPrimaryRole.Object(primary), None, Set("A"), Trees.mods(primary).annotations.head, RoleAwareTargetAdmission.OrdinaryTopLevelObject)
      val program = for
        e0 <- RoleAwareExpansionEdit.start(input)
        e1 <- ExpansionHelpers.placeMembersInPrimary(e0, members(stats))
        e2 <- ExpansionHelpers.placeMembersInOpposite(e1, members(stats), RoleAwareMissingOppositePolicy.Reject)
      yield e2
      assert(RoleAwareExpansionEdit.finish(program).isInstanceOf[RoleAwareExpansionOutcome.Rejected])
      assertEquals(primary.impl.body, Nil)
    }
  }

  test("role-aware controlled negatives preserve the source and proposal") {
    parsed("@current object A" + generated) { stats =>
      val primary = stats.head.asInstanceOf[ModuleDef]
      val input = RoleAwareExpansionInput("current", ExpansionPrimaryRole.Object(primary), None, Set("A"), Trees.mods(primary).annotations.head, RoleAwareTargetAdmission.OrdinaryTopLevelObject)
      val state = RoleAwareExpansionEdit.start(input).toOption.get
      val batch = members(stats)
      val malformed = List(
        null.asInstanceOf[RoleAwareExpansionInput],
        input.copy(primary = null),
        input.copy(leasedOpposite = null),
        input.copy(leasedOpposite = Some(null)),
        input.copy(leasedOpposite = Some(ExpansionOppositeRole.Object(primary))),
        input.copy(currentAnnotation = null),
        input.copy(admission = null)
      )
      malformed.foreach(i => assert(RoleAwareExpansionEdit.start(i).isLeft))
      val invalidBatches = List(null.asInstanceOf[List[MemberDef]], Nil, List(null.asInstanceOf[MemberDef]), List(primary), batch ++ batch, List(primary.impl.constr))
      invalidBatches.foreach { invalid =>
        assert(ExpansionHelpers.placeMembersInPrimary(state, invalid).isLeft)
        assert(ExpansionHelpers.placeMembersInOpposite(state, invalid, RoleAwareMissingOppositePolicy.CreateClass(OppositePlacement.AfterPrimary)).isLeft)
      }
      assert(ExpansionHelpers.placeMembersInOpposite(state, batch, null).isLeft)
      assert(ExpansionHelpers.placeMembersInOpposite(state, batch, RoleAwareMissingOppositePolicy.CreateClass(null)).isLeft)
      val sourceFree = ExpansionHelpers.stringReturningMethod("unpositioned", "bad", dotty.tools.dotc.util.NoSource)
      assert(ExpansionHelpers.placeMembersInPrimary(state, List(sourceFree)).isLeft)
      assertEquals(primary.impl.body, Nil)
      assertEquals(state.opposite, None)
    }
  }

  test("successful composed proposal followed by late topology rejection restores the exact package snapshot") {
    parsed("class A { val original = 1 }; @current object A" + generated) { stats =>
      import RoleAwareTransactionKernel.*
      val primary = stats.collectFirst { case m: ModuleDef if m.name.toString == "A" => m }.get
      val opposite = stats.head.asInstanceOf[TypeDef]
      val transaction = ObjectTransaction.discover(stats, primary, Vector("current")).toOption.get
      val input = RoleAwareExpansionInput("current", ExpansionPrimaryRole.Object(primary), Some(ExpansionOppositeRole.Class(opposite)), Set("A"), Trees.mods(primary).annotations.head, RoleAwareTargetAdmission.OrdinaryTopLevelObject)
      val edited = for
        e0 <- RoleAwareExpansionEdit.start(input)
        e1 <- ExpansionHelpers.placeMembersInPrimary(e0, members(stats))
        e2 <- ExpansionHelpers.placeMembersInOpposite(e1, members(stats), RoleAwareMissingOppositePolicy.Reject)
      yield e2
      val output = RoleAwareExpansionEdit.finish(edited).asInstanceOf[RoleAwareExpansionOutcome.Expanded].output
      val staged = transaction.stageValidatedOutput(RoleAwareExpansionResult(
        PrimaryRole.ObjectPrimary(output.primary.asInstanceOf[ExpansionPrimaryRole.Object].tree),
        Some(OppositeRole.ClassOpposite(output.opposite.asInstanceOf[OppositeChange.Replace].value.asInstanceOf[ExpansionOppositeRole.Class].tree))
      )).toOption.get
      val wrong = cpy.ModuleDef(primary)(dotty.tools.dotc.core.Names.termName("Wrong"), primary.impl)
      assert(staged.stageValidatedOutput(RoleAwareExpansionResult(PrimaryRole.ObjectPrimary(wrong), None)).isLeft)
      val rollback = staged.rollback
      assert(rollback.packageStats.asInstanceOf[AnyRef] eq stats.asInstanceOf[AnyRef])
      assert(rollback.primary.tree eq primary)
      assert(rollback.opposite.get.tree eq opposite)
      assertEquals(primary.impl.body, Nil)
      assertEquals(opposite.rhs.asInstanceOf[Template].body.size, 1)
    }
  }

  test("reverse edit order preserves both sides and legacy method-wins and None cleanup") {
    parsed("@current class A { def a: String = \"user\" }; object A { val old = 1 }" + generated) { stats =>
      val primary = stats.head.asInstanceOf[TypeDef]
      val companion = stats(1).asInstanceOf[ModuleDef]
      val input = ExpansionInput("current", primary, Some(companion), Set("A"), None)
      val program = for
        e0 <- LegacyExpansionEdit.start(input)
        e1 <- ExpansionHelpers.placeMembersInCompanion(e0, members(stats))
        e2 <- ExpansionHelpers.addStringMethodToClass(e1, "a", "ignored")
        e3 <- ExpansionHelpers.placeMembersInPrimary(e2, members(stats))
      yield e3
      val output = LegacyExpansionEdit.finish(input, program).asInstanceOf[ExpansionOutcome.Structured].output
      assert(output.primary.rhs.asInstanceOf[Template].body.head eq primary.rhs.asInstanceOf[Template].body.head)
      assertEquals(output.primary.rhs.asInstanceOf[Template].body.size, 3)
      assertEquals(output.companion.get.impl.body.size, 3)
      assertEquals(Trees.mods(output.primary).annotations, Nil)
      assertEquals(Trees.mods(primary).annotations.size, 1)
      intercept[IllegalArgumentException](LegacyExpansionEdit.finish(input.copy(), program))
      assertEquals(ExpansionHelpers.addStringMethodToClass(input, "a", "ignored").asInstanceOf[ExpansionOutcome.Structured].output.companion, None)
      assert(LegacyExpansionEdit.start(null).isLeft)
      assert(LegacyExpansionEdit.start(input.copy(annotatedClass = null)).isLeft)
      assert(LegacyExpansionEdit.start(input.copy(existingCompanion = null)).isLeft)
      assert(LegacyExpansionEdit.start(input.copy(existingCompanion = Some(null))).isLeft)
    }
    parsed("@opposite trait A { val before = 1 }; @current object A" + generated) { stats =>
      val opposite = stats.head.asInstanceOf[TypeDef]
      val primary = stats(1).asInstanceOf[ModuleDef]
      val input = RoleAwareExpansionInput("current", ExpansionPrimaryRole.Object(primary), Some(ExpansionOppositeRole.Trait(opposite)), Set("A"), Trees.mods(primary).annotations.head, RoleAwareTargetAdmission.OrdinaryTopLevelObject)
      val program = for
        e0 <- RoleAwareExpansionEdit.start(input)
        e1 <- ExpansionHelpers.placeMembersInOpposite(e0, members(stats), RoleAwareMissingOppositePolicy.Reject)
        e2 <- ExpansionHelpers.placeMembersInPrimary(e1, members(stats))
      yield e2
      val output = RoleAwareExpansionEdit.finish(program).asInstanceOf[RoleAwareExpansionOutcome.Expanded].output
      val result = output.opposite.asInstanceOf[OppositeChange.Replace].value.asInstanceOf[ExpansionOppositeRole.Trait].tree
      val before = opposite.rhs.asInstanceOf[Template]
      val after = result.rhs.asInstanceOf[Template]
      assert(after.body.head eq before.body.head)
      assert(after.constr eq before.constr)
      assert(after.self eq before.self)
      assertEquals(after.parentsOrDerived, before.parentsOrDerived)
      assertEquals(Trees.mods(result), Trees.mods(opposite))
      assert(Trees.mods(result).annotations.head eq Trees.mods(opposite).annotations.head)
      assertEquals(result.sourcePos, opposite.sourcePos)
      assertEquals(output.primary.asInstanceOf[ExpansionPrimaryRole.Object].tree.impl.body.size, 2)
      assert(RoleAwareExpansionEdit.start(input.copy(primary = ExpansionPrimaryRole.Trait(opposite))).isLeft)
      assert(RoleAwareExpansionEdit.start(input.copy(leasedOpposite = Some(ExpansionOppositeRole.Class(opposite)))).isLeft)
    }
  }
