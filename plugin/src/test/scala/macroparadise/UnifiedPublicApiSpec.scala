package macroparadise

import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Contexts.{Context, ContextBase}
import dotty.tools.dotc.parsing.Parsers
import paradise3.api.*

class UnifiedPublicApiSpec extends munit.FunSuite:
  test("Class Trait and Object are orthogonal expansion target kinds") {
    withTargets("class C; trait T; object O") { (clazz, traitDefinition, module) =>
      val targets = List(
        ExpansionTarget.Class(clazz),
        ExpansionTarget.Trait(traitDefinition),
        ExpansionTarget.Object(module)
      )

      assertEquals(
        targets.map(target => target.kind -> target.name),
        List(
          ExpansionTargetKind.Class -> "C",
          ExpansionTargetKind.Trait -> "T",
          ExpansionTargetKind.Object -> "O"
        )
      )
    }
  }

  test("structured changes are sparse and raw expansion permits an empty exact result") {
    assertEquals(
      ExpansionChanges(),
      ExpansionChanges(
        primary = PrimaryChange.Preserve,
        companion = CompanionChange.Preserve,
        siblings = Nil
      )
    )
    assertEquals(ExpansionOutcome.Expanded(Nil), ExpansionOutcome.Expanded(Nil))
  }

  test("the unified handler has exactly the selected declaration accessors") {
    val methods = classOf[ExpansionHandler].getDeclaredMethods.map(_.getName).toSet
    assertEquals(methods, Set("annotationName", "expand"))
  }

  test("removed protocol and composition classes are absent") {
    val removed = List(
      "paradise3.api.ParadiseAnnotationExpander",
      "paradise3.api.RoleAwareParadiseAnnotationExpander",
      "paradise3.api.ExpansionCompositionPolicy",
      "paradise3.api.ExpansionAdmission",
      "paradise3.api.ExpansionShapeProfile",
      "paradise3.api.OppositeChange",
      "paradise3.api.RoleAwareExpansionOutcome"
    )
    removed.foreach: name =>
      intercept[ClassNotFoundException](Class.forName(name, false, classOf[ExpansionHandler].getClassLoader))
  }

  test("sibling deltas have no preserve case") {
    intercept[ClassNotFoundException](
      Class.forName("paradise3.api.SiblingChange$Preserve$", false, classOf[SiblingChange].getClassLoader)
    )
  }

  private def withTargets[A](source: String)(
      run: (TypeDef, TypeDef, ModuleDef) => A
  ): A =
    val unit = CompilationUnit("UnifiedPublicApiFixture.scala", source)
    given Context = ContextBase().initialCtx.fresh.setCompilationUnit(unit)
    val stats = new Parsers.Parser(unit.source).parse() match
      case PackageDef(_, values) => values
      case other => fail(s"missing package stats in $other")
    val typeDefinitions = stats.collect { case value: TypeDef => value }
    val module = stats.collectFirst { case value: ModuleDef => value }
      .getOrElse(fail(s"missing module in $stats"))
    run(typeDefinitions.head, typeDefinitions(1), module)
