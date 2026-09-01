import paradise3.{mixedRestrictedCompanion, mixedUnionCompanion}

@mixedUnionCompanion
@mixedRestrictedCompanion
trait MixedProfileUnionThenRestricted[A]

object MixedProfileUnionThenRestricted:
  val preserved: Int = 42

@mixedRestrictedCompanion
@mixedUnionCompanion
trait MixedProfileRestrictedThenUnion[A]

object MixedProfileRestrictedThenUnion:
  val preserved: Int = 84

object MixedClosedProfileCompositionExample:
  val unionThenRestrictedUnionResult =
    MixedProfileUnionThenRestricted.mixedUnionResult
  val unionThenRestrictedRestrictedResult =
    MixedProfileUnionThenRestricted.mixedRestrictedResult
  val unionThenRestrictedPreserved =
    MixedProfileUnionThenRestricted.preserved

  val restrictedThenUnionUnionResult =
    MixedProfileRestrictedThenUnion.mixedUnionResult
  val restrictedThenUnionRestrictedResult =
    MixedProfileRestrictedThenUnion.mixedRestrictedResult
  val restrictedThenUnionPreserved =
    MixedProfileRestrictedThenUnion.preserved
