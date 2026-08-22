package macroparadise

import dotty.tools.dotc.ast.Trees
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.util.SrcPos

import java.util.IdentityHashMap
import scala.collection.mutable

private[macroparadise] final case class ExplicitImportAnnotationIdentityDiagnostic(
    message: String,
    pos: SrcPos
)

private[macroparadise] final case class ExplicitImportAnnotationIdentityRequest(
    annotationName: String,
    importedShortName: Option[String]
)

/** Resolves the intentionally small pre-typer import slice supported by the
  * plugin: one or more package-level, source-preceding, explicit imports whose
  * imported name is unchanged.
  *
  * Wildcard, renamed, given, nested, exported, and semantic imports are not
  * candidates. Qualified annotations bypass this table unchanged.
  */
private[macroparadise] final class ExplicitImportAnnotationIdentityResolver private (
    resolutions: IdentityHashMap[
      Tree,
      Either[ExplicitImportAnnotationIdentityDiagnostic, SyntacticAnnotationIdentity]
    ],
    importedShortNames: IdentityHashMap[Tree, String]
):
  private val legacySimpleIdentities = mutable.Set.empty[String]

  def identityOf(
      annotation: Tree
  )(using Context): Either[ExplicitImportAnnotationIdentityDiagnostic, SyntacticAnnotationIdentity] =
    val base =
      if resolutions.containsKey(annotation) then resolutions.get(annotation)
      else
        SyntacticAnnotationIdentity.fromTree(annotation).toRight(
          ExplicitImportAnnotationIdentityDiagnostic(
            "unsupported raw annotation identity; expected a simple or qualified identifier chain",
            annotation.sourcePos
          )
        )
    base.flatMap: identity =>
      Option(importedShortNames.get(annotation)) match
        case Some(shortName) if legacySimpleIdentities.contains(identity.value) =>
          SyntacticAnnotationIdentity.fromDeclaredName(shortName).left.map: detail =>
            ExplicitImportAnnotationIdentityDiagnostic(detail, annotation.sourcePos)
        case _ => Right(identity)

  def identityOfUsingWitnesses(
      annotation: Tree,
      witnesses: List[Tree]
  )(using Context): Either[ExplicitImportAnnotationIdentityDiagnostic, SyntacticAnnotationIdentity] =
    identityOf(annotation).flatMap: identity =>
      if resolutions.containsKey(annotation) || identity.isQualified then Right(identity)
      else
        val canonicalCandidates =
          SyntacticAnnotationIdentity.fromTree(annotation).toList.flatMap: raw =>
            witnesses.flatMap: witness =>
              SyntacticAnnotationIdentity.fromTree(witness).toList
                .filter(_.value == raw.value)
                .flatMap(_ => identityOf(witness).toOption)
                .filter(_.isQualified)
          .distinct
          .sortBy(_.value)

        canonicalCandidates match
          case Nil => Right(identity)
          case candidate :: Nil => Right(candidate)
          case candidates =>
            Left(
              ExplicitImportAnnotationIdentityDiagnostic(
                s"ambiguous original annotation identity witnesses for reconstructed `@${identity.value}`; candidates: ${candidates.map(_.value).mkString(", ")}",
                annotation.sourcePos
              )
            )

  def requestOf(
      annotation: Tree
  )(using Context): Either[ExplicitImportAnnotationIdentityDiagnostic, ExplicitImportAnnotationIdentityRequest] =
    val base =
      if resolutions.containsKey(annotation) then resolutions.get(annotation)
      else
        SyntacticAnnotationIdentity.fromTree(annotation).toRight(
          ExplicitImportAnnotationIdentityDiagnostic(
            "unsupported raw annotation identity; expected a simple or qualified identifier chain",
            annotation.sourcePos
          )
        )
    base.map: identity =>
      ExplicitImportAnnotationIdentityRequest(
        identity.value,
        Option(importedShortNames.get(annotation))
      )

  def preferLegacySimpleIdentity(request: ExplicitImportAnnotationIdentityRequest): Unit =
    if request.importedShortName.nonEmpty then
      legacySimpleIdentities += request.annotationName

