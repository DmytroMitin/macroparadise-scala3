package quasiquotes.macroparadisecontextual.consumer

import quasiquotes.macroparadisecontextual.PositionedContextualApply

@PositionedContextualApply
trait ContextualMethodConflict[Item]:
  def render(value: Item): String

object ContextualMethodConflict:
  var directCalls: Int = 0
  def apply[Item](using instance: ContextualMethodConflict[Item]): ContextualMethodConflict[Item] =
    directCalls += 1
    instance

object ContextualMethodRuntime:
  def main(args: Array[String]): Unit =
    val created: ContextualMethodCreate[String] = new ContextualMethodCreate[String]:
      def render(value: String): String = "create:" + value
    require(ContextualMethodCreate.apply[String](using created) eq created)
    require(created.render("ok") == "create:ok")

    val merged: ContextualMethodMerge[String] = new ContextualMethodMerge[String]:
      def render(value: String): String = "merge:" + value
    require(ContextualMethodMerge.apply[String](using merged) eq merged)
    require(merged.render("ok") == "merge:ok")
    require(ContextualMethodMerge.preservedBefore == 41)
    require(ContextualMethodMerge.Nested(1) == 2)
    require(ContextualMethodMerge.applyLike(1) == 3)
    require(ContextualMethodMerge.preservedAfter == 43)

    val conflict: ContextualMethodConflict[String] = new ContextualMethodConflict[String]:
      def render(value: String): String = "conflict:" + value
    require(ContextualMethodConflict.apply[String](using conflict) eq conflict)
    require(conflict.render("ok") == "conflict:ok")
    require(ContextualMethodConflict.directCalls == 1)

    println("MACROPARADISE_QUASIQUOTES_CONTEXTUAL_METHOD_OK")
