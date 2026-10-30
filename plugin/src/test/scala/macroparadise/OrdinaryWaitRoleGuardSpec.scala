package macroparadise

import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.ast.untpd.Tree
import dotty.tools.dotc.core.Contexts.{Context, ContextBase}
import dotty.tools.dotc.parsing.Parsers

final class OrdinaryWaitRoleGuardSpec extends munit.FunSuite:
  private val configured = Vector("demo.markerA", "demo.markerB")

  // Removing whole-unit traversal must expose each independently hidden candidate.
  private val hidden = List(
    "method A" -> "def main(): Unit = { @demo.markerA class Hidden; () }",
    "method B" -> "def main(): Unit = { @demo.markerB class Hidden; () }",
    "nested block" -> "def main(): Unit = { { @demo.markerA class Hidden; () }; () }",
    "initializer" -> "val value = { @demo.markerB class Hidden; 1 }",
    "local object" -> "def main(): Unit = { object Local { @demo.markerA class Hidden }; () }",
    "lambda" -> "val f = () => { @demo.markerB class Hidden; () }",
    "template" -> "@demo.markerA class Hidden",
    "method annotation" -> "@demo.markerA def hidden(): Unit = ()",
    "pattern value annotation" -> "def main(): Unit = { @demo.markerA val (left, right) = (1, 2); () }",
    "value annotation" -> "@demo.markerB val hidden = 1",
    "type annotation" -> "val hidden: String @demo.markerA = null",
    "annotation argument" -> "@other.Unrelated({ @demo.markerB class Hidden; 1 }) class C",
    "root qualified" -> "def main(): Unit = { @_root_.demo.markerA class Hidden; () }"
  )
  hidden.foreach: (label, body) =>
    test(s"rejects configured annotation in $label without changing consumer selection") {
      val (tree, ctx) = parsed(s"package consumer; object Main { $body }")
      val result = OrdinaryWaitRoleGuard.validate(tree, configured)(using ctx)
      assert(result.isLeft, result.toString)
      assert(result.swap.toOption.get.message.startsWith("SAME_MODULE_ORDINARY_WAIT_ANNOTATED:"))
      assert(result.swap.toOption.get.pos.span.exists)
    }

  private val imports = List(
    "package explicit" -> "import demo.markerA; object Main { def main(): Unit = { @markerA class Hidden; () } }",
    "local explicit" -> "object Main { def main(): Unit = { import demo.markerB; @markerB class Hidden; () } }",
    "renamed" -> "import demo.{markerA as Alias}; object Main { def main(): Unit = { @Alias class Hidden; () } }",
    "wildcard" -> "import demo.*; object Main { def main(): Unit = { @markerB class Hidden; () } }",
    "prefix alias" -> "import demo as renamed; object Main { def main(): Unit = { @renamed.markerA class Hidden; () } }",
    "shadow" -> "import demo.markerA; object Main { def main(): Unit = { class markerA; @markerA class Hidden; () } }",
    "conflict" -> "import demo.markerA; import other.markerA; object Main { @markerA class Hidden }",
    "later import" -> "object Main { def main(): Unit = { @markerA class Hidden; import demo.markerA; () } }",
    "unresolved alias" -> "object Main { type Alias = demo.markerA; def main(): Unit = { @Alias class Hidden; () } }"
  )
  imports.foreach: (label, body) =>
    test(s"fails closed on $label identity without fabricating symbols") {
      val (tree, ctx) = parsed(s"package consumer; $body")
      assert(OrdinaryWaitRoleGuard.validate(tree, configured)(using ctx).isLeft)
    }

  List(
    "plain" -> "object Main { def main(): Unit = { class Local; val text = \"@demo.markerA\"; () } }",
    "unrelated qualified" -> "object Main { def main(): Unit = { @other.Unrelated class Local; () } }",
    "unrelated explicit" -> "import other.Unrelated; object Main { def main(): Unit = { @Unrelated class Local; () } }",
    "unrelated same leaf" -> "import other.markerA; object Main { def main(): Unit = { @markerA class Local; () } }",
    "sibling import isolation" -> "object First { import demo.markerA }; import other.Unrelated; object Main { @Unrelated class Local }"
  ).foreach: (label, body) =>
    test(s"preserves $label ordinary source") {
      val (tree, ctx) = parsed(s"package consumer; $body")
      assertEquals(OrdinaryWaitRoleGuard.validate(tree, configured)(using ctx), Right(()))
    }

  test("original consumer selector still excludes method bodies and rejects template consumers") {
    val (local, ctx) = parsed("package consumer; object Main { def main(): Unit = { @demo.markerA class Hidden; () } }")
    assertEquals(EmbeddedSameModuleSelection.select(local, configured)(using ctx), Right(None))
    val (nested, nestedCtx) = parsed("package consumer; object Main { @demo.markerA class Hidden }")
    assert(EmbeddedSameModuleSelection.select(nested, configured)(using nestedCtx).isLeft)
  }

  private def parsed(source: String): (Tree, Context) =
    val unit = CompilationUnit("OrdinaryWaitRoleGuardSpec.scala", source)
    val ctx = ContextBase().initialCtx.fresh.setCompilationUnit(unit)
    val tree = new Parsers.Parser(unit.source)(using ctx).parse()
    assert(!ctx.reporter.hasErrors, source)
    (tree, ctx)
