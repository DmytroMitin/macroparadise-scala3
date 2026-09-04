package macroparadise

import dotty.tools.dotc.ast.Trees
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags.{Enum, Trait}

private[macroparadise] object RoleAwareTransactionKernel:
  enum TargetKind:
    case Class, Trait, Object

  enum LegacyShapeProfile:
    case CommonClassOnly
    case RestrictedGenericTraitApply
    case TwoUpperBoundedGenericTrait
    case PlainZeroParameterTrait
    case RestrictedOrTwoUpperBoundedGenericTrait

  final case class LegacyAdmission(
      kinds: Set[TargetKind],
      shape: LegacyShapeProfile
  )

  object LegacyAdmission:
    def forProfile(profile: LegacyShapeProfile): LegacyAdmission =
      val kinds = profile match
        case LegacyShapeProfile.CommonClassOnly => Set(TargetKind.Class)
        case _                                  => Set(TargetKind.Trait)
      LegacyAdmission(kinds, profile)

  enum ViolationCategory:
    case UnsupportedPrimaryRole
    case PrimaryOccurrenceMissing
    case OppositeOccurrenceMissing
    case IllegalRolePair
    case PrimaryNameMismatch
    case PrimaryKindMismatch
    case OppositeNameMismatch
    case OppositeKindMismatch
    case OppositePresenceMismatch
    case MissingOppositeIntent
    case InvalidOppositeCreationKind
    case InvalidOppositePlacement
    case OppositeAlreadyExists
    case AmbiguousOppositeTopology
    case LegacyObjectPrimaryUnsupported
    case MissingLegacyPrimary

  enum LegacyOppositeOmissionPolicy:
    case RetainCurrent, DropCurrent

  final case class Violation(
      category: ViolationCategory,
      detail: String
  ):
    def render: String = s"category=$category detail=$detail"

  enum PrimaryRole:
    case ClassPrimary(value: TypeDef)
    case TraitPrimary(value: TypeDef)
    case ObjectPrimary(value: ModuleDef)

    def kind: TargetKind = this match
      case ClassPrimary(_)  => TargetKind.Class
      case TraitPrimary(_)  => TargetKind.Trait
      case ObjectPrimary(_) => TargetKind.Object

    def name: String = this match
      case ClassPrimary(value)  => value.name.toString
      case TraitPrimary(value)  => value.name.toString
      case ObjectPrimary(value) => value.name.toString

    def tree: Tree = this match
      case ClassPrimary(value)  => value
      case TraitPrimary(value)  => value
      case ObjectPrimary(value) => value

    def legacyTypeDefOption: Option[TypeDef] = this match
      case ClassPrimary(value) => Some(value)
      case TraitPrimary(value) => Some(value)
      case ObjectPrimary(_)    => None

  object PrimaryRole:
    def fromLegacyTypeDef(typeDef: TypeDef)(using Context): Either[Violation, PrimaryRole] =
      val mods = Trees.mods(typeDef)
      if !typeDef.isClassDef || mods.is(Enum) then
        Left(
          Violation(
            ViolationCategory.UnsupportedPrimaryRole,
            s"expected an ordinary class or trait TypeDef, found ${typeDef.name}"
          )
        )
      else if mods.is(Trait) then Right(PrimaryRole.TraitPrimary(typeDef))
      else Right(PrimaryRole.ClassPrimary(typeDef))

  enum OppositeRole:
    case ObjectOpposite(value: ModuleDef)
    case ClassOpposite(value: TypeDef)
    case TraitOpposite(value: TypeDef)

    def kind: TargetKind = this match
      case ObjectOpposite(_) => TargetKind.Object
      case ClassOpposite(_)  => TargetKind.Class
      case TraitOpposite(_)  => TargetKind.Trait

    def name: String = this match
      case ObjectOpposite(value) => value.name.toString
      case ClassOpposite(value)  => value.name.toString
      case TraitOpposite(value)  => value.name.toString

    def tree: Tree = this match
      case ObjectOpposite(value) => value
      case ClassOpposite(value)  => value
      case TraitOpposite(value)  => value

  object OppositeRole:
    def fromTypeDef(typeDef: TypeDef)(using Context): Either[Violation, OppositeRole] =
      PrimaryRole.fromLegacyTypeDef(typeDef).map:
        case PrimaryRole.ClassPrimary(value) => OppositeRole.ClassOpposite(value)
        case PrimaryRole.TraitPrimary(value) => OppositeRole.TraitOpposite(value)
        case PrimaryRole.ObjectPrimary(_) =>
          throw new IllegalStateException(
            "legacy TypeDef classification produced an object primary"
          )

    def validate(value: OppositeRole)(using Context): Either[Violation, Unit] =
      value match
        case OppositeRole.ObjectOpposite(_) => Right(())
        case OppositeRole.ClassOpposite(typeDef) =>
          fromTypeDef(typeDef).flatMap:
            case _: OppositeRole.ClassOpposite => Right(())
            case found =>
              Left(
                Violation(
                  ViolationCategory.OppositeKindMismatch,
                  s"class opposite wrapper contains raw ${found.kind} TypeDef ${typeDef.name}"
                )
              )

        case OppositeRole.TraitOpposite(typeDef) =>
          fromTypeDef(typeDef).flatMap:
            case _: OppositeRole.TraitOpposite => Right(())
            case found =>
              Left(
                Violation(
                  ViolationCategory.OppositeKindMismatch,
                  s"trait opposite wrapper contains raw ${found.kind} TypeDef ${typeDef.name}"
                )
              )

  enum OppositeCreationKind:
    case Class, Trait

  enum OppositePlacement:
    case BeforePrimary, AfterPrimary

  enum OppositeIntent:
    case Preserve
    case Replace(value: OppositeRole)
    case Create(
        kind: OppositeCreationKind,
        placement: OppositePlacement,
        value: OppositeRole
    )

  final case class TransactionTargetId(
      sourceStatsIdentity: Int,
      primaryIndex: Int,
      originalName: String,
      originalKind: TargetKind
  )

  final case class TransactionSnapshot private (
      targetId: TransactionTargetId,
      originalPackageStats: List[Tree],
      originalPrimary: PrimaryRole,
      originalOpposite: Option[OppositeRole],
      primaryIndex: Int,
      oppositeIndex: Option[Int],
      sourceParticipants: Vector[String]
  )

  object TransactionSnapshot:
    def capture(
        originalPackageStats: List[Tree],
        primary: PrimaryRole,
        opposite: Option[OppositeRole],
        sourceParticipants: Vector[String]
    ): Either[Violation, TransactionSnapshot] =
      val primaryIndex = originalPackageStats.indexWhere(_ eq primary.tree)
      if primaryIndex < 0 then
        Left(
          Violation(
            ViolationCategory.PrimaryOccurrenceMissing,
            s"primary ${primary.name} is not an exact member of the package-stat snapshot"
          )
        )
      else
        val oppositeIndex =
          opposite.map(value => originalPackageStats.indexWhere(_ eq value.tree))
        captureKnownOccurrences(
          originalPackageStats,
          primary,
          opposite,
          primaryIndex,
          oppositeIndex,
          sourceParticipants
        )

    private[macroparadise] def captureKnownOccurrences(
        originalPackageStats: List[Tree],
        primary: PrimaryRole,
        opposite: Option[OppositeRole],
        primaryIndex: Int,
        oppositeIndex: Option[Int],
        sourceParticipants: Vector[String]
    ): Either[Violation, TransactionSnapshot] =
      if primaryIndex < 0 then
        Left(
          Violation(
            ViolationCategory.PrimaryOccurrenceMissing,
            s"primary ${primary.name} is not an exact member of the package-stat snapshot"
          )
        )
      else
        oppositeIndex match
          case Some(index) if index < 0 =>
            Left(
              Violation(
                ViolationCategory.OppositeOccurrenceMissing,
                s"opposite ${opposite.get.name} is not an exact member of the package-stat snapshot"
              )
            )
          case _ =>
            validatePair(primary, opposite).map: _ =>
              val targetId = TransactionTargetId(
                System.identityHashCode(originalPackageStats),
                primaryIndex,
                primary.name,
                primary.kind
              )
              TransactionSnapshot(
                targetId,
                originalPackageStats,
                primary,
                opposite,
                primaryIndex,
                oppositeIndex,
                sourceParticipants
              )

    private[macroparadise] def validatePair(
        primary: PrimaryRole,
        opposite: Option[OppositeRole]
    ): Either[Violation, Unit] =
      opposite match
        case Some(value) if value.name != primary.name =>
          Left(
            Violation(
              ViolationCategory.OppositeNameMismatch,
              s"primary ${primary.name} cannot pair with opposite ${value.name}"
            )
          )
        case Some(_: OppositeRole.ObjectOpposite)
            if primary.kind == TargetKind.Object =>
          Left(
            Violation(
              ViolationCategory.IllegalRolePair,
              "an object primary cannot have an object opposite"
            )
          )
        case Some(_: OppositeRole.ClassOpposite | _: OppositeRole.TraitOpposite)
            if primary.kind != TargetKind.Object =>
          Left(
            Violation(
              ViolationCategory.IllegalRolePair,
              "a class or trait primary can currently have only an object opposite"
            )
          )
        case _ => Right(())

  final case class LegacyProjection(
      primary: TypeDef,
      companion: Option[ModuleDef]
  )

  final case class Rollback(
      packageStats: List[Tree],
      primary: PrimaryRole,
      opposite: Option[OppositeRole]
  )

  final case class RoleAwareExpansionResult(
      continuingPrimary: PrimaryRole,
      oppositeIntent: OppositeIntent
  )

  object RoleAwareExpansionResult:
    def apply(
        continuingPrimary: PrimaryRole,
        continuingOpposite: Option[OppositeRole]
    ): RoleAwareExpansionResult =
      RoleAwareExpansionResult(
        continuingPrimary,
        continuingOpposite.fold[OppositeIntent](OppositeIntent.Preserve)(
          OppositeIntent.Replace(_)
        )
      )

  final case class Transaction private (
      snapshot: TransactionSnapshot,
      currentPrimary: PrimaryRole,
      currentOpposite: Option[OppositeRole],
      createdOppositePlacement: Option[OppositePlacement],
      additionalTopLevelTrees: List[Tree]
  ):
    def targetId: TransactionTargetId = snapshot.targetId

    def legacyProjection(
        consumesExistingCompanion: Boolean
    ): Either[Violation, LegacyProjection] =
      currentPrimary.legacyTypeDefOption match
        case None =>
          Left(
            Violation(
              ViolationCategory.LegacyObjectPrimaryUnsupported,
              s"object primary ${currentPrimary.name} cannot be projected into legacy ExpansionInput"
            )
          )
        case Some(typeDef) =>
          val companion =
            if consumesExistingCompanion then
              currentOpposite.collect:
                case OppositeRole.ObjectOpposite(value) => value
            else None
          Right(LegacyProjection(typeDef, companion))

    def stageValidatedLegacy(
        trees: List[Tree],
        oppositeOmission: LegacyOppositeOmissionPolicy =
          LegacyOppositeOmissionPolicy.RetainCurrent
    )(using Context): Either[Violation, Transaction] =
      trees.headOption match
        case Some(nextPrimary: TypeDef) =>
          if nextPrimary.name.toString != targetId.originalName then
            Left(
              Violation(
                ViolationCategory.PrimaryNameMismatch,
                s"expected primary ${targetId.originalName}, found ${nextPrimary.name}"
              )
            )
          else
            PrimaryRole.fromLegacyTypeDef(nextPrimary).flatMap: nextRole =>
              if nextRole.kind != currentPrimary.kind then
                Left(
                  Violation(
                    ViolationCategory.PrimaryKindMismatch,
                    s"expected ${currentPrimary.kind}, found ${nextRole.kind}"
                  )
                )
              else
                val nextCompanion = trees.collectFirst:
                  case value: ModuleDef if value.name.toString == targetId.originalName =>
                    OppositeRole.ObjectOpposite(value)
                val nextOpposite =
                  nextCompanion.orElse:
                    oppositeOmission match
                      case LegacyOppositeOmissionPolicy.RetainCurrent =>
                        currentOpposite
                      case LegacyOppositeOmissionPolicy.DropCurrent =>
                        None
                val additional = trees.filterNot:
                  case value: TypeDef if value eq nextPrimary => true
                  case value: ModuleDef if value.name.toString == targetId.originalName => true
                  case _ => false
                Right(
                  copy(
                    currentPrimary = nextRole,
                    currentOpposite = nextOpposite,
                    additionalTopLevelTrees = additionalTopLevelTrees ++ additional
                  )
                )
        case _ =>
          Left(
            Violation(
              ViolationCategory.MissingLegacyPrimary,
              s"validated legacy output lacks leading TypeDef ${targetId.originalName}"
            )
          )

    def stageValidatedRoleAware(
        result: RoleAwareExpansionResult
    )(using Context): Either[Violation, Transaction] =
      val nextPrimary = result.continuingPrimary
      if nextPrimary.name != targetId.originalName then
        Left(
          Violation(
            ViolationCategory.PrimaryNameMismatch,
            s"expected primary ${targetId.originalName}, found ${nextPrimary.name}"
          )
        )
      else if nextPrimary.kind != targetId.originalKind then
        Left(
          Violation(
            ViolationCategory.PrimaryKindMismatch,
            s"expected ${targetId.originalKind}, found ${nextPrimary.kind}"
          )
        )
      else
        Option(result.oppositeIntent) match
          case None =>
            Left(
              Violation(
                ViolationCategory.MissingOppositeIntent,
                s"missing opposite intent for ${targetId.originalName}"
              )
            )
          case Some(OppositeIntent.Preserve) =>
            Right(copy(currentPrimary = nextPrimary))
          case Some(OppositeIntent.Replace(next)) =>
            currentOpposite match
              case None =>
                Left(
                  Violation(
                    ViolationCategory.OppositePresenceMismatch,
                    s"replacement cannot create an opposite for ${targetId.originalName}"
                  )
                )
              case Some(current) if next == null =>
                Left(
                  Violation(
                    ViolationCategory.OppositeKindMismatch,
                    s"replacement opposite for ${targetId.originalName} is null"
                  )
                )
              case Some(current) =>
                OppositeRole.validate(next).flatMap: _ =>
                  if current.kind != next.kind then
                    Left(
                      Violation(
                        ViolationCategory.OppositeKindMismatch,
                        s"expected opposite ${current.kind}, found ${next.kind}"
                      )
                    )
                  else
                    TransactionSnapshot
                      .validatePair(nextPrimary, Some(next))
                      .map: _ =>
                        copy(
                          currentPrimary = nextPrimary,
                          currentOpposite = Some(next)
                        )
          case Some(OppositeIntent.Create(kind, placement, next)) =>
            if currentOpposite.nonEmpty then
              Left(
                Violation(
                  ViolationCategory.OppositeAlreadyExists,
                  s"cannot create a second opposite for ${targetId.originalName}"
                )
              )
            else if kind == null then
              Left(
                Violation(
                  ViolationCategory.InvalidOppositeCreationKind,
                  s"missing opposite creation kind for ${targetId.originalName}"
                )
              )
            else if placement == null then
              Left(
                Violation(
                  ViolationCategory.InvalidOppositePlacement,
                  s"missing opposite placement for ${targetId.originalName}"
                )
              )
            else if next == null then
              Left(
                Violation(
                  ViolationCategory.OppositeKindMismatch,
                  s"created opposite for ${targetId.originalName} is null"
                )
              )
            else
              val declaredKindMatches = (kind, next) match
                case (OppositeCreationKind.Class, _: OppositeRole.ClassOpposite) =>
                  true
                case (OppositeCreationKind.Trait, _: OppositeRole.TraitOpposite) =>
                  true
                case _ => false
              if !declaredKindMatches then
                Left(
                  Violation(
                    ViolationCategory.OppositeKindMismatch,
                    s"declared creation kind $kind does not match raw opposite ${next.kind}"
                  )
                )
              else
                OppositeRole.validate(next).flatMap: _ =>
                  TransactionSnapshot
                    .validatePair(nextPrimary, Some(next))
                    .map: _ =>
                      copy(
                        currentPrimary = nextPrimary,
                        currentOpposite = Some(next),
                        createdOppositePlacement = Some(placement)
                      )

    def commitPackageStats: List[Tree] =
      val replaced = snapshot.originalPackageStats.zipWithIndex.map: (original, index) =>
        if index == snapshot.primaryIndex then currentPrimary.tree
        else if snapshot.oppositeIndex.contains(index) then
          currentOpposite
            .map(_.tree)
            .getOrElse(
              throw new IllegalStateException(
                s"validated transaction lost opposite at source index $index"
              )
            )
        else original
      (snapshot.oppositeIndex, currentOpposite, createdOppositePlacement) match
        case (None, Some(created), Some(OppositePlacement.BeforePrimary)) =>
          replaced.patch(snapshot.primaryIndex, created.tree :: Nil, 0)
        case (None, Some(created), Some(OppositePlacement.AfterPrimary)) =>
          replaced.patch(snapshot.primaryIndex + 1, created.tree :: Nil, 0)
        case _ => replaced

    def outputTrees: List[Tree] =
      currentPrimary.tree :: currentOpposite.toList.map(_.tree) ++ additionalTopLevelTrees

    def rollback: Rollback =
      Rollback(
        snapshot.originalPackageStats,
        snapshot.originalPrimary,
        snapshot.originalOpposite
      )

  object Transaction:
    def start(snapshot: TransactionSnapshot): Transaction =
      Transaction(
        snapshot,
        snapshot.originalPrimary,
        snapshot.originalOpposite,
        None,
        Nil
      )

  final case class LegacyTransaction private (
      private val underlying: Transaction
  ):
    def snapshot: TransactionSnapshot = underlying.snapshot

    def targetId: TransactionTargetId = underlying.targetId

    def currentPrimary: TypeDef = underlying.currentPrimary match
      case PrimaryRole.ClassPrimary(value) => value
      case PrimaryRole.TraitPrimary(value) => value
      case PrimaryRole.ObjectPrimary(_) =>
        throw new IllegalStateException(
          "validated LegacyTransaction contained an object primary"
        )

    def currentCompanion: Option[ModuleDef] =
      underlying.currentOpposite.collect:
        case OppositeRole.ObjectOpposite(value) => value

    def additionalTopLevelTrees: List[Tree] =
      underlying.additionalTopLevelTrees

    def projection(
        consumesExistingCompanion: Boolean
    ): LegacyProjection =
      underlying
        .legacyProjection(consumesExistingCompanion)
        .fold(
          violation =>
            throw new IllegalStateException(
              s"validated LegacyTransaction projection failed: ${violation.render}"
            ),
          identity
        )

    def stageValidatedOutput(
        trees: List[Tree],
        oppositeOmission: LegacyOppositeOmissionPolicy =
          LegacyOppositeOmissionPolicy.RetainCurrent
    )(using Context): Either[Violation, LegacyTransaction] =
      underlying
        .stageValidatedLegacy(trees, oppositeOmission)
        .flatMap(LegacyTransaction.fromTransaction)

    def outputTrees: List[Tree] = underlying.outputTrees

    def rollback: Rollback = underlying.rollback

  object LegacyTransaction:
    def start(snapshot: TransactionSnapshot): Either[Violation, LegacyTransaction] =
      fromTransaction(Transaction.start(snapshot))

    private def fromTransaction(
        transaction: Transaction
    ): Either[Violation, LegacyTransaction] =
      transaction.currentPrimary match
        case PrimaryRole.ObjectPrimary(_) =>
          Left(
            Violation(
              ViolationCategory.LegacyObjectPrimaryUnsupported,
              s"object primary ${transaction.currentPrimary.name} cannot enter a legacy transaction"
            )
          )
        case _ => Right(LegacyTransaction(transaction))

  final case class ObjectTransaction private (
      private val underlying: Transaction
  ):
    def snapshot: TransactionSnapshot = underlying.snapshot

    def targetId: TransactionTargetId = underlying.targetId

    def currentPrimary: ModuleDef = underlying.currentPrimary match
      case PrimaryRole.ObjectPrimary(value) => value
      case _ =>
        throw new IllegalStateException(
          "validated ObjectTransaction contained a non-object primary"
        )

    def currentOpposite: Option[OppositeRole] = underlying.currentOpposite

    def stageValidatedOutput(
        result: RoleAwareExpansionResult
    )(using Context): Either[Violation, ObjectTransaction] =
      underlying
        .stageValidatedRoleAware(result)
        .flatMap(ObjectTransaction.fromTransaction)

    def commitPackageStats: List[Tree] = underlying.commitPackageStats

    def rollback: Rollback = underlying.rollback

  object ObjectTransaction:
    def discover(
        originalPackageStats: List[Tree],
        primary: ModuleDef,
        sourceParticipants: Vector[String]
    )(using Context): Either[Violation, ObjectTransaction] =
      val targetName = primary.name.toString
      var primaryIndex = -1
      val candidates = originalPackageStats.zipWithIndex.flatMap:
        case (value, index) if value eq primary =>
          primaryIndex = index
          None
        case (typeDef: TypeDef, index)
            if typeDef.name.toString == targetName =>
          OppositeRole.fromTypeDef(typeDef).toOption.map(_ -> index)
        case _ => None

      candidates match
        case _ :: _ :: _ =>
          Left(
            Violation(
              ViolationCategory.AmbiguousOppositeTopology,
              s"object primary $targetName has ${candidates.size} same-name class/trait opposite candidates"
            )
          )
        case candidate =>
          val (opposite, oppositeIndex) =
            candidate.headOption match
              case Some((value, index)) => Some(value) -> Some(index)
              case None                 => None -> None
          TransactionSnapshot
            .captureKnownOccurrences(
              originalPackageStats,
              PrimaryRole.ObjectPrimary(primary),
              opposite,
              primaryIndex,
              oppositeIndex,
              sourceParticipants
            )
            .flatMap(snapshot => fromTransaction(Transaction.start(snapshot)))

    private def fromTransaction(
        transaction: Transaction
    ): Either[Violation, ObjectTransaction] =
      transaction.currentPrimary match
        case PrimaryRole.ObjectPrimary(_) =>
          Right(ObjectTransaction(transaction))
        case _ =>
          Left(
            Violation(
              ViolationCategory.UnsupportedPrimaryRole,
              s"object transaction requires an object primary, found ${transaction.currentPrimary.kind}"
            )
          )
