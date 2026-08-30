package contractprobeunionconsumer

import contractprobeunion.IndependentClosedTargetUnionMarker

trait Nat

@IndependentClosedTargetUnionMarker
trait Show[A]

@IndependentClosedTargetUnionMarker
trait Add[N <: Nat, M <: Nat]:
  type Out <: Nat

object IndependentClosedTargetUnionConsumer:
  def main(args: Array[String]): Unit =
    println(Show.closedTargetUnionInvoked)
    println(Add.closedTargetUnionInvoked)
