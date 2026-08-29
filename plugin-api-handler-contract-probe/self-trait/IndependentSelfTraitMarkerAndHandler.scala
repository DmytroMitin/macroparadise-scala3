package contractprobeself

import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Names.{termName, typeName}
import paradise3.api.{ExpansionInput, ExpansionOutcome, ExpansionTargetProfile, ParadiseAnnotationExpander, expander}
import paradise3.api.helpers.{ExpansionHelpers, TraitSelfPreparation}
import scala.annotation.StaticAnnotation

@expander("contractprobeself.IndependentSelfTraitHandler")
final class IndependentSelfTraitMarker extends StaticAnnotation

final class IndependentSelfTraitHandler extends ParadiseAnnotationExpander:
  val annotationName: String = "IndependentSelfTraitMarker"
  override val targetProfile: ExpansionTargetProfile =
    ExpansionTargetProfile.PlainZeroParameterTrait

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionHelpers.addPreparedSelfTypeToTrait(input): preparation =>
      if input.className == "RejectSelfNat" then
        throw new IllegalStateException("direct Self preflight invoked lowering callback")
      generatedSelfType(input, preparation)

  private def generatedSelfType(
      input: ExpansionInput,
      preparation: TraitSelfPreparation
  )(using Context): untpd.TypeDef =
    given dotty.tools.dotc.util.SourceFile = input.annotatedClass.source
    untpd.TypeDef(
      typeName("Self"),
      untpd.SingletonTypeTree(
        untpd.Ident(termName(preparation.selfAliasName))
      )
    )
