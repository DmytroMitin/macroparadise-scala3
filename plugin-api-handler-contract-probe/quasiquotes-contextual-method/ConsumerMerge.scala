package quasiquotes.macroparadisecontextual.consumer

import quasiquotes.macroparadisecontextual.PositionedContextualApply

@PositionedContextualApply
trait ContextualMethodMerge[Value]:
  def render(value: Value): String

object ContextualMethodMerge:
  val preservedBefore: Int = 41
  object Nested:
    def apply(value: Int): Int = value + 1
  def applyLike(value: Int): Int = value + 2
  val preservedAfter: Int = 43
