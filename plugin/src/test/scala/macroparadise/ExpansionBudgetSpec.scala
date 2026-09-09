package macroparadise

class ExpansionBudgetSpec extends munit.FunSuite:
  test("default and explicit positive expansion budgets parse") {
    assertEquals(ExpansionBudget.parse(Nil), Right(256))
    assertEquals(ExpansionBudget.parse(List("handler=x.H", "expansionBudget=33")), Right(33))
  }

  test("invalid expansion budgets fail closed") {
    val invalid = List(
      List("expansionBudget="),
      List("expansionBudget=0"),
      List("expansionBudget=-1"),
      List("expansionBudget=nope"),
      List("expansionBudget=999999999999999999"),
      List("expansionBudget=1", "expansionBudget=2")
    )
    assert(invalid.forall(options => ExpansionBudget.parse(options).isLeft))
  }
