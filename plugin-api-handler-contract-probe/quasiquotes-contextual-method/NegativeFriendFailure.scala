package quasiquotes.macroparadisecontextual.negative

import quasiquotes.macroparadisecontextual.PositionedContextualApply

@PositionedContextualApply
trait FriendFailure[A]

object FriendFailureUse:
  val unresolved = FriendFailure.apply[String]
