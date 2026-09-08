class ExampleSpec extends munit.FunSuite:
  test("built-in class companion and sibling expansion remains visible in the same run") {
    assertEquals(Example.directResult, "hello A")
    assertEquals(Example.companionResult, "hello A")
    assertEquals(Example.siblingMeta.getClass.getSimpleName, "UserMeta")
  }

  test("debug and constructor-parameter forms remain supported") {
    assertEquals(DebugExample.directResult, "DebugUser")
    assertEquals(ConstructorParamExample.result, "hello B")
  }

  test("existing companions are merged and user conflicts are preserved") {
    assertEquals(multi.MultipleAnnotatedExample.existingValue, 42)
    assertEquals(multi.MultipleAnnotatedExample.userHello, "hello A")
    assertEquals(multi.MultipleAnnotatedExample.orderHello, "hello B")
    assertEquals(factoryconflict.FactoryConflictExample.result, "hello A!")
  }

  test("one external handler uses the same scheduler path") {
    assertEquals(ExternalMarkerExample.directResult, "ExternalMarked")
  }

  test("unannotated definitions remain untouched") {
    assert(!UnannotatedExample.methodNames.contains("generatedHello"))
    assert(!UnannotatedExample.siblingClassExists)
  }
