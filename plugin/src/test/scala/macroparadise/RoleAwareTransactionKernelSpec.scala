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

  test("object transaction discovers the exact existing class opposite") {
    val fixture = parsedFixture()
    given Context = fixture.context

    val transaction = ObjectTransaction
      .discover(
        fixture.stats,
        fixture.objectPrimary,
        Vector("private-object-transform")
      )
      .fold(violation => fail(violation.render), identity)

    assert(transaction.currentPrimary eq fixture.objectPrimary)
    assert(
      transaction.currentOpposite.exists:
        case OppositeRole.ClassOpposite(value) => value eq fixture.objectClassOpposite
        case _                                 => false
    )
    assertEquals(transaction.targetId.originalKind, TargetKind.Object)
    assertEquals(transaction.targetId.originalName, "ObjectSubject")
  }

  test("object transaction represents no opposite without inventing a creation kind") {
    val fixture = parsedFixture()
    given Context = fixture.context

    val transaction = ObjectTransaction
      .discover(fixture.stats, fixture.loneObjectPrimary, Vector.empty)
      .fold(violation => fail(violation.render), identity)

    assert(transaction.currentPrimary eq fixture.loneObjectPrimary)
    assertEquals(transaction.currentOpposite, None)
  }

  test("object transaction preserves an existing trait as a trait opposite") {
    val fixture = parsedFixture()
    given Context = fixture.context

    val transaction = ObjectTransaction
      .discover(fixture.stats, fixture.objectTraitPrimary, Vector.empty)
      .fold(violation => fail(violation.render), identity)

    assert(
      transaction.currentOpposite.exists:
        case OppositeRole.TraitOpposite(value) => value eq fixture.objectTraitOpposite
        case _                                 => false
    )
  }

  test("object transaction rejects ambiguous same-name class and trait topology") {
    val fixture = parsedFixture()
    given Context = fixture.context

    assertEquals(
      ObjectTransaction
        .discover(fixture.stats, fixture.ambiguousObjectPrimary, Vector.empty)
        .left
        .toOption
        .map(_.category),
      Some(ViolationCategory.AmbiguousOppositeTopology)
    )
  }

  test("object no-op staging commits the exact original package-stat references in source order") {
    val fixture = parsedFixture()
    given Context = fixture.context
    val transaction = ObjectTransaction
      .discover(fixture.stats, fixture.objectPrimary, Vector("no-op"))
      .fold(violation => fail(violation.render), identity)

    val staged = transaction
      .stageValidatedOutput(
        RoleAwareExpansionResult(
          PrimaryRole.ObjectPrimary(fixture.objectPrimary),
          transaction.currentOpposite
        )
      )
      .fold(violation => fail(violation.render), identity)
    val committed = staged.commitPackageStats

    assertEquals(committed.size, fixture.stats.size)
    committed.zip(fixture.stats).foreach: (actual, original) =>
      assert(actual eq original)
  }

  test("object no-op staging preserves an existing trait opposite by exact identity") {
    val fixture = parsedFixture()
    given Context = fixture.context
    val transaction = ObjectTransaction
      .discover(fixture.stats, fixture.objectTraitPrimary, Vector("trait-no-op"))
      .fold(violation => fail(violation.render), identity)

    val committed = transaction
      .stageValidatedOutput(
        RoleAwareExpansionResult(
          PrimaryRole.ObjectPrimary(fixture.objectTraitPrimary),
          transaction.currentOpposite
        )
      )
      .fold(violation => fail(violation.render), identity)
      .commitPackageStats

    assert(
      committed(fixture.stats.indexWhere(_ eq fixture.objectTraitOpposite)) eq
        fixture.objectTraitOpposite
    )
    assert(
      committed(fixture.stats.indexWhere(_ eq fixture.objectTraitPrimary)) eq
        fixture.objectTraitPrimary
    )
  }

  test("object primary edit commits at the original occurrence and preserves unrelated references") {
    val fixture = parsedFixture()
    given Context = fixture.context
    val transaction = ObjectTransaction
      .discover(fixture.stats, fixture.loneObjectPrimary, Vector("primary-edit"))
      .fold(violation => fail(violation.render), identity)
    val rewrittenPrimary = copyModuleDef(fixture.loneObjectPrimary, "LoneObject")

    val committed = transaction
      .stageValidatedOutput(
        RoleAwareExpansionResult(
          PrimaryRole.ObjectPrimary(rewrittenPrimary),
          None
        )
      )
      .fold(violation => fail(violation.render), identity)
      .commitPackageStats
    val primaryIndex = fixture.stats.indexWhere(_ eq fixture.loneObjectPrimary)

    assert(committed(primaryIndex) eq rewrittenPrimary)
    committed.zip(fixture.stats).zipWithIndex.foreach:
      case ((actual, original), index) if index != primaryIndex =>
        assert(actual eq original)
      case _ => ()
  }

  test("object transaction stages bounded edits to existing class and trait opposites without changing kind") {
    val fixture = parsedFixture()
    given Context = fixture.context

    val classTransaction = ObjectTransaction
      .discover(fixture.stats, fixture.objectPrimary, Vector("class-edit"))
      .fold(violation => fail(violation.render), identity)
    val rewrittenClass = copyTypeDef(fixture.objectClassOpposite, "ObjectSubject")
    val classCommitted = classTransaction
      .stageValidatedOutput(
        RoleAwareExpansionResult(
          PrimaryRole.ObjectPrimary(fixture.objectPrimary),
          Some(OppositeRole.ClassOpposite(rewrittenClass))
        )
      )
      .fold(violation => fail(violation.render), identity)
      .commitPackageStats
    assert(
      classCommitted(fixture.stats.indexWhere(_ eq fixture.objectClassOpposite)) eq rewrittenClass
    )

    val traitTransaction = ObjectTransaction
      .discover(fixture.stats, fixture.objectTraitPrimary, Vector("trait-edit"))
      .fold(violation => fail(violation.render), identity)
    val rewrittenTrait = copyTypeDef(fixture.objectTraitOpposite, "ObjectTraitSubject")
    val traitCommitted = traitTransaction
      .stageValidatedOutput(
        RoleAwareExpansionResult(
          PrimaryRole.ObjectPrimary(fixture.objectTraitPrimary),
          Some(OppositeRole.TraitOpposite(rewrittenTrait))
        )
      )
      .fold(violation => fail(violation.render), identity)
      .commitPackageStats
    assert(
      traitCommitted(fixture.stats.indexWhere(_ eq fixture.objectTraitOpposite)) eq rewrittenTrait
    )
  }

  test("object staging rejects wrong primary name and primary kind replacement") {
    val fixture = parsedFixture()
    given Context = fixture.context
    val transaction = ObjectTransaction
      .discover(fixture.stats, fixture.loneObjectPrimary, Vector.empty)
      .fold(violation => fail(violation.render), identity)

    assertEquals(
      transaction
        .stageValidatedOutput(
          RoleAwareExpansionResult(
            PrimaryRole.ObjectPrimary(fixture.secondAdditional),
            None
          )
        )
        .left
        .toOption
        .map(_.category),
      Some(ViolationCategory.PrimaryNameMismatch)
    )
    assertEquals(
      transaction
        .stageValidatedOutput(
          RoleAwareExpansionResult(
            PrimaryRole.ClassPrimary(
              copyTypeDef(fixture.objectClassOpposite, "LoneObject")
            ),
            None
          )
        )
        .left
        .toOption
        .map(_.category),
      Some(ViolationCategory.PrimaryKindMismatch)
    )
  }

  test("object staging rejects opposite name, kind, creation, and removal changes") {
    val fixture = parsedFixture()
    given Context = fixture.context
    val transaction = ObjectTransaction
      .discover(fixture.stats, fixture.objectPrimary, Vector.empty)
      .fold(violation => fail(violation.render), identity)

    assertEquals(
      transaction
        .stageValidatedOutput(
          RoleAwareExpansionResult(
            PrimaryRole.ObjectPrimary(fixture.objectPrimary),
            Some(OppositeRole.ClassOpposite(fixture.wrongNameClass))
          )
        )
        .left
        .toOption
        .map(_.category),
      Some(ViolationCategory.OppositeNameMismatch)
    )
    assertEquals(
      transaction
        .stageValidatedOutput(
          RoleAwareExpansionResult(
            PrimaryRole.ObjectPrimary(fixture.objectPrimary),
            Some(
              OppositeRole.TraitOpposite(
                copyTypeDef(fixture.objectTraitOpposite, "ObjectSubject")
              )
            )
          )
        )
        .left
        .toOption
        .map(_.category),
      Some(ViolationCategory.OppositeKindMismatch)
    )
    assertEquals(
      transaction
        .stageValidatedOutput(
          RoleAwareExpansionResult(
            PrimaryRole.ObjectPrimary(fixture.objectPrimary),
            None
          )
        )
        .left
        .toOption
        .map(_.category),
      Some(ViolationCategory.OppositePresenceMismatch)
    )

    val noOpposite = ObjectTransaction
      .discover(fixture.stats, fixture.loneObjectPrimary, Vector.empty)
      .fold(violation => fail(violation.render), identity)
    assertEquals(
      noOpposite
        .stageValidatedOutput(
          RoleAwareExpansionResult(
            PrimaryRole.ObjectPrimary(fixture.loneObjectPrimary),
            Some(
              OppositeRole.ClassOpposite(
                copyTypeDef(fixture.objectClassOpposite, "LoneObject")
              )
            )
          )
        )
        .left
        .toOption
        .map(_.category),
      Some(ViolationCategory.OppositePresenceMismatch)
    )
  }

  test("object staging rejects a class opposite wrapper around a raw trait TypeDef") {
    val fixture = parsedFixture()
    given Context = fixture.context
    val transaction = ObjectTransaction
      .discover(fixture.stats, fixture.objectPrimary, Vector.empty)
      .fold(violation => fail(violation.render), identity)
    val counterfeitClassRole =
      OppositeRole.ClassOpposite(
        copyTypeDef(fixture.objectTraitOpposite, "ObjectSubject")
      )

    assertEquals(
      transaction
        .stageValidatedOutput(
          RoleAwareExpansionResult(
            PrimaryRole.ObjectPrimary(fixture.objectPrimary),
            Some(counterfeitClassRole)
          )
        )
        .left
        .toOption
        .map(_.category),
      Some(ViolationCategory.OppositeKindMismatch)
    )
  }

  test("object rollback after staged primary and opposite edits restores exact original references") {
    val fixture = parsedFixture()
    given Context = fixture.context
    val transaction = ObjectTransaction
      .discover(fixture.stats, fixture.objectPrimary, Vector("late-failure"))
      .fold(violation => fail(violation.render), identity)
    val staged = transaction
      .stageValidatedOutput(
        RoleAwareExpansionResult(
          PrimaryRole.ObjectPrimary(
            copyModuleDef(fixture.objectPrimary, "ObjectSubject")
          ),
          Some(
            OppositeRole.ClassOpposite(
              copyTypeDef(fixture.objectClassOpposite, "ObjectSubject")
            )
          )
        )
      )
      .fold(violation => fail(violation.render), identity)

    val rollback = staged.rollback
    assert(rollback.packageStats.asInstanceOf[AnyRef] eq fixture.stats.asInstanceOf[AnyRef])
    assert(rollback.primary.tree eq fixture.objectPrimary)
    assert(rollback.opposite.exists(_.tree eq fixture.objectClassOpposite))
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
      loneObjectPrimary: ModuleDef,
      objectTraitPrimary: ModuleDef,
      objectTraitOpposite: TypeDef,
      ambiguousObjectPrimary: ModuleDef,
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
        |object LoneObject
        |trait ObjectTraitSubject
        |object ObjectTraitSubject
        |class AmbiguousObject
        |trait AmbiguousObject
        |object AmbiguousObject
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
      loneObjectPrimary = moduleDef(stats, "LoneObject"),
      objectTraitPrimary = moduleDef(stats, "ObjectTraitSubject"),
      objectTraitOpposite = typeDef(stats, "ObjectTraitSubject"),
      ambiguousObjectPrimary = moduleDef(stats, "AmbiguousObject"),
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
