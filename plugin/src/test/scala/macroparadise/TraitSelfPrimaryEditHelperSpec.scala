package macroparadise

import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.ast.Trees
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Contexts.{Context, ContextBase}
import dotty.tools.dotc.core.Names.termName
import dotty.tools.dotc.parsing.Parsers
import paradise3.api.{ExpansionInput, ExpansionOutcome, StructuredExpansionOutput}
import paradise3.api.helpers.{ExpansionHelpers, SelfAliasOrigin}

class TraitSelfPrimaryEditHelperSpec extends munit.FunSuite:
  test("anonymous self preparation selects the exact installed alias and prepends the exact supplied Self member") {
    val fixture = parsedFixture(
      """trait Nat:
        |  type Existing = String
        |  val preserved: Int = 1
        |""".stripMargin
    )
    given Context = fixture.context
    val originalTemplate = fixture.primary.rhs.asInstanceOf[Template]
    val originalBody = originalTemplate.body
    var callbackCalls = 0
    var callbackAlias = ""

    val output = structured:
      ExpansionHelpers.addPreparedSelfTypeToTrait(fixture.input): preparation =>
        callbackCalls += 1
        callbackAlias = preparation.selfAliasName
        assertEquals(preparation.selfAliasOrigin, SelfAliasOrigin.Generated)
        assert(preparation.pos.span.exists)
        fixture.generatedSelf

    val rewrittenTemplate = output.primary.rhs.asInstanceOf[Template]
    assertEquals(callbackCalls, 1)
    assertEquals(callbackAlias, "self")
    assertEquals(rewrittenTemplate.self.name.toString, callbackAlias)
    assert(rewrittenTemplate.body.head.eq(fixture.generatedSelf), clue(rewrittenTemplate.body))
    assert(
      rewrittenTemplate.body.tail.zip(originalBody).forall:
        case (actual, expected) => actual.eq(expected)
    )
    assert(rewrittenTemplate.constr.eq(originalTemplate.constr), clue(rewrittenTemplate.constr))
    assertEquals(rewrittenTemplate.parentsOrDerived, originalTemplate.parentsOrDerived)
    assertEquals(rewrittenTemplate.derived, originalTemplate.derived)
    assertEquals(Trees.mods(output.primary).flags, Trees.mods(fixture.primary).flags)
    assertEquals(output.companion, None)
    assertEquals(output.additionalTopLevelDefinitions, Nil)
  }

  test("fresh alias selection uses only the direct term namespace with stable suffix sequencing") {
    val fixture = parsedFixture(
      """trait Nat:
        |  val self: Int = 1
        |  def self$1: Int = 2
        |  type self = String
        |""".stripMargin
    )
    given Context = fixture.context
    var callbackAlias = ""

    val output = structured:
      ExpansionHelpers.addPreparedSelfTypeToTrait(fixture.input): preparation =>
        callbackAlias = preparation.selfAliasName
        fixture.generatedSelf

    val rewrittenSelf = output.primary.rhs.asInstanceOf[Template].self
    assertEquals(callbackAlias, "self$2")
    assertEquals(rewrittenSelf.name.toString, "self$2")
  }

  test("an existing typed named self is preserved by exact identity and exposed before lowering") {
    val fixture = parsedFixture(
      """trait Nat:
        |  stable: SomeParent =>
        |  type Existing = String
        |""".stripMargin
    )
    given Context = fixture.context
    val originalTemplate = fixture.primary.rhs.asInstanceOf[Template]
    val originalSelf = originalTemplate.self
    val originalSelfType = originalSelf.tpt
    var callbackCalls = 0

    val output = structured:
      ExpansionHelpers.addPreparedSelfTypeToTrait(fixture.input): preparation =>
        callbackCalls += 1
        assertEquals(preparation.selfAliasName, "stable")
        assertEquals(preparation.selfAliasOrigin, SelfAliasOrigin.ExistingNamed)
        assertEquals(preparation.pos, originalSelf.sourcePos)
        fixture.generatedSelf

    val rewrittenSelf = output.primary.rhs.asInstanceOf[Template].self
    assertEquals(callbackCalls, 1)
    assert(rewrittenSelf.eq(originalSelf), clue(rewrittenSelf))
    assert(rewrittenSelf.tpt.eq(originalSelfType), clue(rewrittenSelf.tpt))
  }

  test("direct Self TypeDef rejects at its own position before callback invocation") {
    val fixture = parsedFixture(
      """trait Nat:
        |  type Self = String
        |  val preserved: Int = 1
        |""".stripMargin
    )
    given Context = fixture.context
    val directSelf = fixture.primary.rhs.asInstanceOf[Template].body.collectFirst:
      case value: TypeDef if value.name.toString == "Self" => value
    .getOrElse(fail("missing direct Self"))
    var callbackCalls = 0

    ExpansionHelpers.addPreparedSelfTypeToTrait(fixture.input): _ =>
      callbackCalls += 1
      fixture.generatedSelf
    match
      case ExpansionOutcome.Rejected(diagnostics, fallback) =>
        assertEquals(callbackCalls, 0)
        assert(fallback.eq(fixture.primary), clue(fallback))
        assertEquals(diagnostics.size, 1)
        assertEquals(diagnostics.head.pos, directSelf.sourcePos)
        assert(diagnostics.head.message.contains("direct type member `Self`"), diagnostics.head.message)
      case other => fail(s"expected Rejected, found $other")
  }

  test("nested Self and direct term Self are not direct type-namespace conflicts") {
    val definitions = List(
      """trait Nat:
        |  object Nested:
        |    type Self = String
        |""".stripMargin,
      """trait Nat:
        |  val Self: Int = 1
        |""".stripMargin
    )

    definitions.foreach: definition =>
      val fixture = parsedFixture(definition)
      given Context = fixture.context
      var callbackCalls = 0
      val output = structured:
        ExpansionHelpers.addPreparedSelfTypeToTrait(fixture.input): _ =>
          callbackCalls += 1
          fixture.generatedSelf
      assertEquals(callbackCalls, 1)
      assert(output.primary.rhs.asInstanceOf[Template].body.head.eq(fixture.generatedSelf))
  }

  test("success strips only the current annotation and preserves later annotation identities and order") {
    val fixture = parsedFixture("trait Nat")
    given Context = fixture.context
    val originalAnnotations = Trees.mods(fixture.primary).annotations

    val output = structured:
      ExpansionHelpers.addPreparedSelfTypeToTrait(fixture.input)(_ => fixture.generatedSelf)

    val remaining = Trees.mods(output.primary).annotations
    assertEquals(originalAnnotations.size, 3)
    assert(!remaining.exists(_ eq fixture.currentAnnotation))
    assertEquals(remaining.size, 2)
    assert(
      remaining.zip(originalAnnotations.tail).forall:
        case (actual, expected) => actual.eq(expected)
    )
  }

  test("wrong-named and null generated members reject atomically with the original fallback") {
    val fixture = parsedFixture("trait Nat:\n  type Existing = String")
    given Context = fixture.context
    val originalTemplate = fixture.primary.rhs

    List(
      "wrong name" -> fixture.generatedOther,
      "null member" -> null.asInstanceOf[TypeDef]
    ).foreach: (label, generated) =>
      var callbackCalls = 0
      ExpansionHelpers.addPreparedSelfTypeToTrait(fixture.input): _ =>
        callbackCalls += 1
        generated
      match
        case ExpansionOutcome.Rejected(diagnostics, fallback) =>
          assertEquals(callbackCalls, 1, label)
          assertEquals(diagnostics.size, 1, label)
          assert(fallback.eq(fixture.primary), label)
          assert(fixture.primary.rhs.eq(originalTemplate), label)
        case other => fail(s"expected Rejected for $label, found $other")
  }

  test("malformed nonempty anonymous self rejects before callback with no partial edit") {
    val fixture = parsedFixture("trait Nat:\n  type Existing = String")
    given Context = fixture.context
    val originalTemplate = fixture.primary.rhs.asInstanceOf[Template]
    val malformedSelf =
      cpy.ValDef(originalTemplate.self)(termName("_"), TypeTree(), originalTemplate.self.rhs)
    val malformedTemplate =
      cpy.Template(originalTemplate)(
        originalTemplate.constr,
        originalTemplate.parentsOrDerived,
        originalTemplate.derived,
        malformedSelf,
        originalTemplate.body
      )
    val malformedPrimary = cpy.TypeDef(fixture.primary)(fixture.primary.name, malformedTemplate)
    var callbackCalls = 0

    ExpansionHelpers.addPreparedSelfTypeToTrait(fixture.input.copy(annotatedClass = malformedPrimary)): _ =>
      callbackCalls += 1
      fixture.generatedSelf
    match
      case ExpansionOutcome.Rejected(diagnostics, fallback) =>
        assertEquals(callbackCalls, 0)
        assertEquals(diagnostics.size, 1)
        assert(fallback.eq(malformedPrimary), clue(fallback))
        assert(malformedPrimary.rhs.eq(malformedTemplate))
      case other => fail(s"expected Rejected, found $other")
  }

  test("unsupported target shapes reject before callback invocation") {
    List(
      "class Nat",
      "sealed trait Nat",
      "trait Nat[A]",
      "trait Nat(val value: Int)",
      "enum Nat:\n  case Zero"
    ).foreach: definition =>
      val fixture = parsedFixture(definition)
      given Context = fixture.context
      var callbackCalls = 0
      ExpansionHelpers.addPreparedSelfTypeToTrait(fixture.input): _ =>
        callbackCalls += 1
        fixture.generatedSelf
      match
        case ExpansionOutcome.Rejected(diagnostics, fallback) =>
          assertEquals(callbackCalls, 0, definition)
          assertEquals(diagnostics.size, 1, definition)
          assert(fallback.eq(fixture.primary), definition)
        case other => fail(s"expected Rejected for $definition, found $other")
  }

  test("callback failures propagate without publishing a partially rewritten primary") {
    val fixture = parsedFixture("trait Nat:\n  type Existing = String")
    given Context = fixture.context
    val originalTemplate = fixture.primary.rhs

    val error = intercept[IllegalStateException]:
      ExpansionHelpers.addPreparedSelfTypeToTrait(fixture.input): _ =>
        throw new IllegalStateException("lowering failed")

    assertEquals(error.getMessage, "lowering failed")
    assert(fixture.primary.rhs.eq(originalTemplate))
  }

  private final case class Fixture(
      primary: TypeDef,
      generatedSelf: TypeDef,
      generatedOther: TypeDef,
      currentAnnotation: Tree,
      context: Context
  ):
    def input: ExpansionInput =
      ExpansionInput(
        "current",
        primary,
        None,
        Set(primary.name.toString, "GeneratedTypes"),
        Some(currentAnnotation)
      )

  private def parsedFixture(primaryDefinition: String): Fixture =
    val source =
      s"""@current @later @unhandled
         |$primaryDefinition
         |object GeneratedTypes:
         |  type Self = String
         |  type Other = String
         |""".stripMargin
    val unit = CompilationUnit("TraitSelfPrimaryEditHelperSpec.scala", source)
    val context = ContextBase().initialCtx.fresh.setCompilationUnit(unit)
    val parsed = new Parsers.Parser(unit.source)(using context).parse()
    val stats = parsed match
      case PackageDef(_, values) => values
      case tree => List(tree)
    val primary = stats.collectFirst:
      case value: TypeDef if value.name.toString != "GeneratedTypes" => value
    .getOrElse(fail(s"missing primary TypeDef in $stats"))
    val generatedOwner = stats.collectFirst:
      case value: ModuleDef if value.name.toString == "GeneratedTypes" => value
    .getOrElse(fail(s"missing GeneratedTypes in $stats"))
    val generated = generatedOwner.impl.body(using context).collect:
      case value: TypeDef => value.name.toString -> value
    .toMap
    val currentAnnotation = Trees.mods(primary).annotations.headOption
      .getOrElse(fail("missing current annotation"))
    Fixture(
      primary,
      generated.getOrElse("Self", fail(s"missing generated Self in $generated")),
      generated.getOrElse("Other", fail(s"missing generated Other in $generated")),
      currentAnnotation,
      context
    )

  private def structured(outcome: ExpansionOutcome): StructuredExpansionOutput =
    outcome match
      case ExpansionOutcome.Structured(output) => output
      case other => fail(s"expected Structured, found $other")
