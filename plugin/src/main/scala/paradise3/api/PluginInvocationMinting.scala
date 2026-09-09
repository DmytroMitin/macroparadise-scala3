package paradise3.api

import dotty.tools.dotc.ast.untpd

/** Implementation-artifact bridge; deliberately absent from the pluginApi artifact. */
object PluginInvocationMinting:
  def container(occupiedDefinitionNames: Set[String]): ExpansionContainerContext =
    new ExpansionContainerContext(occupiedDefinitionNames)

  def input(
      primary: ExpansionTarget,
      companion: Option[ExpansionTarget],
      container: ExpansionContainerContext,
      currentAnnotation: untpd.Tree
  ): ExpansionInput =
    new ExpansionInput(primary, companion, container, currentAnnotation)
