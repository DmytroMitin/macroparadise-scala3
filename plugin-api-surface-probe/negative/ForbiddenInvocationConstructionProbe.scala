package surfaceprobe

import dotty.tools.dotc.ast.untpd
import paradise3.api.*

object ForbiddenInvocationConstructionProbe:
  def constructContainer(names: Set[String]): ExpansionContainerContext =
    new ExpansionContainerContext(names)

  def copyContainer(value: ExpansionContainerContext): ExpansionContainerContext =
    value.copy(occupiedDefinitionNames = Set.empty)

  def constructInput(
      primary: ExpansionTarget,
      companion: Option[ExpansionTarget],
      container: ExpansionContainerContext,
      annotation: untpd.Tree
  ): ExpansionInput =
    new ExpansionInput(primary, companion, container, annotation)

  def copyInput(value: ExpansionInput): ExpansionInput =
    value.copy(companion = None)
