package macroparadise

import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.ast.Trees
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Contexts.{Context, ContextBase}
import dotty.tools.dotc.core.Names.{termName, typeName}
import dotty.tools.dotc.parsing.Parsers
import paradise3.api.*

import scala.collection.mutable.ListBuffer

class StagedSchedulerSpec extends munit.FunSuite:
  test("source order is recomputed from the current staged target") {
    withStats("@a @b class A") { (ctx: Context) ?=> (stats: List[Tree]) =>
      val calls = ListBuffer.empty[String]
      val result = ParadiseTreeRewrite.scheduleForTesting(
        stats,
        List(recording("a", calls), recording("b", calls))
      )

      assert(result.isRight)
      assertEquals(calls.toList, List("a", "b"))
    }
  }

  test("a fresh handled annotation introduced by one stage becomes eligible") {
    withStats("@a class A; @b object A") { (ctx: Context) ?=> (stats: List[Tree]) =>
      val calls = ListBuffer.empty[String]
      val introduceB = handler("a") { input =>
        calls += "a"
        val companion = input.companion.get.tree.asInstanceOf[ModuleDef]
        val b = Trees.mods(companion).annotations.head
        val primary = input.primary.tree.asInstanceOf[TypeDef]
          .withMods(Trees.mods(input.primary.tree.asInstanceOf[TypeDef]).withAnnotations(List(b)))
          .asInstanceOf[TypeDef]
        ExpansionOutcome.Structured(
          ExpansionChanges(
            primary = PrimaryChange.Replace(ExpansionTarget.Class(primary)),
            companion = CompanionChange.Delete
          )
        )
      }
      val result = ParadiseTreeRewrite.scheduleForTesting(
        stats,
        List(introduceB, recording("b", calls))
      )

      assert(result.isRight)
      assertEquals(calls.toList, List("a", "b"))
    }
  }

  test("a preserved physical annotation occurrence is consumed only once") {
    withStats("@a class A") { (ctx: Context) ?=> (stats: List[Tree]) =>
      val calls = ListBuffer.empty[String]
      val result = ParadiseTreeRewrite.scheduleForTesting(stats, List(recording("a", calls)))

      assert(result.isRight)
      assertEquals(calls.toList, List("a"))
      assertEquals(Trees.mods(result.toOption.get.head.asInstanceOf[TypeDef]).annotations.size, 1)
    }
  }

  test("deleting an owned primary and nonadjacent companion discards later work on both") {
    withStats("@a class A; class Between; @b object A") { (ctx: Context) ?=> (stats: List[Tree]) =>
      val calls = ListBuffer.empty[String]
      val delete = handler("a") { _ => calls += "a"; ExpansionOutcome.Expanded(Nil) }
      val result = ParadiseTreeRewrite.scheduleForTesting(stats, List(delete, recording("b", calls)))

      assertEquals(calls.toList, List("a"))
      assertEquals(result.toOption.get.collect { case value: TypeDef => value.name.toString }, List("Between"))
    }
  }

  test("raw expansion is exact zero-to-many replacement with no privileged first output") {
    withStats("@raw class A; object A") { (ctx: Context) ?=> (stats: List[Tree]) =>
      val primary = stats.head.asInstanceOf[TypeDef]
      val companion = stats(1).asInstanceOf[ModuleDef]
      val outputs = List[Tree](
        cpy.ModuleDef(companion)(termName("X"), companion.impl),
        cpy.TypeDef(primary)(typeName("Y"), primary.rhs),
        cpy.TypeDef(primary)(typeName("X"), primary.rhs)
      )
      val result = ParadiseTreeRewrite.scheduleForTesting(
        stats,
        List(handler("raw")(_ => ExpansionOutcome.Expanded(outputs)))
      ).toOption.get

      assertEquals(
        result.map:
          case value: ModuleDef => s"object:${value.name}"
          case value: TypeDef   => s"type:${value.name}"
          case value            => value.getClass.getSimpleName,
        List("object:X", "type:Y", "type:X")
      )
      assert(result.zip(outputs).forall((actual, expected) => actual eq expected))
    }
  }

  test("structured labels address input occurrences and final companions are recomputed") {
    withStats("@rename class A; object A; object B") { (ctx: Context) ?=> (stats: List[Tree]) =>
      val result = ParadiseTreeRewrite.scheduleForTesting(
        stats,
        List(handler("rename") { input =>
          val current = input.primary.tree.asInstanceOf[TypeDef]
          val renamed = cpy.TypeDef(current)(typeName("B"), current.rhs)
          ExpansionOutcome.Structured(
            ExpansionChanges(primary = PrimaryChange.Replace(ExpansionTarget.Class(renamed)))
          )
        })
      ).toOption.get

      assertEquals(result.map(definitionName), List("B", "A", "B"))
      assert(result(1) eq stats(1))
      assert(result(2) eq stats(2))
    }
  }

  test("dedicated companion creation follows a renamed surviving primary") {
    withStats("@create class A") { (ctx: Context) ?=> (stats: List[Tree]) =>
      val result = ParadiseTreeRewrite.scheduleForTesting(
        stats,
        List(handler("create") { input =>
          val current = input.primary.tree.asInstanceOf[TypeDef]
          val renamed = cpy.TypeDef(current)(typeName("B"), current.rhs)
          val created = ModuleDef(termName("B"), current.rhs.asInstanceOf[Template])
          ExpansionOutcome.Structured(
            ExpansionChanges(
              primary = PrimaryChange.Replace(ExpansionTarget.Class(renamed)),
              companion = CompanionChange.Create(ExpansionTarget.Object(created), DefinitionPlacement.AfterPrimary)
            )
          )
        })
      ).toOption.get

      assertEquals(result.map(definitionName), List("B", "B"))
    }
  }

  test("sibling creation cannot disguise final companion creation") {
    withStats("@bad class A") { (ctx: Context) ?=> (stats: List[Tree]) =>
      val result = ParadiseTreeRewrite.scheduleForTesting(
        stats,
        List(handler("bad") { input =>
          val primary = input.primary.tree.asInstanceOf[TypeDef]
          val disguised = ModuleDef(termName("A"), primary.rhs.asInstanceOf[Template])
          ExpansionOutcome.Structured(
            ExpansionChanges(siblings = List(SiblingChange.Create(ExpansionTarget.Object(disguised), DefinitionPlacement.AfterPrimary)))
          )
        })
      )

      assert(result.left.toOption.exists(_.contains("SiblingChange.Create")))
    }
  }

  test("a late failure rolls the complete unit back to exact original occurrences") {
    withStats("@a class A; @b object A; class Stable") { (ctx: Context) ?=> (stats: List[Tree]) =>
      val introduceB = handler("a") { input =>
        val companion = input.companion.get.tree.asInstanceOf[ModuleDef]
        val b = Trees.mods(companion).annotations.head
        val primary = input.primary.tree.asInstanceOf[TypeDef]
          .withMods(Trees.mods(input.primary.tree.asInstanceOf[TypeDef]).withAnnotations(List(b)))
          .asInstanceOf[TypeDef]
        ExpansionOutcome.Structured(
          ExpansionChanges(
            primary = PrimaryChange.Replace(ExpansionTarget.Class(primary)),
            companion = CompanionChange.Delete
          )
        )
      }
      val reject = handler("b") { input =>
        ExpansionOutcome.Rejected(List(ExpansionDiagnostic("late", input.currentAnnotation.sourcePos)))
      }
      val (rolledBack, failure) = ParadiseTreeRewrite.scheduleAtomicallyForTesting(stats, List(introduceB, reject))

      assert(failure.nonEmpty)
      assert(rolledBack.zip(stats).forall((actual, original) => actual eq original))
    }
  }

  test("a verified companion is discovered by kind and name across unrelated statements") {
    withStats("@a object A; class Between; class A") { (ctx: Context) ?=> (stats: List[Tree]) =>
      var observed: Option[(ExpansionTargetKind, ExpansionTargetKind)] = None
      val result = ParadiseTreeRewrite.scheduleForTesting(stats, List(handler("a") { input =>
        observed = input.companion.map(value => input.primary.kind -> value.kind)
        ExpansionOutcome.Structured(ExpansionChanges())
      }))

      assert(result.isRight)
      assertEquals(observed, Some(ExpansionTargetKind.Object -> ExpansionTargetKind.Class))
    }
  }

  test("primary deletion preserves the former companion as an ordinary definition") {
    withStats("@a class A; class Between; object A") { (ctx: Context) ?=> (stats: List[Tree]) =>
      val result = ParadiseTreeRewrite.scheduleForTesting(
        stats,
        List(handler("a")(_ => ExpansionOutcome.Structured(ExpansionChanges(primary = PrimaryChange.Delete))))
      ).toOption.get

      assertEquals(result.map(definitionName), List("Between", "A"))
      assert(result.last eq stats.last)
    }
  }

  test("primary deletion plus companion creation is rejected") {
    withStats("@a class A") { (ctx: Context) ?=> (stats: List[Tree]) =>
      val primary = stats.head.asInstanceOf[TypeDef]
      val created = ModuleDef(termName("A"), primary.rhs.asInstanceOf[Template])
      val result = ParadiseTreeRewrite.scheduleForTesting(stats, List(handler("a")(_ =>
        ExpansionOutcome.Structured(ExpansionChanges(
          primary = PrimaryChange.Delete,
          companion = CompanionChange.Create(ExpansionTarget.Object(created), DefinitionPlacement.AfterPrimary)
        ))
      )))

      assert(result.left.toOption.exists(_.contains("surviving resulting primary")))
    }
  }

  test("ordered merge patches preserve unspecified members") {
    withStats("@a class A { def kept: Int = 1 }") { (ctx: Context) ?=> (stats: List[Tree]) =>
      val added = DefDef(termName("added"), Nil, Ident(typeName("Int")), Literal(dotty.tools.dotc.core.Constants.Constant(2)))
      val result = ParadiseTreeRewrite.scheduleForTesting(stats, List(handler("a")(_ =>
        ExpansionOutcome.Structured(ExpansionChanges(primary = PrimaryChange.Merge(List(
          TargetPatch.ReplaceAnnotations(Nil),
          TargetPatch.AppendMembers(List(added))
        ))))
      ))).toOption.get
      val bodyNames = result.head.asInstanceOf[TypeDef].rhs.asInstanceOf[Template].body.collect {
        case member: MemberDef => member.name.toString
      }

      assertEquals(bodyNames, List("kept", "added"))
      assertEquals(Trees.mods(result.head.asInstanceOf[TypeDef]).annotations, Nil)
    }
  }

  test("removing a later stacked annotation removes its pending work") {
    withStats("@a @b class A") { (ctx: Context) ?=> (stats: List[Tree]) =>
      val calls = ListBuffer.empty[String]
      val remove = handler("a") { _ =>
        calls += "a"
        ExpansionOutcome.Structured(ExpansionChanges(primary = PrimaryChange.Merge(List(TargetPatch.ReplaceAnnotations(Nil)))))
      }
      val result = ParadiseTreeRewrite.scheduleForTesting(stats, List(remove, recording("b", calls)))

      assert(result.isRight)
      assertEquals(calls.toList, List("a"))
    }
  }

  test("a fresh syntax-equivalent annotation is new work and can bottom out") {
    withStats("@a class A") { (ctx: Context) ?=> (stats: List[Tree]) =>
      var calls = 0
      val recursive = handler("a") { input =>
        calls += 1
        val nextAnnotations =
          if calls == 1 then List(copyAnnotation(input.currentAnnotation)) else Nil
        ExpansionOutcome.Structured(ExpansionChanges(primary = PrimaryChange.Merge(List(TargetPatch.ReplaceAnnotations(nextAnnotations)))))
      }
      val result = ParadiseTreeRewrite.scheduleForTesting(stats, List(recursive))

      assert(result.isRight)
      assertEquals(calls, 2)
    }
  }

  test("operational budget exhaustion fails the staged transaction") {
    withStats("@a class A") { (ctx: Context) ?=> (stats: List[Tree]) =>
      val recursive = handler("a") { input =>
        ExpansionOutcome.Structured(ExpansionChanges(primary = PrimaryChange.Merge(List(
          TargetPatch.ReplaceAnnotations(List(copyAnnotation(input.currentAnnotation)))
        ))))
      }
      val (rolledBack, failure) = ParadiseTreeRewrite.scheduleAtomicallyForTesting(stats, List(recursive))

      assert(failure.exists(_.contains("32-success budget")))
      assert(rolledBack.head eq stats.head)
    }
  }

  test("raw output rejects aliased definition occurrences") {
    withStats("@a class A") { (ctx: Context) ?=> (stats: List[Tree]) =>
      val tree = stats.head
      val result = ParadiseTreeRewrite.scheduleForTesting(stats, List(handler("a")(_ =>
        ExpansionOutcome.Expanded(List(tree, tree))
      )))

      assert(result.left.toOption.exists(_.contains("same raw tree object")))
    }
  }

  private def recording(name: String, calls: ListBuffer[String]): ExpansionHandler =
    handler(name) { _ => calls += name; ExpansionOutcome.Structured(ExpansionChanges()) }

  private def handler(name: String)(run: ExpansionInput => ExpansionOutcome): ExpansionHandler =
    new ExpansionHandler:
      val annotationName = name
      val admissions = ExpansionTargetKind.values.toList.map(
        ExpansionAdmission(_, ExpansionShapeProfile.OrdinaryTemplate)
      )
      def expand(input: ExpansionInput)(using Context): ExpansionOutcome = run(input)

  private def definitionName(tree: Tree): String = tree match
    case value: TypeDef => value.name.toString
    case value: ModuleDef => value.name.toString
    case value => value.getClass.getSimpleName

  private def copyAnnotation(tree: Tree)(using Context): Tree = tree match
    case value: Apply => Apply(value.fun, value.args).withSpan(value.span)
    case other => fail(s"expected parser annotation Apply, found ${other.getClass.getName}")

  private def withStats[A](source: String)(run: Context ?=> List[Tree] => A): A =
    val unit = CompilationUnit("StagedSchedulerFixture.scala", source)
    given Context = ContextBase().initialCtx.fresh.setCompilationUnit(unit)
    val stats = new Parsers.Parser(unit.source).parse() match
      case PackageDef(_, values) => values
      case other => fail(s"missing package stats in $other")
    run(stats)
