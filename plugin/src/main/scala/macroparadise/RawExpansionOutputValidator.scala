package macroparadise

import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.ast.untpd.*

/** Internal structural validation for the experimental raw handler output list.
  *
  * This deliberately validates only package-splicing invariants that can be
  * established from raw untyped tree shape and known syntactic top-level names.
  * It does not typecheck output, inspect symbols, or replace the raw list with a
  * public structured result.
  */
private[macroparadise] object RawExpansionOutputValidator:
  final case class Input(
      currentPrimary: TypeDef,
      knownTopLevelNames: Set[String],
      trees: List[untpd.Tree]
  )

  final case class Violation(invariant: String, detail: String):
    def diagnostic(origin: String, primaryName: String): String =
      s"invalid raw expansion output from $origin on primary `$primaryName`: invariant $invariant failed: $detail"

  def validate(input: Input): Option[Violation] =
    val primaryName = input.currentPrimary.name.toString
    val indexedTrees = input.trees.zipWithIndex
    val primaryIndexes =
      indexedTrees.collect:
        case (typeDef: TypeDef, index) if typeDef.name.toString == primaryName =>
          index
    val companionIndexes =
      indexedTrees.collect:
        case (moduleDef: ModuleDef, index) if moduleDef.name.toString == primaryName =>
          index

    if input.trees.isEmpty then
      Some(Violation("A (non-empty output)", "the handler returned no trees"))
    else if !isSameNamePrimary(input.trees.head, primaryName) then
      Some(
        Violation(
          "B (primary first)",
          s"the first tree must be a TypeDef named `$primaryName`, but was ${treeDescription(input.trees.head)}"
        )
      )
    else if primaryIndexes.size != 1 then
      Some(
        Violation(
          "C (exactly one primary)",
          s"expected exactly one TypeDef named `$primaryName`, but found ${primaryIndexes.size}"
        )
      )
    else if companionIndexes.size > 1 then
      Some(
        Violation(
          "D (at most one companion)",
          s"expected at most one ModuleDef named `$primaryName`, but found ${companionIndexes.size}"
        )
      )
    else if companionIndexes.headOption.exists(_ != primaryIndexes.head + 1) then
      Some(
        Violation(
          "E (companion immediately after primary)",
          s"the same-name companion for `$primaryName` must immediately follow the primary"
        )
      )
    else
      val roleIndexes = primaryIndexes.toSet ++ companionIndexes.toSet
      val additionalNamed =
        indexedTrees.collect:
          case (tree, index) if !roleIndexes.contains(index) =>
            namedDefinition(tree).map(name => (name, index))
        .flatten
      val duplicateAdditional =
        additionalNamed
          .groupBy(_._1)
          .collect:
            case (name, occurrences) if occurrences.size > 1 => name
          .toList
          .sorted
          .headOption

      duplicateAdditional match
        case Some(name) =>
          Some(
            Violation(
              "F (unique additional named outputs)",
              s"additional output defines top-level name `$name` more than once"
            )
          )
        case None =>
          additionalNamed
            .map(_._1)
            .filter(input.knownTopLevelNames.contains)
            .distinct
            .sorted
            .headOption
            .map: name =>
              Violation(
                "G (no known top-level conflict)",
                s"additional output name `$name` conflicts with a known pre-existing top-level definition"
              )

  private def isSameNamePrimary(tree: untpd.Tree, primaryName: String): Boolean =
    tree match
      case typeDef: TypeDef => typeDef.name.toString == primaryName
      case _ => false

  private def namedDefinition(tree: untpd.Tree): Option[String] =
    tree match
      case typeDef: TypeDef => Some(typeDef.name.toString)
      case moduleDef: ModuleDef => Some(moduleDef.name.toString)
      case _ => None

  private def treeDescription(tree: untpd.Tree): String =
    tree match
      case typeDef: TypeDef => s"TypeDef `${typeDef.name}`"
      case moduleDef: ModuleDef => s"ModuleDef `${moduleDef.name}`"
      case other => other.getClass.getSimpleName
