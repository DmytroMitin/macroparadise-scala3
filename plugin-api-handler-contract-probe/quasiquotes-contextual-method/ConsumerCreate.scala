package quasiquotes.macroparadisecontextual.consumer

import quasiquotes.macroparadisecontextual.PositionedContextualApply

@PositionedContextualApply
trait ContextualMethodCreate[Element]:
  def render(value: Element): String
