package roleawareprobe

import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags.Trait
import dotty.tools.dotc.core.Names.{termName, typeName}
import paradise3.api.*

final class IndependentRoleAwareHandler
    extends RoleAwareParadiseAnnotationExpander:
  val annotationName: String = "roleawareprobe.IndependentRoleAwareMarker"

  override val oppositeCapability: RoleAwareOppositeCapability =
    RoleAwareOppositeCapability.LeaseOrCreateClassOrTrait

  def expand(
      input: RoleAwareExpansionInput
  )(using Context): RoleAwareExpansionOutcome =
    input.primary match
      case ExpansionPrimaryRole.Object(primary) =>
        val primaryName = primary.name.toString
        val rewritten =
          if primaryName == "ObjectEdit" then
            appendStringMethod(primary, "foo", "ok")
          else primary
        val opposite =
          (primaryName, input.leasedOpposite) match
            case ("ExistingClass", Some(ExpansionOppositeRole.Class(value))) =>
              OppositeChange.Replace(
                ExpansionOppositeRole.Class(
                  appendStringMethod(value, "foo", "class-replaced")
                )
              )
            case ("ExistingTrait", Some(ExpansionOppositeRole.Trait(value))) =>
              OppositeChange.Replace(
                ExpansionOppositeRole.Trait(
                  appendStringMethod(value, "foo", "trait-replaced")
                )
              )
            case ("CreateClass", None) =>
              OppositeChange.Create(
                ExpansionOppositeRole.Class(
                  freshOpposite(primaryName, primary.source, asTrait = false, "class-created")
                ),
                OppositePlacement.BeforePrimary
              )
            case ("CreateTrait", None) =>
              OppositeChange.Create(
                ExpansionOppositeRole.Trait(
                  freshOpposite(primaryName, primary.source, asTrait = true, "trait-created")
                ),
                OppositePlacement.AfterPrimary
              )
            case _ => OppositeChange.Preserve
        RoleAwareExpansionOutcome.Expanded(
          RoleAwareExpansionOutput(
            ExpansionPrimaryRole.Object(rewritten),
            opposite
          )
        )
      case _ =>
        RoleAwareExpansionOutcome.Rejected(
          List(ExpansionDiagnostic("expected object primary", input.currentAnnotation.sourcePos))
        )

  private def appendStringMethod(
      value: untpd.ModuleDef,
      name: String,
      result: String
  )(using Context): untpd.ModuleDef =
    val rewritten = appendStringMethod(value.impl, value.source, name, result)
    untpd.cpy.ModuleDef(value)(value.name, rewritten)

  private def appendStringMethod(
      value: untpd.TypeDef,
      name: String,
      result: String
  )(using Context): untpd.TypeDef =
    value.rhs match
      case template: untpd.Template =>
        untpd.cpy.TypeDef(value)(
          value.name,
          appendStringMethod(template, value.source, name, result)
        )
      case _ => value

  private def appendStringMethod(
      template: untpd.Template,
      source: dotty.tools.dotc.util.SourceFile,
      name: String,
      result: String
  )(using Context): untpd.Template =
    given dotty.tools.dotc.util.SourceFile = source
    val method = untpd.DefDef(
      termName(name),
      Nil,
      untpd.Ident(typeName("String")),
      untpd.Literal(Constant(result))
    )
    untpd.cpy.Template(template)(
      template.constr,
      template.parentsOrDerived,
      template.derived,
      template.self,
      template.body :+ method
    )

  private def freshOpposite(
      name: String,
      source: dotty.tools.dotc.util.SourceFile,
      asTrait: Boolean,
      methodResult: String
  )(using Context): untpd.TypeDef =
    given dotty.tools.dotc.util.SourceFile = source
    val method = untpd.DefDef(
      termName("foo"),
      Nil,
      untpd.Ident(typeName("String")),
      untpd.Literal(Constant(methodResult))
    )
    val raw = untpd.TypeDef(
      typeName(name),
      untpd.Template(untpd.emptyConstructor, Nil, Nil, untpd.EmptyValDef, method :: Nil)
    )
    if asTrait then raw.withMods(untpd.Modifiers(Trait)).asInstanceOf[untpd.TypeDef]
    else raw
