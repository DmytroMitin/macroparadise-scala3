package macroparadise

import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Contexts.{Context, ContextBase}
import dotty.tools.dotc.core.Names.*
import dotty.tools.dotc.parsing.Parsers

class RoleAwareTransactionKernelSpec extends munit.FunSuite:
  import RoleAwareTransactionKernel.*

  test("legacy profile mapping keeps target kind separate from all five shape profiles") {
    val expected = List(
      LegacyShapeProfile.CommonClassOnly -> Set(TargetKind.Class),
      LegacyShapeProfile.PlainZeroParameterTrait -> Set(TargetKind.Trait),
      LegacyShapeProfile.RestrictedGenericTraitApply -> Set(TargetKind.Trait),
      LegacyShapeProfile.TwoUpperBoundedGenericTrait -> Set(TargetKind.Trait),
      LegacyShapeProfile.RestrictedOrTwoUpperBoundedGenericTrait -> Set(TargetKind.Trait)
    )

    expected.foreach: (profile, kinds) =>
      assertEquals(LegacyAdmission.forProfile(profile).kinds, kinds)
      assertEquals(LegacyAdmission.forProfile(profile).shape, profile)
  }

  test("class transaction projects exact legacy primary and opted-in object companion") {
    val fixture = parsedFixture()
    given Context = fixture.context
    val transaction = started(
      fixture.stats,
      PrimaryRole.fromLegacyTypeDef(fixture.classPrimary).toOption.get,
      Some(OppositeRole.ObjectOpposite(fixture.classCompanion)),
      Vector("first", "second")
    )

    val projected = transaction.legacyProjection(consumesExistingCompanion = true).toOption.get
    assert(projected.primary eq fixture.classPrimary)
    assert(projected.companion.exists(_ eq fixture.classCompanion))
    assertEquals(transaction.snapshot.sourceParticipants, Vector("first", "second"))
  }

  test("trait transaction projects exact legacy primary without leaking an unrequested companion") {
    val fixture = parsedFixture()
    given Context = fixture.context
    val transaction = started(
      fixture.stats,
      PrimaryRole.fromLegacyTypeDef(fixture.traitPrimary).toOption.get,
      Some(OppositeRole.ObjectOpposite(fixture.traitCompanion)),
      Vector("trait")
    )

    val projected = transaction.legacyProjection(consumesExistingCompanion = false).toOption.get
    assert(projected.primary eq fixture.traitPrimary)
    assertEquals(projected.companion, None)
    assertEquals(transaction.currentPrimary.kind, TargetKind.Trait)
  }

  test("validated legacy staging preserves role and target identity while retaining additional order") {
    val fixture = parsedFixture()
    given Context = fixture.context
    val transaction = started(
      fixture.stats,
      PrimaryRole.fromLegacyTypeDef(fixture.classPrimary).toOption.get,
      Some(OppositeRole.ObjectOpposite(fixture.classCompanion)),
      Vector("first")
    )

    val staged = transaction.stageValidatedLegacy(
      List(
        fixture.rewrittenClass,
        fixture.rewrittenClassCompanion,
        fixture.firstAdditional,
        fixture.secondAdditional
      )
    ).toOption.get

    assertEquals(staged.targetId, transaction.targetId)
    assert(staged.currentPrimary.legacyTypeDefOption.exists(_ eq fixture.rewrittenClass))
    assert(staged.currentOpposite.exists(_.tree eq fixture.rewrittenClassCompanion))
    assertEquals(
      staged.additionalTopLevelTrees,
      List(fixture.firstAdditional, fixture.secondAdditional)
    )
    assertEquals(
      staged.outputTrees,
      List(
        fixture.rewrittenClass,
        fixture.rewrittenClassCompanion,
        fixture.firstAdditional,
        fixture.secondAdditional
      )
    )
  }

  test("legacy opposite omission is explicit for complete versus incremental output") {
    val fixture = parsedFixture()
    given Context = fixture.context
    val transaction = started(
      fixture.stats,
      PrimaryRole.fromLegacyTypeDef(fixture.classPrimary).toOption.get,
      Some(OppositeRole.ObjectOpposite(fixture.classCompanion)),
      Vector("legacy")
    )

    val complete = transaction.stageValidatedLegacy(
      List(fixture.rewrittenClass),
      LegacyOppositeOmissionPolicy.DropCurrent
    ).toOption.get
    val incremental = transaction.stageValidatedLegacy(
      List(fixture.rewrittenClass),
      LegacyOppositeOmissionPolicy.RetainCurrent
    ).toOption.get

    assertEquals(complete.currentOpposite, None)
    assert(incremental.currentOpposite.exists(_.tree eq fixture.classCompanion))
  }

  test("wrong primary name and wrong primary role fail before transaction staging") {
    val fixture = parsedFixture()
    given Context = fixture.context
    val classTransaction = started(
      fixture.stats,
      PrimaryRole.fromLegacyTypeDef(fixture.classPrimary).toOption.get,
      Some(OppositeRole.ObjectOpposite(fixture.classCompanion)),
      Vector.empty
    )

    assertEquals(
      classTransaction.stageValidatedLegacy(List(fixture.wrongNameClass)).left.toOption.map(_.category),
      Some(ViolationCategory.PrimaryNameMismatch)
    )
    assertEquals(
      classTransaction.stageValidatedLegacy(List(fixture.wrongKindTrait)).left.toOption.map(_.category),
      Some(ViolationCategory.PrimaryKindMismatch)
    )
  }

  test("object role is representable but cannot project the legacy TypeDef input") {
    val fixture = parsedFixture()
    given Context = fixture.context
    val transaction = started(
      fixture.stats,
      PrimaryRole.ObjectPrimary(fixture.objectPrimary),
      Some(OppositeRole.ClassOpposite(fixture.objectClassOpposite)),
      Vector("legacy-name-match")
    )

    assertEquals(transaction.currentPrimary.kind, TargetKind.Object)
    assertEquals(
      transaction.legacyProjection(consumesExistingCompanion = true).left.toOption.map(_.category),
      Some(ViolationCategory.LegacyObjectPrimaryUnsupported)
    )
  }

  test("legacy transaction adapter owns validated staging and refuses an object snapshot") {
    val fixture = parsedFixture()
    given Context = fixture.context
    val classSnapshot = TransactionSnapshot
      .capture(
        fixture.stats,
        PrimaryRole.fromLegacyTypeDef(fixture.classPrimary).toOption.get,
        Some(OppositeRole.ObjectOpposite(fixture.classCompanion)),
        Vector("legacy")
      )
      .toOption.get
    val legacy = LegacyTransaction.start(classSnapshot).toOption.get
    val staged = legacy.stageValidatedOutput(
      List(fixture.rewrittenClass, fixture.rewrittenClassCompanion)
    ).toOption.get

    assert(legacy.currentPrimary eq fixture.classPrimary)
    assert(legacy.currentCompanion.exists(_ eq fixture.classCompanion))
    assert(staged.currentPrimary eq fixture.rewrittenClass)
    assert(staged.currentCompanion.exists(_ eq fixture.rewrittenClassCompanion))

    val objectSnapshot = TransactionSnapshot
      .capture(
        fixture.stats,
        PrimaryRole.ObjectPrimary(fixture.objectPrimary),
        Some(OppositeRole.ClassOpposite(fixture.objectClassOpposite)),
        Vector("legacy-name-match")
      )
      .toOption.get
    assertEquals(
      LegacyTransaction.start(objectSnapshot).left.toOption.map(_.category),
      Some(ViolationCategory.LegacyObjectPrimaryUnsupported)
    )
  }

  test("rollback returns the exact original package-stat list and role references") {
    val fixture = parsedFixture()
    given Context = fixture.context
    val transaction = started(
      fixture.stats,
      PrimaryRole.fromLegacyTypeDef(fixture.classPrimary).toOption.get,
      Some(OppositeRole.ObjectOpposite(fixture.classCompanion)),
      Vector("first")
    )
    val staged = transaction.stageValidatedLegacy(
      List(fixture.rewrittenClass, fixture.rewrittenClassCompanion)
    ).toOption.get

    val rollback = staged.rollback
    assert(rollback.packageStats.asInstanceOf[AnyRef] eq fixture.stats.asInstanceOf[AnyRef])
    assert(rollback.primary.tree eq fixture.classPrimary)
    assert(rollback.opposite.exists(_.tree eq fixture.classCompanion))
  }

  private def started(
      stats: List[Tree],
      primary: PrimaryRole,
      opposite: Option[OppositeRole],
      sourceParticipants: Vector[String]
  )(using Context): Transaction =
    val snapshot = TransactionSnapshot
      .capture(stats, primary, opposite, sourceParticipants)
      .fold(violation => fail(violation.render), identity)
    Transaction.start(snapshot)

  private final case class Fixture(
      stats: List[Tree],
      classPrimary: TypeDef,
      classCompanion: ModuleDef,
      traitPrimary: TypeDef,
      traitCompanion: ModuleDef,
      objectPrimary: ModuleDef,
      objectClassOpposite: TypeDef,
      rewrittenClass: TypeDef,
      rewrittenClassCompanion: ModuleDef,
      wrongNameClass: TypeDef,
      wrongKindTrait: TypeDef,
      firstAdditional: TypeDef,
      secondAdditional: ModuleDef,
      context: Context
  )

  private def parsedFixture(): Fixture =
    val source =
      """class Subject
        |object Subject
        |trait TraitSubject
        |object TraitSubject
        |object ObjectSubject
        |class ObjectSubject
        |class RewrittenSubject
        |object RewrittenSubject
        |class WrongName
        |class FirstAdditional
        |object SecondAdditional
        |""".stripMargin
    val unit = CompilationUnit("RoleAwareTransactionKernelSpec.scala", source)
    val context = ContextBase().initialCtx.fresh.setCompilationUnit(unit)
    val parsed = new Parsers.Parser(unit.source)(using context).parse()
    val stats = parsed match
      case PackageDef(_, values) => values
      case tree                  => List(tree)
    given Context = context
    Fixture(
      stats = stats,
      classPrimary = typeDef(stats, "Subject"),
      classCompanion = moduleDef(stats, "Subject"),
      traitPrimary = typeDef(stats, "TraitSubject"),
      traitCompanion = moduleDef(stats, "TraitSubject"),
      objectPrimary = moduleDef(stats, "ObjectSubject"),
      objectClassOpposite = typeDef(stats, "ObjectSubject"),
      rewrittenClass = copyTypeDef(typeDef(stats, "RewrittenSubject"), "Subject"),
      rewrittenClassCompanion = copyModuleDef(moduleDef(stats, "RewrittenSubject"), "Subject"),
      wrongNameClass = typeDef(stats, "WrongName"),
      wrongKindTrait = copyTypeDef(typeDef(stats, "TraitSubject"), "Subject"),
      firstAdditional = typeDef(stats, "FirstAdditional"),
      secondAdditional = moduleDef(stats, "SecondAdditional"),
      context = context
    )

  private def typeDef(stats: List[Tree], name: String): TypeDef =
    stats.collectFirst:
      case value: TypeDef if value.name.toString == name => value
    .getOrElse(fail(s"missing TypeDef $name"))

  private def moduleDef(stats: List[Tree], name: String): ModuleDef =
    stats.collectFirst:
      case value: ModuleDef if value.name.toString == name => value
    .getOrElse(fail(s"missing ModuleDef $name"))

  private def copyTypeDef(value: TypeDef, name: String)(using Context): TypeDef =
    cpy.TypeDef(value)(typeName(name), value.rhs)

  private def copyModuleDef(value: ModuleDef, name: String)(using Context): ModuleDef =
    cpy.ModuleDef(value)(termName(name), value.impl)
