package macroparadise

import dotty.tools.dotc.ast.{Trees, untpd}
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Flags.{Enum, Trait}
import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.StructuredExpansionOutput

/** Validates and canonicalizes the experimental role-structured success path.
  *
  * Role checks are intentionally syntactic and run before the existing raw
  * validator. The latter remains the single defense-in-depth owner for common
  * package-splicing invariants.
  */
private[macroparadise] object StructuredExpansionOutputValidator:
  final case class Input(
      currentPrimary: TypeDef,
      knownTopLevelNames: Set[String],
      output: StructuredExpansionOutput
  )

  final case class Violation(category: String, invariant: String, actual: String):
    def diagnostic(origin: String, annotationName: String, primaryName: String): String =
      s"invalid structured expansion output from $origin: annotation=@$annotationName class=$primaryName " +
        s"category=$category invariant=$invariant actual=$actual"

  def validate(input: Input)(using Context): Either[Violation, List[untpd.Tree]] =
    val primaryName = input.currentPrimary.name.toString
    val output = input.output

    if output == null then
      Left(
        Violation(
          "NULL_OUTPUT",
          "structured output object must be non-null",
          "null"
        )
      )
    else if output.primary == null then
      Left(
        Violation(
          "NULL_PRIMARY",
          "primary must be non-null",
          "null"
        )
      )
    else if output.primary.name.toString != primaryName then
      Left(
        Violation(
          "PRIMARY_NAME_MISMATCH",
          s"primary must be a TypeDef named `$primaryName`",
          s"TypeDef `${output.primary.name}`"
        )
      )
    else if definitionKind(output.primary) != definitionKind(input.currentPrimary) then
      Left(
        Violation(
          "PRIMARY_KIND_MISMATCH",
          s"primary must preserve raw definition kind `${definitionKind(input.currentPrimary)}`",
          s"TypeDef `${output.primary.name}` kind=${definitionKind(output.primary)}"
        )
      )
    else if output.companion == null then
      Left(
        Violation(
          "NULL_COMPANION_OPTION",
          "companion Option container must be non-null",
          "null"
        )
      )
    else
      output.companion match
        case Some(companion) if companion == null =>
          Left(
            Violation(
              "NULL_COMPANION",
              "present companion must be non-null",
              "Some(null)"
            )
          )
        case Some(companion) if companion.name.toString != primaryName =>
          Left(
            Violation(
              "COMPANION_NAME_MISMATCH",
              s"companion must be a ModuleDef named `$primaryName`",
              s"ModuleDef `${companion.name}`"
            )
          )
        case companion =>
          validateAdditional(
            input,
            primaryName,
            output.primary,
            companion,
            output.additionalTopLevelDefinitions
          )

  private def validateAdditional(
      input: Input,
      primaryName: String,
      primary: TypeDef,
      companion: Option[ModuleDef],
      additional: List[untpd.Tree]
  )(using Context): Either[Violation, List[untpd.Tree]] =
    if additional == null then
      Left(
        Violation(
          "NULL_ADDITIONAL_LIST",
          "additional top-level definition list must be non-null",
          "null"
        )
      )
    else
      additional.zipWithIndex.collectFirst:
        case (tree, index) if tree == null =>
          Violation(
            "NULL_ADDITIONAL_ELEMENT",
            "every additional top-level definition must be non-null",
            s"index=$index value=null"
          )
        case (tree, index) if !tree.isInstanceOf[TypeDef] && !tree.isInstanceOf[ModuleDef] =>
          Violation(
            "UNSUPPORTED_ADDITIONAL_TREE_KIND",
            "structured additional output accepts only TypeDef or ModuleDef",
            s"index=$index kind=${tree.getClass.getName}"
          )
        case (typeDef: TypeDef, index) if typeDef.name.toString == primaryName =>
          Violation(
            "ADDITIONAL_PRIMARY_ROLE",
            "additional output must not reintroduce the same-name primary role",
            s"index=$index TypeDef `${typeDef.name}`"
          )
        case (moduleDef: ModuleDef, index) if moduleDef.name.toString == primaryName =>
          Violation(
            "ADDITIONAL_COMPANION_ROLE",
            "additional output must not reintroduce the same-name companion role",
            s"index=$index ModuleDef `${moduleDef.name}`"
          )
      match
        case Some(violation) =>
          Left(violation)
        case None =>
          val namedAdditional =
            additional.map:
              case typeDef: TypeDef => typeDef.name.toString
              case moduleDef: ModuleDef => moduleDef.name.toString
              case _ => throw new AssertionError("structured kind check did not hold")
          val duplicate =
            namedAdditional
              .groupBy(identity)
              .collect:
                case (name, occurrences) if occurrences.size > 1 => name
              .toList
              .sorted
              .headOption

          duplicate match
            case Some(name) =>
              Left(
                Violation(
                  "DUPLICATE_ADDITIONAL_NAME",
                  "additional named definitions must be unique across class/object forms",
                  s"name=$name occurrences=${namedAdditional.count(_ == name)}"
                )
              )
            case None =>
              namedAdditional
                .filter(input.knownTopLevelNames.contains)
                .distinct
                .sorted
                .headOption
              match
                case Some(name) =>
                  Left(
                    Violation(
                      "TOP_LEVEL_NAME_CONFLICT",
                      "additional named definitions must not conflict with known top-level names",
                      s"name=$name"
                    )
                  )
                case None =>
                  val canonical =
                    List(primary) ++ companion.toList ++ additional
                  RawExpansionOutputValidator.validate(
                    RawExpansionOutputValidator.Input(
                      currentPrimary = input.currentPrimary,
                      knownTopLevelNames = input.knownTopLevelNames,
                      trees = canonical
                    )
                  ) match
                    case Some(rawViolation) =>
                      Left(
                        Violation(
                          "RAW_VALIDATION_DEFENSE_IN_DEPTH",
                          rawViolation.invariant,
                          rawViolation.detail
                        )
                      )
                    case None =>
                      Right(canonical)

  private def definitionKind(typeDef: TypeDef)(using Context): String =
    val mods = Trees.mods(typeDef)
    if !typeDef.isClassDef then "non-class-type"
    else if mods.is(Enum) then "enum"
    else if mods.is(Trait) then "trait"
    else "class"
