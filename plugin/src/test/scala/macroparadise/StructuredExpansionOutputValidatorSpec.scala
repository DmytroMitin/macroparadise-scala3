package macroparadise

import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Contexts.{Context, ContextBase}
import dotty.tools.dotc.core.Names.*
import dotty.tools.dotc.util.SourceFile
import paradise3.api.StructuredExpansionOutput

class StructuredExpansionOutputValidatorSpec extends munit.FunSuite:
  private given Context = ContextBase().initialCtx
  private val source =
    SourceFile.virtual("StructuredExpansionOutputValidatorSpec.scala", "")

  private def typeDef(name: String): TypeDef =
    given SourceFile = source
    TypeDef(typeName(name), EmptyTree)

  private def moduleDef(name: String): ModuleDef =
    given SourceFile = source
    ModuleDef(
      termName(name),
      Template(emptyConstructor, Nil, Nil, EmptyValDef, Nil)
    )

  private def unknownTree: untpd.Tree =
    given SourceFile = source
    Literal(Constant(1))

  private def validate(
      output: StructuredExpansionOutput,
      knownTopLevelNames: Set[String] = Set("Primary")
  ): Either[StructuredExpansionOutputValidator.Violation, List[untpd.Tree]] =
    StructuredExpansionOutputValidator.validate(
      StructuredExpansionOutputValidator.Input(
        currentPrimary = typeDef("Primary"),
        knownTopLevelNames = knownTopLevelNames,
        output = output
      )
    )

  private def category(
      output: StructuredExpansionOutput,
      knownTopLevelNames: Set[String] = Set("Primary")
  ): Option[String] =
    validate(output, knownTopLevelNames).left.toOption.map(_.category)

  private def canonical(
      output: StructuredExpansionOutput,
      knownTopLevelNames: Set[String] = Set("Primary")
  ): List[untpd.Tree] =
    validate(output, knownTopLevelNames) match
      case Right(trees) => trees
      case Left(violation) => fail(s"unexpected ${violation.category}: ${violation.actual}")

  test("accepts primary only") {
    val primary = typeDef("Primary")
    assertEquals(
      canonical(StructuredExpansionOutput(primary, None, Nil)),
      List(primary)
    )
  }

  test("accepts primary plus same-name companion") {
    val primary = typeDef("Primary")
    val companion = moduleDef("Primary")
    assertEquals(
      canonical(StructuredExpansionOutput(primary, Some(companion), Nil)),
      List(primary, companion)
    )
  }

  test("accepts primary plus one named sibling") {
    val primary = typeDef("Primary")
    val sibling = typeDef("Sibling")
    assertEquals(
      canonical(StructuredExpansionOutput(primary, None, List(sibling))),
      List(primary, sibling)
    )
  }

  test("accepts primary companion and multiple named additional definitions") {
    val primary = typeDef("Primary")
    val companion = moduleDef("Primary")
    val additionalClass = typeDef("AdditionalClass")
    val additionalObject = moduleDef("AdditionalObject")
    assertEquals(
      canonical(
        StructuredExpansionOutput(
          primary,
          Some(companion),
          List(additionalClass, additionalObject)
        )
      ),
      List(primary, companion, additionalClass, additionalObject)
    )
  }

  test("canonical order follows roles rather than construction order") {
    val secondAdditional = moduleDef("SecondAdditional")
    val companion = moduleDef("Primary")
    val firstAdditional = typeDef("FirstAdditional")
    val primary = typeDef("Primary")
    val trees =
      canonical(
        StructuredExpansionOutput(
          primary,
          Some(companion),
          List(firstAdditional, secondAdditional)
        )
      )
    assertEquals(trees, List(primary, companion, firstAdditional, secondAdditional))
  }

  test("preserves caller order among additional definitions") {
    val primary = typeDef("Primary")
    val first = moduleDef("Zed")
    val second = typeDef("Alpha")
    assertEquals(
      canonical(StructuredExpansionOutput(primary, None, List(first, second))),
      List(primary, first, second)
    )
  }

  test("accepts a rebuilt same-name primary tree") {
    val rebuiltPrimary = typeDef("Primary")
    val trees =
      canonical(StructuredExpansionOutput(rebuiltPrimary, None, Nil))
    assert(trees.head eq rebuiltPrimary)
  }

  test("canonical output passes the raw validator as defense in depth") {
    val primary = typeDef("Primary")
    val companion = moduleDef("Primary")
    val sibling = typeDef("Sibling")
    val trees =
      canonical(
        StructuredExpansionOutput(primary, Some(companion), List(sibling))
      )
    assertEquals(
      RawExpansionOutputValidator.validate(
        RawExpansionOutputValidator.Input(
          currentPrimary = typeDef("Primary"),
          knownTopLevelNames = Set("Primary"),
          trees = trees
        )
      ),
      None
    )
  }

  test("rejects a null structured output object") {
    assertEquals(category(null), Some("NULL_OUTPUT"))
  }

  test("rejects a null primary") {
    assertEquals(
      category(StructuredExpansionOutput(null, None, Nil)),
      Some("NULL_PRIMARY")
    )
  }

  test("rejects a wrong-name primary") {
    assertEquals(
      category(StructuredExpansionOutput(typeDef("Wrong"), None, Nil)),
      Some("PRIMARY_NAME_MISMATCH")
    )
  }

  test("rejects a null companion Option container") {
    assertEquals(
      category(StructuredExpansionOutput(typeDef("Primary"), null, Nil)),
      Some("NULL_COMPANION_OPTION")
    )
  }

  test("rejects Some(null) companion") {
    assertEquals(
      category(
        StructuredExpansionOutput(
          typeDef("Primary"),
          Some(null.asInstanceOf[ModuleDef]),
          Nil
        )
      ),
      Some("NULL_COMPANION")
    )
  }

  test("rejects a wrong-name companion") {
    assertEquals(
      category(
        StructuredExpansionOutput(
          typeDef("Primary"),
          Some(moduleDef("Wrong")),
          Nil
        )
      ),
      Some("COMPANION_NAME_MISMATCH")
    )
  }

  test("rejects a null additional list") {
    assertEquals(
      category(StructuredExpansionOutput(typeDef("Primary"), None, null)),
      Some("NULL_ADDITIONAL_LIST")
    )
  }

  test("rejects a null additional element") {
    assertEquals(
      category(
        StructuredExpansionOutput(
          typeDef("Primary"),
          None,
          List(null.asInstanceOf[untpd.Tree])
        )
      ),
      Some("NULL_ADDITIONAL_ELEMENT")
    )
  }

  test("rejects an unknown additional raw tree kind") {
    assertEquals(
      category(
        StructuredExpansionOutput(
          typeDef("Primary"),
          None,
          List(unknownTree)
        )
      ),
      Some("UNSUPPORTED_ADDITIONAL_TREE_KIND")
    )
  }

  test("rejects an additional same-name TypeDef") {
    assertEquals(
      category(
        StructuredExpansionOutput(
          typeDef("Primary"),
          None,
          List(typeDef("Primary"))
        )
      ),
      Some("ADDITIONAL_PRIMARY_ROLE")
    )
  }

  test("rejects an additional same-name ModuleDef") {
    assertEquals(
      category(
        StructuredExpansionOutput(
          typeDef("Primary"),
          None,
          List(moduleDef("Primary"))
        )
      ),
      Some("ADDITIONAL_COMPANION_ROLE")
    )
  }

  test("rejects duplicate additional name across class and object forms") {
    assertEquals(
      category(
        StructuredExpansionOutput(
          typeDef("Primary"),
          None,
          List(typeDef("Repeated"), moduleDef("Repeated"))
        )
      ),
      Some("DUPLICATE_ADDITIONAL_NAME")
    )
  }

  test("rejects an additional name conflicting with known top-level input") {
    assertEquals(
      category(
        StructuredExpansionOutput(
          typeDef("Primary"),
          None,
          List(typeDef("KnownConflict"))
        ),
        knownTopLevelNames = Set("Primary", "KnownConflict")
      ),
      Some("TOP_LEVEL_NAME_CONFLICT")
    )
  }
