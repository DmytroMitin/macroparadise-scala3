package macroparadise.embedded

import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.ast.Trees
import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.Names.termName
import dotty.tools.dotc.parsing.Parsers
import dotty.tools.dotc.plugins.PluginPhase
import dotty.tools.dotc.report
import paradise3.api.ExpansionTargetView
import paradise3.api.ExpansionTargetView.{DefinitionKind, Variance}

/** Producer-only source phase for the frozen embedded declaration frontend. */
final class EmbeddedProducerPhase extends PluginPhase:
  override val phaseName = "macroparadiseEmbeddedProducer"
  override val description =
    "generates ordinary Macro Paradise ExpansionHandler adapters for opted-in annotation declarations"
  override def runsAfter = Set("parser")
  override def runsBefore = Set("typer")

  private var compilationTopLevelNames = Map.empty[String, Set[String]]
  private var compilationMarkerNames = Map.empty[String, Set[String]]
  private var compilationTransformNames = Map.empty[String, Map[String, String]]

  override def runOn(units: List[CompilationUnit])(using Context): List[CompilationUnit] =
    claimedCanonicalIdentities.clear()
    compilationTopLevelNames = units.foldLeft(Map.empty[String, Set[String]]):
      case (acc, unit) =>
        unit.untpdTree match
          case pkg: PackageDef =>
            val packageName = sourcePackageName(pkg.pid)
            val names = pkg.stats.collect:
              case definition: MemberDef => definition.name.toString
            acc.updated(
              packageName,
              acc.getOrElse(packageName, Set.empty) ++ names
            )
          case _ => acc
    compilationMarkerNames = Map.empty
    compilationTransformNames = Map.empty
    try
      val rewritten = super.runOn(units)
      splitMarkerUnits(rewriteCompilationReferences(rewritten))
    finally
      compilationTopLevelNames = Map.empty
      compilationMarkerNames = Map.empty
      compilationTransformNames = Map.empty
      claimedCanonicalIdentities.clear()

  override def run(using context: Context): Unit =
    val unit = context.compilationUnit
    unit.untpdTree match
      case pkg: PackageDef => unit.untpdTree = rewritePackage(pkg)
      case tree if containsPotentialOptIn(tree, ImportEvidence.Empty) =>
        fail(
          "EMBEDDED_TOPOLOGY",
          "embeddedExpander is supported only on a public top-level annotation class in a named package",
          tree
        )
      case _ => ()

  private val OptInIdentity = "paradise3.api.embeddedExpander"
  private val OptInSimpleName = "embeddedExpander"
  private val StaticAnnotationIdentity = "scala.annotation.StaticAnnotation"
  private val AdapterSuffix = "__MacroParadiseEmbeddedExpansionHandler"
  private val TransformSuffix = "__MacroParadiseEmbeddedTransform"
  private val claimedCanonicalIdentities =
    java.util.concurrent.ConcurrentHashMap.newKeySet[String]()


  private final case class ImportEvidence(
      explicit: Map[String, Set[String]],
      renamed: Map[String, Set[String]],
      wildcardPrefixes: Set[String]
  )

  private object ImportEvidence:
    val Empty = ImportEvidence(Map.empty, Map.empty, Set.empty)

  private final case class Generated(metadata: Tree, adapter: TypeDef)

  private def rewritePackage(pkg: PackageDef)(using Context): PackageDef =
    val packageName = sourcePackageName(pkg.pid)
    val topLevelNames = compilationTopLevelNames.getOrElse(packageName, Set.empty).toList
    val generated = List.newBuilder[Tree]
    val transformNames = scala.collection.mutable.Map.empty[String, String]
    var imports = ImportEvidence.Empty

    val rewritten = pkg.stats.map:
      case importTree: Import =>
        imports = addImport(imports, importTree)
        importTree
      case marker: TypeDef =>
        optInAnnotations(marker, imports, topLevelNames) match
          case Nil =>
            if containsPotentialOptIn(marker.rhs, imports) then
              fail(
                "EMBEDDED_TOPOLOGY",
                "embeddedExpander is supported only on the top-level annotation class, not on nested declarations",
                marker
              )
            marker
          case candidates =>
            val stripped = stripPotentialOptIns(marker, imports)
            val errors = candidates.collect { case Left(message) => message }
            if candidates.size != 1 then
              fail(
                "EMBEDDED_DUPLICATE_OPT_IN",
                s"annotation class `${marker.name}` has ${candidates.size} embeddedExpander opt-ins; exactly one is required",
                marker
              )
              stripped
            else if errors.nonEmpty then
              fail("EMBEDDED_OPT_IN_IDENTITY", errors.head, marker)
              stripped
            else
              val markerName = marker.name.toString
              val adapterName = adapterNameFor(markerName)
              val transformName = transformNameFor(markerName)
              validate(
                marker,
                packageName,
                pkg.stats,
                imports,
                adapterName,
                transformName,
                topLevelNames
              ) match
                case Left((code, detail, at)) =>
                  fail(code, detail, at)
                  stripped
                case Right(_) =>
                  val canonicalIdentity = s"$packageName.$markerName"
                  if !claimedCanonicalIdentities.add(canonicalIdentity) then
                    fail(
                      "EMBEDDED_DUPLICATE_CANONICAL_IDENTITY",
                      s"embedded annotation identity `$canonicalIdentity` is declared more than once in this producer compilation",
                      marker
                    )
                    stripped
                  else
                    val adapterFqcn = s"$packageName.$adapterName"
                    compilationMarkerNames = compilationMarkerNames.updated(
                      packageName,
                      compilationMarkerNames.getOrElse(packageName, Set.empty) + markerName
                    )
                    compilationTransformNames = compilationTransformNames.updated(
                      packageName,
                      compilationTransformNames.getOrElse(packageName, Map.empty)
                        .updated(markerName, transformName)
                    )
                    transformNames += markerName -> transformName
                    val parsed =
                      parseGenerated(packageName, markerName, transformName, adapterName, adapterFqcn)
                    generated += parsed.adapter
                    withMetadata(stripped, parsed.metadata)
      case module: ModuleDef =>
        if containsPotentialOptIn(module, imports) then
          fail(
            "EMBEDDED_TOPOLOGY",
            "embeddedExpander is supported only on a top-level annotation class, not an object or nested declaration",
            module
          )
        module
      case other =>
        if containsPotentialOptIn(other, imports) then
          fail(
            "EMBEDDED_TOPOLOGY",
            "embeddedExpander is supported only on a top-level annotation class",
            other
          )
        other

    val loweredCompanions = rewritten.map:
      case module: ModuleDef if transformNames.contains(module.name.toString) =>
        untpd.cpy.ModuleDef(module)(termName(transformNames(module.name.toString)), module.impl)
      case other => other

    untpd.cpy.PackageDef(pkg)(pkg.pid, loweredCompanions ++ generated.result())

  private def rewriteCompilationReferences(
      units: List[CompilationUnit]
  )(using Context): List[CompilationUnit] =
    units.map: unit =>
      unit.untpdTree match
        case pkg: PackageDef =>
          val unitContext = summon[Context].fresh.setCompilationUnit(unit)
          val packageName = sourcePackageName(pkg.pid)
          unit.untpdTree =
            rewriteCompanionReferences(pkg, packageName)(using unitContext)
          unit
        case _ => unit

  private final case class TransformTarget(
      packageName: String,
      sourceName: String,
      internalName: String
  )

  private def rewriteCompanionReferences(
      tree: Tree,
      packageName: String
  )(using Context): Tree =
    new untpd.UntypedTreeMap:
      private final case class ReferenceImport(
          explicit: Map[String, Set[String]],
          wildcardPrefixes: Set[String]
      )

      private final case class RewriteScope(
          shadowed: Set[String],
          imports: Vector[ReferenceImport]
      )

      private sealed trait ImportResolution
      private case object NoImportResolution extends ImportResolution
      private case object ImportBlocksRewrite extends ImportResolution
      private final case class ImportResolvesTo(target: TransformTarget)
          extends ImportResolution

      private var scopes = List(RewriteScope(Set.empty, Vector.empty))

      private def withScope[A](names: Set[String])(operation: => A): A =
        scopes = RewriteScope(names, Vector.empty) :: scopes
        try operation
        finally scopes = scopes.tail

      private def addShadowed(names: Set[String]): Unit =
        scopes = scopes.head.copy(shadowed = scopes.head.shadowed ++ names) :: scopes.tail

      private def mergeImports(
          left: Map[String, Set[String]],
          right: Map[String, Set[String]]
      ): Map[String, Set[String]] =
        (left.keySet ++ right.keySet).map: name =>
          name -> (left.getOrElse(name, Set.empty) ++ right.getOrElse(name, Set.empty))
        .toMap

      private def addReferenceImport(importTree: Import): Unit =
        val evidence = addImport(ImportEvidence.Empty, importTree)
        val entry = ReferenceImport(
          mergeImports(evidence.explicit, evidence.renamed),
          evidence.wildcardPrefixes
        )
        scopes = scopes.head.copy(imports = scopes.head.imports :+ entry) :: scopes.tail

      private def isShadowed(name: String): Boolean =
        scopes.exists(_.shadowed.contains(name))

      private def isPatternBinder(identifier: Ident): Boolean =
        identifier.name.isTermName &&
          identifier.name.toString != "_" &&
          identifier.name.toString.headOption.exists(_.isLower) &&
          !identifier.hasAttachment(Trees.Backquoted)

      private def patternNames(tree: Tree): Set[String] =
        tree match
          case bind: Bind if bind.name.isTermName =>
            patternNames(bind.body) + bind.name.toString
          case identifier: Ident if isPatternBinder(identifier) =>
            Set(identifier.name.toString)
          case typed: Typed => patternNames(typed.expr)
          case application: Apply => application.args.flatMap(patternNames).toSet
          case unapply: UnApply => unapply.patterns.flatMap(patternNames).toSet
          case alternative: Alternative => alternative.trees.flatMap(patternNames).toSet
          case tuple: Tuple => tuple.trees.flatMap(patternNames).toSet
          case parens: Parens => patternNames(parens.t)
          case named: NamedArg => patternNames(named.arg)
          case infix: InfixOp => patternNames(infix.left) ++ patternNames(infix.right)
          case sequence: SeqLiteral => sequence.elems.flatMap(patternNames).toSet
          case _ => Set.empty

      private def transformPattern(tree: Tree)(using Context): Tree =
        tree match
          case identifier: Ident if isPatternBinder(identifier) => identifier
          case bind: Bind =>
            untpd.cpy.Bind(bind)(bind.name, transformPattern(bind.body))
          case typed: Typed =>
            untpd.cpy.Typed(typed)(transformPattern(typed.expr), transform(typed.tpt))
          case application: Apply =>
            untpd.cpy.Apply(application)(
              transform(application.fun),
              application.args.map(transformPattern)
            )
          case unapply: UnApply =>
            untpd.cpy.UnApply(unapply)(
              transform(unapply.fun),
              transform(unapply.implicits),
              unapply.patterns.map(transformPattern)
            )
          case alternative: Alternative =>
            untpd.cpy.Alternative(alternative)(alternative.trees.map(transformPattern))
          case tuple: Tuple =>
            untpd.cpy.Tuple(tuple)(tuple.trees.map(transformPattern))
          case parens: Parens =>
            untpd.cpy.Parens(parens)(transformPattern(parens.t))
          case named: NamedArg =>
            untpd.cpy.NamedArg(named)(named.name, transformPattern(named.arg))
          case infix: InfixOp =>
            untpd.cpy.InfixOp(infix)(
              transformPattern(infix.left),
              infix.op,
              transformPattern(infix.right)
            )
          case sequence: SeqLiteral =>
            untpd.cpy.SeqLiteral(sequence)(
              sequence.elems.map(transformPattern),
              transform(sequence.elemtpt)
            )
          case other => transform(other)

      private def definitionNames(tree: Tree): Set[String] =
        tree match
          case definition: MemberDef if definition.name.isTermName =>
            Set(definition.name.toString)
          case module: ModuleDef => Set(module.name.toString)
          case pattern: PatDef => pattern.pats.flatMap(patternNames).toSet
          case _ => Set.empty

      private def memberNames(trees: List[Tree]): Set[String] =
        trees.flatMap(definitionNames).toSet

      private def parameterNames(parameters: List[Tree]): Set[String] =
        parameters.collect:
          case parameter: ValDef => parameter.name.toString
          case bind: Bind if bind.name.isTermName => bind.name.toString
        .toSet

      private def transformSequential(
          trees: List[Tree],
          definitionsEnterScope: Boolean
      )(using Context): List[Tree] =
        trees.map: current =>
          current match
            case importTree: Import =>
              addReferenceImport(importTree)
              untpd.cpy.Import(importTree)(
                transform(importTree.expr),
                importTree.selectors
              )
            case other =>
              val rewritten = transform(other)
              if definitionsEnterScope then addShadowed(definitionNames(other))
              rewritten

      private def targetForCanonical(identity: String): Option[TransformTarget] =
        val segments = identity.split('.').toList.filter(_.nonEmpty) match
          case "_root_" :: tail => tail
          case other => other
        segments match
          case Nil | _ :: Nil => None
          case _ =>
            val sourceName = segments.last
            val owner = segments.dropRight(1).mkString(".")
            compilationTransformNames
              .get(owner)
              .flatMap(_.get(sourceName))
              .map(TransformTarget(owner, sourceName, _))

      private def resolveImportInScope(
          scope: RewriteScope,
          name: String
      ): ImportResolution =
        val explicit = scope.imports.reverseIterator
          .flatMap(_.explicit.get(name))
          .find(_.nonEmpty)
        explicit match
          case Some(candidates) if candidates.size == 1 =>
            candidates.headOption.flatMap(targetForCanonical) match
              case Some(target) => ImportResolvesTo(target)
              case None => ImportBlocksRewrite
          case Some(_) => ImportBlocksRewrite
          case None =>
            val wildcardPrefixes = scope.imports.flatMap(_.wildcardPrefixes).distinct
            val wildcardTargets = wildcardPrefixes
              .flatMap(prefix => targetForCanonical(s"$prefix.$name"))
              .distinct
            if wildcardTargets.size == 1 && wildcardPrefixes.size == 1 then
              ImportResolvesTo(wildcardTargets.head)
            else if wildcardPrefixes.nonEmpty then ImportBlocksRewrite
            else NoImportResolution

      private def importedTarget(name: String): ImportResolution =
        scopes.iterator
          .map(resolveImportInScope(_, name))
          .find(_ != NoImportResolution)
          .getOrElse(NoImportResolution)

      private def targetForShort(name: String): Option[TransformTarget] =
        if isShadowed(name) then None
        else
          importedTarget(name) match
            case ImportResolvesTo(target) => Some(target)
            case ImportBlocksRewrite => None
            case NoImportResolution =>
              compilationTransformNames
                .get(packageName)
                .flatMap(_.get(name))
                .map(TransformTarget(packageName, name, _))

      private def targetForSelection(selection: Select): Option[TransformTarget] =
        referenceSegments(selection).flatMap: rawSegments =>
          val rooted = rawSegments.headOption.contains("_root_")
          val segments = if rooted then rawSegments.tail else rawSegments
          if segments.isEmpty || !rooted && isShadowed(segments.head) then None
          else targetForCanonical(segments.mkString("."))

      private def qualifiedReference(
          target: TransformTarget,
          original: Tree
      ): Tree =
        val segments =
          ("_root_" :: target.packageName.split('.').toList) :+ target.internalName
        segments.tail.foldLeft[Tree](
          Ident(termName(segments.head)).withSpan(original.span)
        ):
          case (qualifier, segment) =>
            Select(qualifier, termName(segment)).withSpan(original.span)

      private def transformEnumerators(
          enumerators: List[Tree]
      )(using Context): List[Tree] =
        enumerators.map:
          case generator: GenFrom =>
            val names = patternNames(generator.pat)
            val rewritten = untpd.cpy.GenFrom(generator)(
              transformPattern(generator.pat),
              transform(generator.expr),
              generator.checkMode
            )
            addShadowed(names)
            rewritten
          case alias: GenAlias =>
            val names = patternNames(alias.pat)
            val rewritten = untpd.cpy.GenAlias(alias)(
              transformPattern(alias.pat),
              transform(alias.expr)
            )
            addShadowed(names)
            rewritten
          case other => transform(other)

      override def transformStats(
          trees: List[Tree],
          exprOwner: dotty.tools.dotc.core.Symbols.Symbol
      )(using Context): List[Tree] =
        withScope(Set.empty)(transformSequential(trees, definitionsEnterScope = false))

      override def transformBlock(block: Block)(using Context): Block =
        withScope(Set.empty):
          untpd.cpy.Block(block)(
            transformSequential(block.stats, definitionsEnterScope = true),
            transform(block.expr)
          )

      override def transform(tree: Tree)(using Context): Tree =
        tree match
          case method: DefDef =>
            val parameters = method.termParamss.flatten.map(_.name.toString).toSet
            withScope(parameters + method.name.toString):
              untpd.cpy.DefDef(method)(
                method.name,
                transformParamss(method.paramss),
                transform(method.tpt),
                transform(method.rhs)
              )
          case function: Function =>
            val names = parameterNames(function.args)
            untpd.cpy.Function(function)(
              withScope(names)(transform(function.args)),
              withScope(names)(transform(function.body))
            )
          case caseDefinition: CaseDef =>
            val names = patternNames(caseDefinition.pat)
            untpd.cpy.CaseDef(caseDefinition)(
              transformPattern(caseDefinition.pat),
              withScope(names)(transform(caseDefinition.guard)),
              withScope(names)(transform(caseDefinition.body))
            )
          case template: Template =>
            val constructorParameters =
              template.constr.termParamss.flatten.map(_.name.toString).toSet
            val selfName =
              if template.self.name.isTermName && template.self.name.toString.nonEmpty then
                Set(template.self.name.toString)
              else Set.empty[String]
            withScope(constructorParameters ++ selfName ++ memberNames(template.body)):
              untpd.cpy.Template(template)(
                transformSub(template.constr),
                transform(template.parents),
                Nil,
                transformSub(template.self),
                transformSequential(template.body, definitionsEnterScope = false)
              )
          case forYield: ForYield =>
            withScope(Set.empty):
              untpd.cpy.ForYield(forYield)(
                transformEnumerators(forYield.enums),
                transform(forYield.expr)
              )
          case forDo: ForDo =>
            withScope(Set.empty):
              untpd.cpy.ForDo(forDo)(
                transformEnumerators(forDo.enums),
                transform(forDo.body)
              )
          case identifier: Ident
              if identifier.name.isTermName =>
            targetForShort(identifier.name.toString)
              .map(qualifiedReference(_, identifier))
              .getOrElse(identifier)
          case selection: Select
              if selection.name.isTermName =>
            targetForSelection(selection) match
              case Some(target) => qualifiedReference(target, selection)
              case None =>
                untpd.cpy.Select(selection)(
                  transform(selection.qualifier),
                  selection.name
                )
          case _ => super.transform(tree)
    .transform(tree)

  private def splitMarkerUnits(
      units: List[CompilationUnit]
  )(using Context): List[CompilationUnit] =
    units.flatMap: unit =>
      unit.untpdTree match
        case pkg: PackageDef =>
          val packageName = sourcePackageName(pkg.pid)
          val markerNames = compilationMarkerNames.getOrElse(packageName, Set.empty)
          val markers = pkg.stats.collect:
            case definition: TypeDef
                if markerNames.contains(definition.name.toString) =>
              definition
          if markers.isEmpty then List(unit)
          else
            val retainedImports = pkg.stats.collect:
              case importTree: Import if markerFacingImport(importTree) => importTree
            val handlerStats = pkg.stats.filter:
              case definition: TypeDef =>
                !markerNames.contains(definition.name.toString)
              case _ => true
            unit.untpdTree = untpd.cpy.PackageDef(pkg)(pkg.pid, handlerStats)
            val markerSource =
              s"package $packageName\n" +
                (retainedImports ++ markers)
                  .map(_.show.replaceAll("\\u001B\\[[;\\d]*m", ""))
                  .mkString("\n")
            val markerUnit = CompilationUnit(
              s"MacroParadiseEmbeddedMarker-${unit.source.name}",
              markerSource
            )
            val markerContext = summon[Context].fresh.setCompilationUnit(markerUnit)
            markerUnit.untpdTree =
              new Parsers.Parser(markerUnit.source)(using markerContext).parse()
            List(unit, markerUnit)
        case _ => List(unit)

  private def markerFacingImport(importTree: Import): Boolean =
    referenceSegments(importTree.expr) match
      case Some(rawSegments) =>
        val segments = rawSegments match
          case "_root_" :: tail => tail
          case other => other
        val internalTransformPaths =
          compilationTransformNames.iterator.flatMap: (owner, names) =>
            val ownerSegments = owner.split('.').toList.filter(_.nonEmpty)
            names.valuesIterator.map(ownerSegments :+ _)
        def startsWith(prefix: List[String]): Boolean =
          segments.take(prefix.size) == prefix
        val referencesInternalTransform =
          internalTransformPaths.exists(startsWith)
        !startsWith(List("paradise3", "api")) &&
          !startsWith(List("dotty", "tools", "dotc")) &&
          !referencesInternalTransform
      case None => true

  private def validate(
      marker: TypeDef,
      packageName: String,
      stats: List[Tree],
      imports: ImportEvidence,
      adapterName: String,
      transformName: String,
      topLevelNames: List[String]
  )(using Context): Either[(String, String, Tree), ModuleDef] =
    def reject(code: String, detail: String, at: Tree = marker) =
      Left((code, detail, at))

    val mods = Trees.mods(marker)
    val existingExpander =
      mods.annotations.exists(annotation => treeIdentity(annotation).contains("paradise3.api.expander") ||
        treeIdentity(annotation).contains("expander"))

    if packageName.isEmpty || packageName == "<empty>" || packageName == "_root_" then
      reject("EMBEDDED_NAMED_PACKAGE_REQUIRED", "embedded annotation declarations require a named package")
    else if existingExpander then
      reject(
        "EMBEDDED_METADATA_COLLISION",
        s"annotation class `${marker.name}` already declares expander metadata"
      )
    else if topLevelNames.contains(adapterName) || topLevelNames.contains(transformName) then
      val collision = if topLevelNames.contains(adapterName) then adapterName else transformName
      reject(
        "EMBEDDED_ADAPTER_COLLISION",
        s"generated implementation name `$collision` collides with an existing top-level definition"
      )
    else if !marker.isClassDef then
      reject("EMBEDDED_CLASS_REQUIRED", "embeddedExpander requires an ordinary class declaration")
    else if mods.flags.is(Flags.Private) || mods.flags.is(Flags.Protected) then
      reject("EMBEDDED_CLASS_VISIBILITY", "embedded annotation class must be public")
    else if !mods.flags.is(Flags.Final) then
      reject("EMBEDDED_FINAL_CLASS_REQUIRED", "embedded annotation class must be final")
    else if
      mods.flags.is(Flags.Abstract) ||
      mods.flags.is(Flags.Sealed) ||
      mods.flags.is(Flags.Case) ||
      mods.flags.is(Flags.Trait) ||
      mods.flags.is(Flags.Open)
    then
      reject(
        "EMBEDDED_CLASS_MODIFIERS",
        "embedded annotation class cannot be abstract, sealed, open, case, or a trait"
      )
    else
      ExpansionTargetView.decode(marker) match
        case Left(diagnostic) =>
          reject("EMBEDDED_CLASS_SHAPE", diagnostic.message)
        case Right(view) if view.definitionKind != DefinitionKind.Class =>
          reject("EMBEDDED_CLASS_REQUIRED", "embeddedExpander requires a class, not a trait")
        case Right(view) if
              view.typeParameters.exists(parameter =>
                parameter.variance != Variance.Invariant ||
                  !parameter.isOrdinaryUnbounded ||
                  parameter.hasContextBounds
              ) =>
          reject(
            "EMBEDDED_TYPE_PARAMETER_SHAPE",
            "embedded annotation type parameters must be ordinary, invariant, and unbounded"
          )
        case Right(view) if
              view.constructorClauses.size > 1 ||
              view.constructorClauses.exists(clause =>
                clause.isContextual ||
                  clause.parameters.exists(parameter =>
                    parameter.isContextual ||
                      parameter.isVal ||
                      parameter.isVar
                  )
              ) =>
          reject(
            "EMBEDDED_CONSTRUCTOR_SHAPE",
            "embedded annotation constructor parameters must form at most one ordinary clause of bare parameters; defaults remain unevaluated source syntax"
          )
        case Right(_) =>
          marker.rhs match
            case template: Template =>
              val parents = template.parents
              if
                parents.size != 1 ||
                !referenceMatches(parents.head, StaticAnnotationIdentity, "StaticAnnotation", imports)
              then
                reject(
                  "EMBEDDED_STATIC_ANNOTATION_PARENT",
                  "embedded annotation class must directly extend exactly scala.annotation.StaticAnnotation"
                )
              else if template.body.nonEmpty then
                reject(
                  "EMBEDDED_EMPTY_CLASS_BODY",
                  "embedded annotation class body must be empty in the first supported slice"
                )
              else
                val companions = stats.collect:
                  case module: ModuleDef if module.name.toString == marker.name.toString =>
                    module
                companions match
                  case Nil =>
                    reject(
                      "EMBEDDED_COMPANION_REQUIRED",
                      s"annotation class `${marker.name}` requires a same-file companion object"
                    )
                  case _ :: _ :: _ =>
                    reject(
                      "EMBEDDED_COMPANION_AMBIGUOUS",
                      s"annotation class `${marker.name}` has multiple companion candidates"
                    )
                  case companion :: Nil =>
                    validateCompanion(companion, imports).map(_ => companion)
            case _ =>
              reject(
                "EMBEDDED_CLASS_SHAPE",
                "embedded annotation class must have an ordinary template"
              )

  private def validateCompanion(
      companion: ModuleDef,
      imports: ImportEvidence
  )(using Context): Either[(String, String, Tree), Unit] =
    def reject(code: String, detail: String, at: Tree = companion) =
      Left((code, detail, at))
    val companionMods = Trees.mods(companion)
    if companionMods.flags.is(Flags.Private) || companionMods.flags.is(Flags.Protected) then
      reject("EMBEDDED_COMPANION_VISIBILITY", "embedded annotation companion must be public")
    else
      val namedMembers = companion.impl.body.collect:
        case definition: MemberDef if definition.name.toString == "transform" =>
          definition
      val transforms = namedMembers.collect { case definition: DefDef => definition }
      if namedMembers.size != 1 || transforms.size != 1 then
        reject(
          "EMBEDDED_TRANSFORM_COUNT",
          s"embedded annotation companion requires exactly one ordinary transform method; found ${namedMembers.size}",
          namedMembers.headOption.getOrElse(companion)
        )
      else
        val transform = transforms.head
        val mods = Trees.mods(transform)
        val typeParams = transform.leadingTypeParams
        val clauses = transform.termParamss
        if mods.flags.is(Flags.Private) || mods.flags.is(Flags.Protected) then
          reject("EMBEDDED_TRANSFORM_ACCESS", "embedded transform must be public", transform)
        else if mods.flags.is(Flags.Deferred) || transform.rhs.isEmpty then
          reject("EMBEDDED_TRANSFORM_CONCRETE", "embedded transform must be concrete", transform)
        else if typeParams.nonEmpty then
          reject(
            "EMBEDDED_TRANSFORM_TYPE_PARAMETERS",
            "embedded transform cannot declare type parameters",
            transform
          )
        else if clauses.map(_.size) != List(1, 1) then
          reject(
            "EMBEDDED_TRANSFORM_SIGNATURE",
            "embedded transform must have exactly (input)(using Context)",
            transform
          )
        else
          val input = clauses.head.head
          val context = clauses(1).head
          val inputMods = Trees.mods(input)
          val contextMods = Trees.mods(context)
          val inputOrdinary =
            !inputMods.flags.is(Flags.Given) && !inputMods.flags.is(Flags.Implicit)
          val contextUsing = contextMods.flags.is(Flags.Given)
          val noDefaults = input.rhs.isEmpty && context.rhs.isEmpty
          if
            input.name.toString != "input" ||
            !inputOrdinary ||
            !contextUsing ||
            !noDefaults ||
            !typeReferenceMatches(input.tpt, "paradise3.api.ExpansionInput", "ExpansionInput") ||
            !typeReferenceMatches(
              context.tpt,
              "dotty.tools.dotc.core.Contexts.Context",
              "Context"
            ) ||
            transform.tpt.isEmpty ||
            !typeReferenceMatches(
              transform.tpt,
              "paradise3.api.ExpansionOutcome",
              "ExpansionOutcome"
            )
          then
            reject(
              "EMBEDDED_TRANSFORM_SIGNATURE",
              "embedded transform must be exactly def transform(input: paradise3.api.ExpansionInput)(using dotty.tools.dotc.core.Contexts.Context): paradise3.api.ExpansionOutcome",
              transform
            )
          else Right(())

  private def parseGenerated(
      packageName: String,
      markerName: String,
      transformName: String,
      adapterName: String,
      adapterFqcn: String
  )(using outer: Context): Generated =
    val source =
      s"""package $packageName
         |@paradise3.api.expander("$adapterFqcn") final class MacroParadiseEmbeddedMetadataCarrier
         |final class $adapterName extends paradise3.api.ExpansionHandler:
         |  override val annotationName: String = "$packageName.$markerName"
         |  override def expand(
         |      input: paradise3.api.ExpansionInput
         |  )(using dotty.tools.dotc.core.Contexts.Context): paradise3.api.ExpansionOutcome =
         |    $transformName.transform(input)
         |""".stripMargin
    val unit = CompilationUnit("MacroParadiseEmbeddedGenerated.scala", source)
    given Context = outer.fresh.setCompilationUnit(unit)
    new Parsers.Parser(unit.source).parse() match
      case generatedPackage: PackageDef =>
        val carrier = generatedPackage.stats.collectFirst:
          case definition: TypeDef
              if definition.name.toString == "MacroParadiseEmbeddedMetadataCarrier" =>
            definition
        val adapter = generatedPackage.stats.collectFirst:
          case definition: TypeDef if definition.name.toString == adapterName =>
            definition
        (carrier, adapter) match
          case (Some(metadataCarrier), Some(generatedAdapter)) =>
            Trees.mods(metadataCarrier).annotations match
              case metadata :: Nil => Generated(metadata, generatedAdapter)
              case other =>
                throw IllegalStateException(
                  s"generated embedded metadata count was ${other.size}"
                )
          case _ =>
            throw IllegalStateException(
              "generated embedded adapter source did not parse to expected definitions"
            )
      case other =>
        throw IllegalStateException(
          s"generated embedded adapter source parsed as ${other.getClass.getName}"
        )

  private def optInAnnotations(
      marker: TypeDef,
      imports: ImportEvidence,
      topLevelNames: List[String]
  )(using Context): List[Either[String, Unit]] =
    Trees.mods(marker).annotations.flatMap(annotation =>
      optInCandidate(annotation, imports, topLevelNames)
    )

  private def optInCandidate(
      annotation: Tree,
      imports: ImportEvidence,
      topLevelNames: List[String]
  ): Option[Either[String, Unit]] =
    treeIdentity(annotation).flatMap: raw =>
      if raw == OptInIdentity then Some(Right(()))
      else if imports.renamed.getOrElse(raw, Set.empty).contains(OptInIdentity) then
        Some(Left("renamed embeddedExpander imports are unsupported; use the exact public name"))
      else if raw.contains(".") && raw.split('.').lastOption.contains(OptInSimpleName) then
        Some(Left(s"unrelated qualified opt-in `@$raw`; expected @$OptInIdentity"))
      else if raw == OptInSimpleName then
        val candidates = imports.explicit.getOrElse(raw, Set.empty)
        if topLevelNames.contains(raw) then
          Some(Left("short embeddedExpander is shadowed by a top-level definition"))
        else if candidates == Set(OptInIdentity) then Some(Right(()))
        else if candidates.size > 1 then
          Some(
            Left(
              s"short embeddedExpander import is ambiguous: ${candidates.toList.sorted.mkString(", ")}"
            )
          )
        else if imports.wildcardPrefixes.contains("paradise3.api") then
          Some(Left("wildcard embeddedExpander imports are unsupported; import it explicitly"))
        else
          Some(
            Left(
              "short embeddedExpander requires an explicit unaliased paradise3.api.embeddedExpander import"
            )
          )
      else None

  private def stripPotentialOptIns(
      marker: TypeDef,
      imports: ImportEvidence
  )(using Context): TypeDef =
    val mods = Trees.mods(marker)
    marker
      .withMods(
        mods.withAnnotations(
          mods.annotations.filter(annotation =>
            optInCandidate(annotation, imports, Nil).isEmpty
          )
        )
      )
      .asInstanceOf[TypeDef]

  private def withMetadata(marker: TypeDef, metadata: Tree)(using Context): TypeDef =
    val mods = Trees.mods(marker)
    marker.withMods(mods.withAnnotations(metadata :: mods.annotations)).asInstanceOf[TypeDef]

  private def addImport(
      imports: ImportEvidence,
      importTree: Import
  ): ImportEvidence =
    referenceSegments(importTree.expr) match
      case None => imports
      case Some(prefixOrPath) if importTree.selectors.isEmpty =>
        if prefixOrPath.size >= 2 then
          val local = prefixOrPath.last
          val canonical = prefixOrPath.mkString(".")
          imports.copy(
            explicit = add(imports.explicit, local, canonical)
          )
        else imports
      case Some(prefix) =>
        importTree.selectors.foldLeft(imports): (current, selector) =>
          if selector.isWildcard then
            current.copy(wildcardPrefixes = current.wildcardPrefixes + prefix.mkString("."))
          else if selector.isGiven || selector.isUnimport then current
          else
            val original = selector.name.toString
            val local = selector.rename.toString
            val canonical = (prefix :+ original).mkString(".")
            if original == local then
              current.copy(explicit = add(current.explicit, local, canonical))
            else
              current.copy(renamed = add(current.renamed, local, canonical))

  private def add(
      values: Map[String, Set[String]],
      key: String,
      value: String
  ): Map[String, Set[String]] =
    values.updated(key, values.getOrElse(key, Set.empty) + value)

  private def referenceMatches(
      tree: Tree,
      canonical: String,
      simple: String,
      imports: ImportEvidence
  ): Boolean =
    treeIdentity(tree).exists: raw =>
      raw == canonical ||
      raw == simple && imports.explicit.getOrElse(simple, Set.empty) == Set(canonical)

  private def typeReferenceMatches(
      tree: Tree,
      canonical: String,
      simple: String
  ): Boolean =
    treeIdentity(tree).exists(raw => raw == canonical || raw == simple)

  private def treeIdentity(tree: Tree): Option[String] =
    tree match
      case Apply(fn, _) => treeIdentity(fn)
      case TypeApply(fn, _) => treeIdentity(fn)
      case Select(qualifier, name) if name.toString == "<init>" =>
        treeIdentity(qualifier)
      case New(tpt) => treeIdentity(tpt)
      case AppliedTypeTree(tpt, _) => treeIdentity(tpt)
      case reference => referenceSegments(reference).map(_.mkString("."))

  private def referenceSegments(tree: Tree): Option[List[String]] =
    tree match
      case Ident(name) => Some(List(name.toString))
      case Select(qualifier, name) =>
        referenceSegments(qualifier).map(_ :+ name.toString)
      case _ => None

  private def containsPotentialOptIn(
      tree: Tree,
      imports: ImportEvidence
  )(using Context): Boolean =
    var found = false
    object traverser extends UntypedTreeTraverser:
      def traverse(current: Tree)(using Context): Unit =
        current match
          case definition: TypeDef =>
            if
              Trees.mods(definition).annotations.exists(annotation =>
                optInCandidate(annotation, imports, Nil).nonEmpty
              )
            then found = true
            else traverseChildren(current)
          case _ if !found => traverseChildren(current)
          case _ => ()
    traverser.traverse(tree)
    found

  private def adapterNameFor(markerName: String): String =
    markerName + AdapterSuffix

  private def transformNameFor(markerName: String): String =
    markerName + TransformSuffix

  private def sourcePackageName(tree: Tree): String =
    referenceSegments(tree).map(_.mkString(".")).getOrElse("")

  private def fail(code: String, detail: String, at: Tree)(using Context): Unit =
    report.error(s"$code: $detail", at.sourcePos)
