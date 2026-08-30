package contractprobeunionconsumernegative

import contractprobeunion.IndependentClosedTargetUnionMarker

trait Nat

@IndependentClosedTargetUnionMarker
trait Zero

@IndependentClosedTargetUnionMarker
trait TwoUnbounded[A, B]

@IndependentClosedTargetUnionMarker
trait OneBounded[A <: Nat]

@IndependentClosedTargetUnionMarker
trait Mixed[A, B <: Nat]

@IndependentClosedTargetUnionMarker
trait LowerBounded[A >: Nothing <: Nat, B <: Nat]

@IndependentClosedTargetUnionMarker
trait Constructor[A](val value: A)

@IndependentClosedTargetUnionMarker
class OrdinaryClass[A]

@IndependentClosedTargetUnionMarker
sealed trait SealedShow[A]

@IndependentClosedTargetUnionMarker
trait ContextualShow[A: Ordering]

@IndependentClosedTargetUnionMarker
trait CovariantShow[+A]
