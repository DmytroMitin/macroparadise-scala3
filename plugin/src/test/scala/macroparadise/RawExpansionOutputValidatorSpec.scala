package macroparadise

import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Contexts.{Context, ContextBase}
import dotty.tools.dotc.core.Names.*
import dotty.tools.dotc.util.SourceFile

class RawExpansionOutputValidatorSpec extends munit.FunSuite:
  private given Context = ContextBase().initialCtx
  private val source = SourceFile.virtual("RawExpansionOutputValidatorSpec.scala", "")

  private def typeDef(name: String): TypeDef =
    given SourceFile = source
    TypeDef(typeName(name), EmptyTree)

  private def moduleDef(name: String): ModuleDef =
    given SourceFile = source
    ModuleDef(
      termName(name),
      Template(emptyConstructor, Nil, Nil, EmptyValDef, Nil)
    )

  private def unknownTree: Tree =
    given SourceFile = source
    Literal(Constant(1))

  private def violation(
      trees: List[Tree],
      knownTopLevelNames: Set[String] = Set("Primary")
  ): Option[RawExpansionOutputValidator.Violation] =
    RawExpansionOutputValidator.validate(
      RawExpansionOutputValidator.Input(
        currentPrimary = typeDef("Primary"),
        knownTopLevelNames = knownTopLevelNames,
        trees = trees
      )
    )

  test("accepts primary only") {
    assertEquals(violation(List(typeDef("Primary"))), None)
  }

  test("accepts primary followed by companion") {
    assertEquals(
      violation(List(typeDef("Primary"), moduleDef("Primary"))),
      None
    )
  }

  test("accepts primary followed by a fresh named sibling") {
    assertEquals(
      violation(List(typeDef("Primary"), typeDef("FreshSibling"))),
      None
    )
  }

  test("accepts primary companion and multiple fresh named siblings") {
    assertEquals(
      violation(
        List(
          typeDef("Primary"),
          moduleDef("Primary"),
          typeDef("FreshSibling"),
          typeDef("SecondFreshSibling")
        )
      ),
      None
    )
  }

  test("accepts unknown additional raw tree kinds") {
    assertEquals(
      violation(List(typeDef("Primary"), unknownTree)),
      None
    )
  }

  test("rejects empty output with invariant A") {
    assertEquals(violation(Nil).map(_.invariant), Some("A (non-empty output)"))
  }

  test("rejects output whose first tree is not the primary with invariant B") {
    assertEquals(
      violation(List(unknownTree, typeDef("Primary"))).map(_.invariant),
      Some("B (primary first)")
    )
  }

  test("rejects sibling-only output with invariant B") {
    assertEquals(
      violation(List(typeDef("Other"))).map(_.invariant),
      Some("B (primary first)")
    )
  }

  test("rejects companion-first output with invariant B") {
    assertEquals(
      violation(List(moduleDef("Primary"), typeDef("Primary"))).map(_.invariant),
      Some("B (primary first)")
    )
  }

  test("rejects a wrong-name first TypeDef even when the primary appears later") {
    assertEquals(
      violation(List(typeDef("Other"), typeDef("Primary"))).map(_.invariant),
      Some("B (primary first)")
    )
  }

  test("rejects duplicate same-name primary definitions with invariant C") {
    assertEquals(
      violation(List(typeDef("Primary"), typeDef("Primary"))).map(_.invariant),
      Some("C (exactly one primary)")
    )
  }

  test("rejects duplicate same-name companions with invariant D") {
    assertEquals(
      violation(
        List(
          typeDef("Primary"),
          moduleDef("Primary"),
          moduleDef("Primary")
        )
      ).map(_.invariant),
      Some("D (at most one companion)")
    )
  }

  test("rejects a late same-name companion with invariant E") {
    assertEquals(
      violation(
        List(
          typeDef("Primary"),
          typeDef("FreshSibling"),
          moduleDef("Primary")
        )
      ).map(_.invariant),
      Some("E (companion immediately after primary)")
    )
  }

  test("rejects duplicate additional named output with invariant F") {
    assertEquals(
      violation(
        List(
          typeDef("Primary"),
          typeDef("Repeated"),
          moduleDef("Repeated")
        )
      ).map(_.invariant),
      Some("F (unique additional named outputs)")
    )
  }

  test("rejects additional named output conflicting with known top-level name with invariant G") {
    assertEquals(
      violation(
        List(typeDef("Primary"), typeDef("KnownConflict")),
        knownTopLevelNames = Set("Primary", "KnownConflict")
      ).map(_.invariant),
      Some("G (no known top-level conflict)")
    )
  }