private[macroparadise] object ExplicitImportAnnotationIdentityResolver:
  private type ActiveImports = Map[String, Set[String]]
  private final case class LocalImportBarrier(
      names: Set[String],
      blocksAllShortNames: Boolean
  ):
    def blocks(name: String): Boolean = blocksAllShortNames || names.contains(name)

  private object LocalImportBarrier:
    val Empty: LocalImportBarrier = LocalImportBarrier(Set.empty, false)

  def fromUnitTree(tree: Tree)(using Context): ExplicitImportAnnotationIdentityResolver =
    val resolutions = IdentityHashMap[
      Tree,
      Either[ExplicitImportAnnotationIdentityDiagnostic, SyntacticAnnotationIdentity]
    ]()
    val importedShortNames = IdentityHashMap[Tree, String]()

    tree match
      case pkg: PackageDef =>
        analyzePackageStats(pkg.stats, Map.empty, resolutions, importedShortNames)
      case other =>
        recordAnnotations(
          other,
          Map.empty,
          LocalImportBarrier.Empty,
          resolutions,
          importedShortNames
        )

    ExplicitImportAnnotationIdentityResolver(resolutions, importedShortNames)

  private def analyzePackageStats(
      stats: List[Tree],
      initialImports: ActiveImports,
      resolutions: IdentityHashMap[
        Tree,
        Either[ExplicitImportAnnotationIdentityDiagnostic, SyntacticAnnotationIdentity]
      ],
      importedShortNames: IdentityHashMap[Tree, String]
  )(using Context): Unit =
    stats.foldLeft(initialImports): (activeImports, stat) =>
      stat match
        case importTree: Import => addExplicitImports(activeImports, importTree)
        case nestedPackage: PackageDef =>
          analyzePackageStats(
            nestedPackage.stats,
            activeImports,
            resolutions,
            importedShortNames
          )
          activeImports
        case other =>
          recordAnnotations(
            other,
            activeImports,
            LocalImportBarrier.Empty,
            resolutions,
            importedShortNames
          )
          activeImports
    ()

  private def recordAnnotations(
      tree: Tree,
      activeImports: ActiveImports,
      localImportBarrier: LocalImportBarrier,
      resolutions: IdentityHashMap[
        Tree,
        Either[ExplicitImportAnnotationIdentityDiagnostic, SyntacticAnnotationIdentity]
      ],
      importedShortNames: IdentityHashMap[Tree, String]
  )(using Context): Unit =
    def record(annotation: Tree): Unit =
      val resolution = resolve(annotation, activeImports, localImportBarrier)
      resolutions.put(annotation, resolution)
      for
        raw <- SyntacticAnnotationIdentity.fromTree(annotation)
        resolved <- resolution.toOption
        if !raw.isQualified && resolved.isQualified
      do importedShortNames.put(annotation, raw.value)

    tree match
      case typeDef: TypeDef =>
        Trees.mods(typeDef).annotations.foreach(record)
        typeDef.rhs match
          case template: Template =>
            analyzeNestedStats(
              template.body(using summon[Context]),
              activeImports,
              localImportBarrier,
              resolutions,
              importedShortNames
            )
          case _ => ()
      case moduleDef: ModuleDef =>
        Trees.mods(moduleDef).annotations.foreach(record)
        analyzeNestedStats(
          moduleDef.impl.body(using summon[Context]),
          activeImports,
          localImportBarrier,
          resolutions,
          importedShortNames
        )
      case _ => ()

  private def analyzeNestedStats(
      stats: List[Tree],
      activeImports: ActiveImports,
      initialBarrier: LocalImportBarrier,
      resolutions: IdentityHashMap[
        Tree,
        Either[ExplicitImportAnnotationIdentityDiagnostic, SyntacticAnnotationIdentity]
      ],
      importedShortNames: IdentityHashMap[Tree, String]
  )(using Context): Unit =
    stats.foldLeft(initialBarrier): (barrier, stat) =>
      stat match
        case importTree: Import => addLocalImportBarrier(barrier, importTree)
        case other =>
          recordAnnotations(
            other,
            activeImports,
            barrier,
            resolutions,
            importedShortNames
          )
          barrier
    ()

  private def resolve(
      annotation: Tree,
      activeImports: ActiveImports,
      localImportBarrier: LocalImportBarrier
  )(using Context): Either[ExplicitImportAnnotationIdentityDiagnostic, SyntacticAnnotationIdentity] =
    SyntacticAnnotationIdentity.fromTree(annotation) match
      case Some(identity) if identity.isQualified => Right(identity)
      case Some(identity) if localImportBarrier.blocks(identity.value) =>
        Left(
          ExplicitImportAnnotationIdentityDiagnostic(
            s"unsupported local/nested import scope for short annotation `@${identity.value}`; use a qualified annotation",
            annotation.sourcePos
          )
        )
      case Some(identity) =>
        activeImports.getOrElse(identity.value, Set.empty).toList.sorted match
          case Nil => Right(identity)
          case candidate :: Nil =>
            SyntacticAnnotationIdentity.fromDeclaredName(candidate).left.map: detail =>
              ExplicitImportAnnotationIdentityDiagnostic(detail, annotation.sourcePos)
          case candidates =>
            Left(
              ExplicitImportAnnotationIdentityDiagnostic(
                s"ambiguous explicit-import annotation identity for `@${identity.value}`; candidates: ${candidates.mkString(", ")}; use a qualified annotation",
                annotation.sourcePos
              )
            )
      case None =>
        Left(
          ExplicitImportAnnotationIdentityDiagnostic(
            "unsupported raw annotation identity; expected a simple or qualified identifier chain",
            annotation.sourcePos
          )
        )

  private def addLocalImportBarrier(
      barrier: LocalImportBarrier,
      importTree: Import
  ): LocalImportBarrier =
    if importTree.selectors.isEmpty then
      referenceSegments(importTree.expr) match
        case Some(segments) if segments.nonEmpty =>
          barrier.copy(names = barrier.names + segments.last)
        case _ => barrier.copy(blocksAllShortNames = true)
    else
      importTree.selectors.foldLeft(barrier): (current, selector) =>
        if selector.isWildcard || selector.isGiven || selector.isUnimport then
          current.copy(blocksAllShortNames = true)
        else
          val localName = selector.rename.toString
          current.copy(names = current.names + localName)

  private def addExplicitImports(
      activeImports: ActiveImports,
      importTree: Import
  ): ActiveImports =
    val candidates =
      if importTree.selectors.isEmpty then
        referenceSegments(importTree.expr).toList.flatMap: segments =>
          if segments.size >= 2 then List(segments.last -> segments.mkString("."))
          else Nil
      else
        referenceSegments(importTree.expr).toList.flatMap: prefix =>
          importTree.selectors.collect:
            case selector
                if !selector.isWildcard &&
                  !selector.isGiven &&
                  !selector.isUnimport &&
                  selector.name == selector.rename =>
              val localName = selector.name.toString
              localName -> (prefix :+ localName).mkString(".")

    candidates
      // `paradise3` is the product's reserved legacy marker namespace. Its
      // established handlers intentionally declare simple identities, and the
      // metadata reader already owns that compatibility mapping.
      .filterNot((_, canonicalName) => canonicalName.startsWith("paradise3."))
      .foldLeft(activeImports):
      case (imports, (localName, canonicalName)) =>
        imports.updated(
          localName,
          imports.getOrElse(localName, Set.empty) + canonicalName
        )

  private def referenceSegments(tree: Tree): Option[List[String]] =
    tree match
      case Ident(name) => Some(List(name.toString))
      case Select(qualifier, name) =>
        referenceSegments(qualifier).map(_ :+ name.toString)
      case _ => None
