package macroparadise

import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.ast.Trees
import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags.{Enum, Param, Trait}
import dotty.tools.dotc.core.Names.*
import dotty.tools.dotc.report
import paradise3.api.*

import java.util.IdentityHashMap
import scala.collection.mutable
import scala.util.control.NonFatal

/** One staged-tree scheduler for built-in and externally loaded handlers. */
private[macroparadise] object ParadiseTreeRewrite:
  private final case class Handler(
      instance: ExpansionHandler,
      identity: String,
      trace: Option[ExternalHandlerInvocationTrace]
  )

  private final case class Occurrence(
      primaryIndex: Int,
      primary: ExpansionTarget,
      annotation: Tree,
      handler: Handler
  )

  private final case class Relationship(
      primaryIndex: Int,
      primary: ExpansionTarget,
      companionIndex: Option[Int],
      companion: Option[ExpansionTarget]
  )

  private final case class Failure(category: String, detail: String, pos: dotty.tools.dotc.util.SrcPos):
    def render(annotationName: String, handler: String, target: String): String =
      ExternalHandlerDiagnostics.render(
        ExternalHandlerDiagnostics.Stage.Invocation,
        category,
        "annotation" -> s"@$annotationName",
        "handler" -> handler,
        "target" -> target,
        "detail" -> detail
      )

  def containsTopLevelClassAnnotation(tree: Tree, annotationName: String)(using Context): Boolean =
    tree match
      case pkg: PackageDef =>
        pkg.stats.exists:
          case value: TypeDef => annotations(value).exists(annotationSyntax(_).contains(annotationName))
          case value: ModuleDef => annotations(value).exists(annotationSyntax(_).contains(annotationName))
          case _ => false
      case _ => false

  def rewriteUnit(
      unit: CompilationUnit,
      loaded: ExternalHandlerLoading.LoadedHandlers,
      expansionBudget: Int
  )(using Context): Tree =
    given resolver: ExplicitImportAnnotationIdentityResolver =
      ExplicitImportAnnotationIdentityResolver.fromUnitTree(unit.untpdTree)
    val discovery = ExternalHandlerLoading.discoverMetadataHandlers(
      collectAnnotationIdentityRequests(unit.untpdTree),
      loaded
    )
    discovery.legacySimpleRequests.foreach(resolver.preferLegacySimpleIdentity)
    val external = dedupe(loaded.explicit ++ discovery.handlers).collect:
      case value: LoadedExternalHandler =>
        Handler(value.instance, value.handlerClassName, Some(loaded.invocationTrace))
    val handlers = builtIns ++ external

    unit.untpdTree match
      case pkg: PackageDef =>
        schedule(pkg.stats, handlers, expansionBudget) match
          case Left(failure) =>
            report.error(failure.detail, failure.pos)
            unit.untpdTree
          case Right(staged) =>
            cpy.PackageDef(pkg)(pkg.pid, staged)
      case other => other

  private[macroparadise] def scheduleForTesting(
      stats: List[Tree],
      instances: List[ExpansionHandler],
      expansionBudget: Int = ExpansionBudget.Default
  )(using Context): Either[String, List[Tree]] =
    val packageTree = PackageDef(Ident(termName("scheduler_test")), stats)
    given resolver: ExplicitImportAnnotationIdentityResolver =
      ExplicitImportAnnotationIdentityResolver.fromUnitTree(packageTree)
    schedule(
      stats,
      instances.map(value => Handler(value, value.getClass.getName, None)),
      expansionBudget
    ).left.map(_.detail)

  private[macroparadise] def scheduleAtomicallyForTesting(
      stats: List[Tree],
      instances: List[ExpansionHandler],
      expansionBudget: Int = ExpansionBudget.Default
  )(using Context): (List[Tree], Option[String]) =
    scheduleForTesting(stats, instances, expansionBudget) match
      case Right(value) => (value, None)
      case Left(detail) => (stats, Some(detail))

  private def schedule(
      original: List[Tree],
      handlers: List[Handler],
      expansionBudget: Int
  )(using Context, ExplicitImportAnnotationIdentityResolver): Either[Failure, List[Tree]] =
    val consumed = IdentityHashMap[Tree, java.lang.Boolean]()

    def loop(staged: List[Tree], successful: Int): Either[Failure, List[Tree]] =
      firstOccurrence(staged, handlers, consumed) match
        case Left(failure) => Left(failure)
        case Right(None) => Right(staged)
        case Right(Some(occurrence)) if successful >= expansionBudget =>
          Left(
            Failure(
              "EXPANSION_BUDGET_EXCEEDED",
              s"staged expansion exceeded the $expansionBudget-success budget; recent target `${occurrence.primary.name}`",
              occurrence.annotation.sourcePos
            )
          )
        case Right(Some(occurrence)) =>
          relationship(staged, occurrence.primaryIndex, occurrence.primary).flatMap: relation =>
            val container = PluginInvocationMinting.container(
              occupiedDefinitionNames(staged)
            )
            val input = PluginInvocationMinting.input(
              relation.primary,
              relation.companion,
              container,
              occurrence.annotation
            )
            occurrence.handler.trace.foreach(
              _.record(
                occurrence.handler.identity,
                occurrence.handler.instance.annotationName,
                relation.primary.name
              )
            )
            val outcome =
              try Right(occurrence.handler.instance.expand(input))
              catch
                case NonFatal(error) =>
                  Left(
                    Failure(
                      "HANDLER_INVOCATION_FAILURE",
                      s"${error.getClass.getName}: ${Option(error.getMessage).getOrElse("<no-message>")}",
                      occurrence.annotation.sourcePos
                    )
                  )
            outcome.flatMap: result =>
              applyOutcome(staged, relation, result, occurrence).flatMap: next =>
                consumed.put(occurrence.annotation, java.lang.Boolean.TRUE)
                loop(next, successful + 1)

    loop(original, 0)

  private def firstOccurrence(
      stats: List[Tree],
      handlers: List[Handler],
      consumed: IdentityHashMap[Tree, java.lang.Boolean]
  )(using Context, ExplicitImportAnnotationIdentityResolver): Either[Failure, Option[Occurrence]] =
    var result: Option[Occurrence] = None
    var failure: Option[Failure] = None
    var index = 0
    while index < stats.size && result.isEmpty && failure.isEmpty do
      ExpansionTarget.fromTree(stats(index)) match
        case Right(target) =>
          val currentAnnotations = target match
            case ExpansionTarget.Class(value)  => annotations(value)
            case ExpansionTarget.Trait(value)  => annotations(value)
            case ExpansionTarget.Object(value) => annotations(value)
          var annotationIndex = 0
          while annotationIndex < currentAnnotations.size && result.isEmpty && failure.isEmpty do
            val annotation = currentAnnotations(annotationIndex)
            if !consumed.containsKey(annotation) then
              handlers.find(value => annotationMatches(annotation, value.instance.annotationName)) match
                case Some(handler) =>
                  result = Some(Occurrence(index, target, annotation, handler))
                case None => ()
            annotationIndex += 1
        case Left(_) => ()
      index += 1
    failure match
      case Some(value) => Left(value)
      case None        => Right(result)

  private def relationship(
      stats: List[Tree],
      primaryIndex: Int,
      primary: ExpansionTarget
  )(using Context): Either[Failure, Relationship] =
    val candidates = stats.zipWithIndex.flatMap: (tree, index) =>
      if index == primaryIndex then None
      else
        ExpansionTarget.fromTree(tree).toOption.filter(value =>
          value.name == primary.name && compatible(primary.kind, value.kind)
        ).map(index -> _)
    candidates match
      case Nil => Right(Relationship(primaryIndex, primary, None, None))
      case (index, value) :: Nil => Right(Relationship(primaryIndex, primary, Some(index), Some(value)))
      case many =>
        Left(
          Failure(
            "AMBIGUOUS_COMPANION_TOPOLOGY",
            s"target `${primary.name}` has ${many.size} compatible companion candidates in the current staged tree",
            primary.tree.sourcePos
          )
        )

  private def compatible(left: ExpansionTargetKind, right: ExpansionTargetKind): Boolean =
    (left == ExpansionTargetKind.Object) != (right == ExpansionTargetKind.Object)

  private def applyOutcome(
      stats: List[Tree],
      relation: Relationship,
      outcome: ExpansionOutcome | Null,
      occurrence: Occurrence
  )(using Context): Either[Failure, List[Tree]] =
    if outcome == null then fail("NULL_OUTCOME", "handler returned null", occurrence)
    else
      outcome match
        case ExpansionOutcome.Rejected(diagnostics) =>
          if diagnostics == null || diagnostics.isEmpty then
            fail("EMPTY_REJECTION", "handler rejection must contain at least one diagnostic", occurrence)
          else
            diagnostics.foreach: diagnostic =>
              if diagnostic != null then report.error(diagnostic.message, diagnostic.pos)
            fail("HANDLER_REJECTED", "handler rejected the admitted invocation", occurrence)
        case ExpansionOutcome.Expanded(trees) =>
          applyRaw(stats, relation, trees, occurrence)
        case ExpansionOutcome.Structured(changes) =>
          applyStructured(stats, relation, changes, occurrence)

  private def applyRaw(
      stats: List[Tree],
      relation: Relationship,
      trees: List[Tree] | Null,
      occurrence: Occurrence
  )(using Context): Either[Failure, List[Tree]] =
    if trees == null then fail("NULL_RAW_OUTPUT", "raw output list is null", occurrence)
    else if trees.exists(_ == null) then fail("NULL_RAW_ELEMENT", "raw output contains a null tree", occurrence)
    else if trees.exists(tree => ExpansionTarget.fromTree(tree).isLeft) then
      fail("UNSUPPORTED_RAW_ELEMENT", "raw output accepts only supported class, trait, or object definitions", occurrence)
    else if trees.exists(tree => !hasRootProvenance(tree)) then
      fail("MISSING_OUTPUT_PROVENANCE", "raw output contains a definition with neither source nor span provenance", occurrence)
    else
      val owned = Set(relation.primaryIndex) ++ relation.companionIndex
      val anchor = owned.min
      val result = stats.zipWithIndex.flatMap: (tree, index) =>
        if index == anchor then trees
        else if owned(index) then Nil
        else List(tree)
      validateFinal(result, occurrence)

  private def applyStructured(
      stats: List[Tree],
      relation: Relationship,
      changes: ExpansionChanges | Null,
      occurrence: Occurrence
  )(using Context): Either[Failure, List[Tree]] =
    if changes == null then fail("NULL_CHANGES", "structured changes are null", occurrence)
    else if changes.primary == null || changes.companion == null || changes.siblings == null then
      fail("INCOMPLETE_CHANGES", "structured changes contain a null lane", occurrence)
    else
      for
        primaryReplacement <- applyPrimary(relation.primary, changes.primary, occurrence)
        companionPlan <- applyCompanion(relation.companion, changes.companion, occurrence)
        siblingPlans <- applySiblings(stats, changes.siblings, occurrence)
        _ <- validateCreationIntent(primaryReplacement, companionPlan, siblingPlans, occurrence)
        result <- spliceStructured(stats, relation, primaryReplacement, companionPlan, siblingPlans, occurrence)
      yield result

  private final case class Planned(value: Option[Tree], placement: Option[DefinitionPlacement])
  private final case class SiblingPlan(index: Option[Int], value: Option[Tree], placement: Option[DefinitionPlacement])

  private def applyPrimary(
      current: ExpansionTarget,
      change: PrimaryChange,
      occurrence: Occurrence
  )(using Context): Either[Failure, Option[Tree]] =
    change match
      case PrimaryChange.Preserve => Right(Some(current.tree))
      case PrimaryChange.Delete   => Right(None)
      case PrimaryChange.Replace(value) => validateReplacement(current, value, "primary", occurrence).map(value => Some(value.tree))
      case PrimaryChange.Merge(patches) => applyPatches(current, patches, occurrence).map(value => Some(value.tree))

  private def applyCompanion(
      current: Option[ExpansionTarget],
      change: CompanionChange,
      occurrence: Occurrence
  )(using Context): Either[Failure, Planned] =
    change match
      case CompanionChange.Preserve => Right(Planned(current.map(_.tree), None))
      case CompanionChange.Delete =>
        if current.isEmpty then fail("MISSING_COMPANION", "cannot delete a missing companion", occurrence)
        else Right(Planned(None, None))
      case CompanionChange.Merge(patches) =>
        current match
          case None => fail("MISSING_COMPANION", "cannot merge a missing companion", occurrence)
          case Some(value) => applyPatches(value, patches, occurrence).map(next => Planned(Some(next.tree), None))
      case CompanionChange.Replace(value) =>
        current match
          case None => fail("MISSING_COMPANION", "cannot replace a missing companion", occurrence)
          case Some(existing) => validateReplacement(existing, value, "companion", occurrence).map(next => Planned(Some(next.tree), None))
      case CompanionChange.Create(value, placement) =>
        if current.nonEmpty then fail("COMPANION_ALREADY_EXISTS", "cannot create a companion when one already exists", occurrence)
        else if value == null || placement == null then fail("INVALID_COMPANION_CREATE", "companion creation contains null data", occurrence)
        else if !hasRootProvenance(value.tree) then fail("MISSING_OUTPUT_PROVENANCE", "created companion has neither source nor span provenance", occurrence)
        else Right(Planned(Some(value.tree), Some(placement)))

  private def applySiblings(
      stats: List[Tree],
      changes: List[SiblingChange],
      occurrence: Occurrence
  )(using Context): Either[Failure, List[SiblingPlan]] =
    changes.foldLeft[Either[Failure, List[SiblingPlan]]](Right(Nil)):
      case (acc, change) =>
        acc.flatMap: plans =>
          if change == null then fail("NULL_SIBLING_CHANGE", "sibling changes contain null", occurrence)
          else
            change match
              case SiblingChange.Create(value, placement) =>
                if value == null || placement == null then fail("INVALID_SIBLING_CREATE", "sibling creation contains null data", occurrence)
                else if !hasRootProvenance(value.tree) then fail("MISSING_OUTPUT_PROVENANCE", "created sibling has neither source nor span provenance", occurrence)
                else Right(plans :+ SiblingPlan(None, Some(value.tree), Some(placement)))
              case SiblingChange.Merge(ref, patches) =>
                resolveSibling(stats, ref, occurrence).flatMap: (index, value) =>
                  applyPatches(value, patches, occurrence).map(next => plans :+ SiblingPlan(Some(index), Some(next.tree), None))
              case SiblingChange.Replace(ref, value) =>
                resolveSibling(stats, ref, occurrence).flatMap: (index, existing) =>
                  validateReplacement(existing, value, "sibling", occurrence).map(next => plans :+ SiblingPlan(Some(index), Some(next.tree), None))
              case SiblingChange.Delete(ref) =>
                resolveSibling(stats, ref, occurrence).map: (index, _) =>
                  plans :+ SiblingPlan(Some(index), None, None)

  private def resolveSibling(
      stats: List[Tree],
      ref: SiblingRef | Null,
      occurrence: Occurrence
  )(using Context): Either[Failure, (Int, ExpansionTarget)] =
    if ref == null then fail("NULL_SIBLING_REF", "sibling reference is null", occurrence)
    else
      val matches = stats.zipWithIndex.flatMap: (tree, index) =>
        ExpansionTarget.fromTree(tree).toOption.filter(value => value.name == ref.name && value.kind == ref.kind).map(index -> _)
      matches match
        case value :: Nil => Right(value)
        case Nil => fail("MISSING_SIBLING", s"sibling `${ref.name}` of kind ${ref.kind} does not exist", occurrence)
        case many => fail("AMBIGUOUS_SIBLING", s"sibling `${ref.name}` resolves to ${many.size} current definitions", occurrence)

  private def applyPatches(
      original: ExpansionTarget,
      patches: List[TargetPatch] | Null,
      occurrence: Occurrence
  )(using Context): Either[Failure, ExpansionTarget] =
    if patches == null || patches.isEmpty then fail("EMPTY_PATCH_LIST", "merge requires a nonempty patch list", occurrence)
    else
      patches.foldLeft[Either[Failure, ExpansionTarget]](Right(original)):
        case (acc, patch) =>
          acc.flatMap: current =>
            if patch == null then fail("NULL_PATCH", "merge contains a null patch", occurrence)
            else applyPatch(current, patch, occurrence)

  private def applyPatch(
      target: ExpansionTarget,
      patch: TargetPatch,
      occurrence: Occurrence
  )(using Context): Either[Failure, ExpansionTarget] =
    patch match
      case TargetPatch.AppendMembers(members) =>
        if members == null || members.isEmpty || members.exists(value => value == null || !value.isInstanceOf[MemberDef]) then
          fail("INVALID_MEMBER_PATCH", "AppendMembers requires a nonempty list of non-null member definitions", occurrence)
        else if members.exists(value => !hasRootProvenance(value)) then
          fail("MISSING_OUTPUT_PROVENANCE", "AppendMembers contains a definition with neither source nor span provenance", occurrence)
        else rewriteTemplate(target, template => cpy.Template(template)(template.constr, template.parentsOrDerived, template.derived, template.self, template.body ++ members), occurrence)
      case TargetPatch.ReplaceAnnotations(values) =>
        if values == null || values.exists(_ == null) then fail("INVALID_ANNOTATION_PATCH", "ReplaceAnnotations requires a non-null list without null trees", occurrence)
        else Right(withAnnotations(target, values))
      case TargetPatch.SetSelf(value) =>
        if value == null then fail("INVALID_SELF_PATCH", "SetSelf Option is null", occurrence)
        else rewriteTemplate(target, template => cpy.Template(template)(template.constr, template.parentsOrDerived, template.derived, value.getOrElse(EmptyValDef), template.body), occurrence)

  private def rewriteTemplate(
      target: ExpansionTarget,
      update: Template => Template,
      occurrence: Occurrence
  )(using Context): Either[Failure, ExpansionTarget] =
    target match
      case ExpansionTarget.Class(value) => Right(ExpansionTarget.Class(cpy.TypeDef(value)(value.name, update(value.rhs.asInstanceOf[Template]))))
      case ExpansionTarget.Trait(value) => Right(ExpansionTarget.Trait(cpy.TypeDef(value)(value.name, update(value.rhs.asInstanceOf[Template]))))
      case ExpansionTarget.Object(value) => Right(ExpansionTarget.Object(cpy.ModuleDef(value)(value.name, update(value.impl))))

  private def withAnnotations(target: ExpansionTarget, values: List[Tree])(using Context): ExpansionTarget =
    target match
      case ExpansionTarget.Class(value) => ExpansionTarget.Class(value.withMods(Trees.mods(value).withAnnotations(values)).asInstanceOf[TypeDef])
      case ExpansionTarget.Trait(value) => ExpansionTarget.Trait(value.withMods(Trees.mods(value).withAnnotations(values)).asInstanceOf[TypeDef])
      case ExpansionTarget.Object(value) => ExpansionTarget.Object(value.withMods(Trees.mods(value).withAnnotations(values)).asInstanceOf[ModuleDef])

  private def validateReplacement(
      current: ExpansionTarget,
      replacement: ExpansionTarget | Null,
      lane: String,
      occurrence: Occurrence
  )(using Context): Either[Failure, ExpansionTarget] =
    if replacement == null || replacement.tree == null then fail("NULL_REPLACEMENT", s"$lane replacement is null", occurrence)
    else
      ExpansionTarget.fromTree(replacement.tree) match
        case Left(diagnostic) => fail("INVALID_REPLACEMENT", s"$lane replacement is unsupported: ${diagnostic.message}", occurrence)
        case Right(actual) if actual.kind != replacement.kind =>
          fail("MISLABELED_REPLACEMENT", s"$lane replacement wrapper ${replacement.kind} disagrees with raw tree kind ${actual.kind}", occurrence)
        case Right(_) if !hasRootProvenance(replacement.tree) =>
          fail("MISSING_OUTPUT_PROVENANCE", s"$lane replacement has neither source nor span provenance", occurrence)
        case Right(_) => Right(replacement)

  private def validateCreationIntent(
      resultingPrimary: Option[Tree],
      companion: Planned,
      siblings: List[SiblingPlan],
      occurrence: Occurrence
  )(using Context): Either[Failure, Unit] =
    val finalPrimary = resultingPrimary.flatMap(ExpansionTarget.fromTree(_).toOption)
    companion.placement match
      case Some(_) =>
        (finalPrimary, companion.value.flatMap(ExpansionTarget.fromTree(_).toOption)) match
          case (Some(primary), Some(created))
              if primary.name == created.name && compatible(primary.kind, created.kind) => Right(())
          case (None, _) => fail("COMPANION_CREATE_WITHOUT_PRIMARY", "CompanionChange.Create requires a surviving resulting primary", occurrence)
          case _ => fail("INVALID_FINAL_COMPANION", "CompanionChange.Create must create the real companion of the resulting primary", occurrence)
      case None =>
        finalPrimary match
          case None => Right(())
          case Some(primary) =>
            val disguised = siblings.filter(_.index.isEmpty).flatMap(_.value).flatMap(ExpansionTarget.fromTree(_).toOption).find(value =>
              value.name == primary.name && compatible(primary.kind, value.kind)
            )
            disguised match
              case Some(_) => fail("SIBLING_CREATES_COMPANION", "SiblingChange.Create cannot create the resulting primary's companion", occurrence)
              case None    => Right(())

  private def spliceStructured(
      stats: List[Tree],
      relation: Relationship,
      primary: Option[Tree],
      companion: Planned,
      siblings: List[SiblingPlan],
      occurrence: Occurrence
  )(using Context): Either[Failure, List[Tree]] =
    val replacements = mutable.Map.empty[Int, Option[Tree]]
    replacements += relation.primaryIndex -> primary
    relation.companionIndex.foreach(index => replacements += index -> companion.value)
    val addressedSiblings = siblings.flatMap(plan => plan.index.map(_ -> plan.value))
    addressedSiblings.find((index, _) => replacements.contains(index) || addressedSiblings.count(_._1 == index) > 1) match
      case Some((index, _)) =>
        fail("OVERLAPPING_CHANGES", s"more than one lane addresses staged index $index", occurrence)
      case None =>
        addressedSiblings.foreach(replacements += _)
        val createdSiblings = siblings.filter(_.index.isEmpty)
        if createdSiblings.exists(_.placement.isEmpty) then
          fail("MISSING_PLACEMENT", "created sibling is missing placement", occurrence)
        else
          val before = mutable.ListBuffer.empty[Tree]
          val after = mutable.ListBuffer.empty[Tree]
          companion.placement.foreach:
            case DefinitionPlacement.BeforePrimary => companion.value.foreach(before += _)
            case DefinitionPlacement.AfterPrimary  => companion.value.foreach(after += _)
          createdSiblings.foreach: plan =>
            plan.placement.foreach:
              case DefinitionPlacement.BeforePrimary => plan.value.foreach(before += _)
              case DefinitionPlacement.AfterPrimary  => plan.value.foreach(after += _)

          val result = stats.zipWithIndex.flatMap: (tree, index) =>
            if index == relation.primaryIndex then before.toList ++ replacements(index).toList ++ after.toList
            else replacements.get(index).fold(List(tree))(_.toList)
          validateFinal(result, occurrence)

  private def validateFinal(
      stats: List[Tree],
      occurrence: Occurrence
  )(using Context): Either[Failure, List[Tree]] =
    val identities = IdentityHashMap[Tree, String]()
    var alias: Tree | Null = null
    var aliasPaths: Option[(String, String)] = None

    def record(tree: Tree, path: String): Boolean =
      if isCanonicalEmpty(tree) then false
      else if identities.containsKey(tree) then
        alias = tree
        aliasPaths = Some(identities.get(tree) -> path)
        true
      else
        identities.put(tree, path)
        false

    def visitDefinition(tree: Tree, path: String): Unit =
      if alias == null && !record(tree, path) then
        tree match
          case definition: DefTree =>
            annotations(definition).zipWithIndex.foreach((annotation, index) => record(annotation, s"$path.annotation[$index]"))
            definition match
              case value: TypeDef =>
                value.rhs match
                  case template: Template => visitTemplate(template, s"$path.template")
                  case _ => ()
              case value: ModuleDef => visitTemplate(value.impl, s"$path.template")
              case value: DefDef =>
                value.paramss.flatten.zipWithIndex.foreach((parameter, index) => visitDefinition(parameter, s"$path.parameter[$index]"))
                value.rhs match
                  case nested: DefTree => visitDefinition(nested, s"$path.rhs")
                  case _ => ()
              case _ => ()
          case _ => ()

    def visitTemplate(template: Template, path: String): Unit =
      if alias == null && !record(template, path) then
        if !isCanonicalEmpty(template.self) then visitDefinition(template.self, s"$path.self")
        template.body.zipWithIndex.foreach:
          case (nested: DefTree, index) => visitDefinition(nested, s"$path.body[$index]")
          case _ => ()

    stats.zipWithIndex.foreach((tree, index) => visitDefinition(tree, s"root[$index]"))
    if alias != null then
      fail(
        "ALIASED_OUTPUT",
        s"the same noncanonical raw tree object occurs more than once in staged output: ${alias.getClass.getName} at ${aliasPaths.getOrElse("unknown" -> "unknown")}",
        occurrence
      )
    else
      val invalidName = namedDefinitions(stats).collectFirst:
        case (name, values) if values.size > 2 => name
        case (name, first :: second :: Nil) if !compatible(first.kind, second.kind) => name
      invalidName match
        case Some(name) => fail("NAME_COLLISION", s"staged output contains an illegal definition collision for `$name`", occurrence)
        case None       => Right(stats)

  private def namedDefinitions(stats: List[Tree])(using Context): Map[String, List[ExpansionTarget]] =
    stats.flatMap(ExpansionTarget.fromTree(_).toOption).groupBy(_.name)

  private def occupiedDefinitionNames(stats: List[Tree]): Set[String] =
    stats.collect:
      case definition: MemberDef => definition.name.toString
    .toSet

  private def hasRootProvenance(tree: Tree): Boolean =
    tree.source.exists || tree.span.exists

  private def isCanonicalEmpty(tree: Tree)(using Context): Boolean =
    (tree eq EmptyTree) || (tree eq EmptyValDef) || (tree eq emptyConstructor)

  private def annotations(tree: DefTree)(using Context): List[Tree] = Trees.mods(tree).annotations

  private def annotationSyntax(tree: Tree)(using Context): Option[String] =
    SyntacticAnnotationIdentity.fromTree(tree).map(_.value)

  private def annotationMatches(tree: Tree, expected: String)(using Context, ExplicitImportAnnotationIdentityResolver): Boolean =
    summon[ExplicitImportAnnotationIdentityResolver].identityOfUsingWitnesses(tree, Nil).toOption.exists(_.value == expected)

  private def collectAnnotationIdentityRequests(
      tree: Tree
  )(using Context, ExplicitImportAnnotationIdentityResolver): Set[ExplicitImportAnnotationIdentityRequest] =
    val result = Set.newBuilder[ExplicitImportAnnotationIdentityRequest]
    def loop(current: Tree): Unit = current match
      case value: TypeDef =>
        annotations(value).foreach(annotation => summon[ExplicitImportAnnotationIdentityResolver].requestOf(annotation).foreach(result += _))
        value.rhs match
          case template: Template => template.body.foreach(loop)
          case _ => ()
      case value: ModuleDef =>
        annotations(value).foreach(annotation => summon[ExplicitImportAnnotationIdentityResolver].requestOf(annotation).foreach(result += _))
        value.impl.body.foreach(loop)
      case value: PackageDef => value.stats.foreach(loop)
      case _ => ()
    loop(tree)
    result.result()

  private def dedupe(values: List[LoadedExternalHandlerContract]): List[LoadedExternalHandlerContract] =
    val seen = mutable.Set.empty[String]
    values.filter: value =>
      val key = s"${value.handlerClassName}:${value.annotationName}"
      seen.add(key)

  private def fail[A](category: String, detail: String, occurrence: Occurrence)(using Context): Left[Failure, A] =
    Left(Failure(category, detail, occurrence.annotation.sourcePos))

  private def builtIns: List[Handler] = List(
    Handler(GenHandler, "macroparadise.builtin.gen", None),
    Handler(DebugHandler, "macroparadise.builtin.debug", None)
  )

  private object GenHandler extends ExpansionHandler:
    val annotationName = "gen"
    def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
      input.primary match
        case ExpansionTarget.Class(value) =>
          val rejection = AnnotatedClassAdmission.decode(value) match
            case Left(value) => Some(value)
            case Right(view) =>
              AnnotatedClassAdmission.commonRejection(view, "@gen")
                .orElse(AnnotatedClassAdmission.genRejection(view))
          rejection match
            case Some(rejection) => ExpansionOutcome.Rejected(List(ExpansionDiagnostic(rejection.message, rejection.pos)))
            case None =>
              val siblingName = s"${value.name}Meta"
              if input.container.occupiedDefinitionNames.contains(siblingName) then
                ExpansionOutcome.Rejected(List(ExpansionDiagnostic(s"generated sibling `$siblingName` already exists", value.sourcePos)))
              else
                val primary = append(value, generatedHello(value.source))
                val companion = input.companion match
                  case Some(ExpansionTarget.Object(existing)) => append(existing, generatedFactory(value.name))
                  case _ => ModuleDef(value.name.toTermName, emptyTemplate(value.source, List(generatedFactory(value.name))))
                val sibling = TypeDef(typeName(siblingName), emptyTemplate(value.source, Nil))
                ExpansionOutcome.Structured(
                  ExpansionChanges(
                    primary = PrimaryChange.Replace(ExpansionTarget.Class(strip(primary, input.currentAnnotation))),
                    companion = input.companion match
                      case Some(_) => CompanionChange.Replace(ExpansionTarget.Object(companion))
                      case None => CompanionChange.Create(ExpansionTarget.Object(companion), DefinitionPlacement.AfterPrimary),
                    siblings = List(SiblingChange.Create(ExpansionTarget.Class(sibling), DefinitionPlacement.AfterPrimary))
                  )
                )
        case _ => ExpansionOutcome.Rejected(List(ExpansionDiagnostic("@gen requires a class primary", input.currentAnnotation.sourcePos)))

  private object DebugHandler extends ExpansionHandler:
    val annotationName = "debug"
    def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
      input.primary match
        case ExpansionTarget.Class(value) =>
          val rejection = AnnotatedClassAdmission.decode(value) match
            case Left(value) => Some(value)
            case Right(view) => AnnotatedClassAdmission.commonRejection(view, "@debug")
          rejection match
            case Some(value) => ExpansionOutcome.Rejected(List(ExpansionDiagnostic(value.message, value.pos)))
            case None =>
              val rewritten = append(value, DefDef(termName("debugName"), Nil, Ident(typeName("String")), Literal(Constant(value.name.toString))))
              ExpansionOutcome.Structured(ExpansionChanges(primary = PrimaryChange.Replace(ExpansionTarget.Class(strip(rewritten, input.currentAnnotation)))))
        case _ => ExpansionOutcome.Rejected(List(ExpansionDiagnostic("@debug requires a class primary", input.currentAnnotation.sourcePos)))

  private def append(value: TypeDef, member: Tree)(using Context): TypeDef =
    val template = value.rhs.asInstanceOf[Template]
    cpy.TypeDef(value)(value.name, cpy.Template(template)(template.constr, template.parentsOrDerived, template.derived, template.self, template.body :+ member))

  private def append(value: ModuleDef, member: Tree)(using Context): ModuleDef =
    val template = value.impl
    val exists = template.body.exists:
      case definition: MemberDef => definition.name == member.asInstanceOf[MemberDef].name
      case _ => false
    if exists then value
    else cpy.ModuleDef(value)(value.name, cpy.Template(template)(template.constr, template.parentsOrDerived, template.derived, template.self, template.body :+ member))

  private def strip(value: TypeDef, annotation: Tree)(using Context): TypeDef =
    value.withMods(Trees.mods(value).withAnnotations(annotations(value).filterNot(_ eq annotation))).asInstanceOf[TypeDef]

  private def generatedHello(source: dotty.tools.dotc.util.SourceFile)(using Context): DefDef =
    given dotty.tools.dotc.util.SourceFile = source
    DefDef(termName("generatedHello"), Nil, Ident(typeName("String")), Apply(Select(Literal(Constant("hello ")), termName("+")), Ident(termName("name"))))

  private def generatedFactory(name: TypeName)(using Context): DefDef =
    val parameter = ValDef(termName("name"), Ident(typeName("String")), EmptyTree).withMods(Modifiers(Param))
    DefDef(termName("generatedFactory"), List(List(parameter)), Ident(name), Apply(Select(New(Ident(name)), termName("<init>")), List(Ident(termName("name")))))

  private def emptyTemplate(source: dotty.tools.dotc.util.SourceFile, body: List[Tree])(using Context): Template =
    given dotty.tools.dotc.util.SourceFile = source
    Template(emptyConstructor, Nil, Nil, EmptyValDef, body)
