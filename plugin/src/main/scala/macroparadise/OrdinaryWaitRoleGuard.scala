package macroparadise

import dotty.tools.dotc.ast.Trees
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.util.SrcPos

/** Read-only admission for one explicitly designated physical ordinary unit.
  * This is not a consumer selector or semantic annotation resolver. Unresolved
  * short names, renamed/nested imports and local shadows fail closed; unrelated
  * qualified annotations and unambiguous package explicit imports remain legal.
  */
private[macroparadise] object OrdinaryWaitRoleGuard:
  final case class Diagnostic(message: String, pos: SrcPos)
  private final case class ImportBinding(canonical: String, unsupported: Boolean)
  private final case class Scope(
      imports: Map[String, Set[ImportBinding]] = Map.empty,
      wildcard: Boolean = false,
      shadows: Set[String] = Set.empty,
      packageLevel: Boolean = true
  )

  def validate(tree: Tree, configuredNames: Vector[String])(using Context): Either[Diagnostic, Unit] =
    val configured = configuredNames.toSet
    val resolver = ExplicitImportAnnotationIdentityResolver.fromUnitTree(tree)
    var failure: Option[Diagnostic] = None

    def reject(annotation: Tree, code: String, detail: String): Unit =
      if failure.isEmpty then failure = Some(Diagnostic(s"$code: $detail", annotation.sourcePos))

    def ambiguous(annotation: Tree, detail: String): Unit =
      reject(annotation, "SAME_MODULE_ORDINARY_WAIT_ANNOTATION_IDENTITY", detail)

    def record(annotation: Tree, scope: Scope): Unit =
      def selected(name: String): Unit =
        reject(annotation, "SAME_MODULE_ORDINARY_WAIT_ANNOTATED",
          s"designated ordinary source contains configured annotation candidate `@$name`")
      SyntacticAnnotationIdentity.fromTree(annotation) match
        case None => ambiguous(annotation, "unsupported annotation identity in designated ordinary source")
        case Some(raw) =>
          val rooted = raw.value.startsWith("_root_.")
          val name = raw.value.stripPrefix("_root_.")
          if configured(name) then selected(name)
          else if raw.isQualified then
            val first = name.takeWhile(_ != '.')
            val suffix = name.drop(first.length)
            val bindings = if rooted then Set.empty[ImportBinding] else scope.imports.getOrElse(first, Set.empty)
            val candidates = bindings.map(b => b.canonical + suffix).filter(configured)
            if candidates.nonEmpty then selected(candidates.toVector.sorted.head)
            else if !rooted && (scope.shadows(first) || bindings.nonEmpty) then
              ambiguous(annotation, s"qualified annotation `@${raw.value}` has an unverified local/imported qualifier")
          else
            val bindings = scope.imports.getOrElse(name, Set.empty)
            val candidates = bindings.map(_.canonical).filter(configured)
            if candidates.nonEmpty then selected(candidates.toVector.sorted.head)
            else if bindings.size != 1 || bindings.exists(_.unsupported) || scope.shadows(name) then
              ambiguous(annotation, s"short annotation `@$name` requires an unshadowed source-preceding explicit package import")
            else
              // Use the existing resolver when its package/template authority
              // covers this occurrence. Method-local occurrences instead use
              // the source-preceding immutable import scope carried below.
              resolver.identityOf(annotation) match
                case Right(identity) if configured(identity.value) => selected(identity.value)
                case Left(diagnostic) => ambiguous(annotation, diagnostic.message)
                case _ => ()

    def segments(reference: Tree): Option[List[String]] = reference match
      case Ident(name) => Some(List(name.toString))
      case Select(prefix, name) => segments(prefix).map(_ :+ name.toString)
      case _ => None

    def addImport(scope: Scope, imp: Import): Scope =
      val prefix = if imp.expr.isEmpty then Some(Nil) else segments(imp.expr)
      // Raw prefix concatenation cannot discharge imported/local authority.
      // Preserve uncertainty without resolving or substituting alias names.
      val uncertainPrefix = prefix.exists: parts =>
        parts.headOption.exists(first => first != "_root_" &&
          (scope.imports.contains(first) || scope.shadows(first)))
      def add(current: Scope, local: String, canonical: String, renamed: Boolean): Scope =
        val binding = ImportBinding(canonical.stripPrefix("_root_."),
          renamed || !scope.packageLevel || uncertainPrefix)
        current.copy(imports = current.imports.updated(local, current.imports.getOrElse(local, Set.empty) + binding))
      // The parser represents `import demo as renamed` with EmptyTree and
      // a named selector. This is syntactic import evidence, not a Symbol.
      prefix match
        case None => scope.copy(wildcard = true)
        case Some(prefix) if imp.selectors.isEmpty =>
          add(scope, prefix.last, prefix.mkString("."), renamed = false)
        case Some(prefix) =>
          imp.selectors.foldLeft(scope): (current, selector) =>
            if selector.isWildcard || selector.isGiven || selector.isUnimport then current.copy(wildcard = true)
            else add(current, selector.rename.toString, (prefix :+ selector.name.toString).mkString("."), selector.name != selector.rename)

    def names(stats: List[Tree]): Set[String] = stats.flatMap:
      case member: MemberDef => List(member.name.toString)
      // A later root-prefix import does not resolve an earlier annotation.
      // Retain its spelling only as an ambiguity barrier, never as authority.
      case imp: Import if imp.expr.isEmpty =>
        imp.selectors.filterNot(s => s.isWildcard || s.isUnimport).map(_.rename.toString)
      case _ => Nil
    .toSet

    def walkStats(stats: List[Tree], initial: Scope): Scope =
      stats.foldLeft(initial.copy(shadows = initial.shadows ++ names(stats))): (scope, stat) =>
        stat match
          case imp: Import =>
            walk(imp.expr, scope)
            addImport(scope, imp)
          case other => walk(other, scope); scope

    def walk(current: Tree, scope: Scope): Unit =
      if failure.isEmpty then
        current match
          case member: MemberDef =>
            Trees.mods(member).annotations.foreach: annotation =>
              record(annotation, scope)
              walk(annotation, scope) // Annotation arguments can themselves contain declarations.
          case pattern: PatDef =>
            pattern.mods.annotations.foreach: annotation =>
              record(annotation, scope)
              walk(annotation, scope)
          case Annotated(_, annotation) => record(annotation, scope)
          case _ => ()
        if failure.isEmpty then
          current match
            case pkg: PackageDef => walkStats(pkg.stats, scope)
            case block: Block =>
              val after = walkStats(block.stats, scope.copy(packageLevel = false))
              walk(block.expr, after)
            case template: Template =>
              val nested = scope.copy(packageLevel = false, shadows = scope.shadows ++ names(template.body))
              walk(template.constr, nested)
              template.parents.foreach(walk(_, nested))
              template.derived.foreach(walk(_, nested))
              walk(template.self, nested)
              walkStats(template.body, nested)
            case method: DefDef =>
              val params = method.paramss.flatten
              val nested = scope.copy(packageLevel = false, shadows = scope.shadows ++ names(params))
              params.foreach(walk(_, nested))
              walk(method.tpt, nested)
              walk(method.rhs, nested)
            case function: Function =>
              val nested = scope.copy(packageLevel = false, shadows = scope.shadows ++ names(function.args))
              function.args.foreach(walk(_, nested))
              walk(function.body, nested)
            case _ =>
              // Unlike the bounded consumer walker, visit every admitted raw
              // statement container, including untyped-only syntax. No copying,
              // transformation, symbols, handler calls or tree mutation occurs.
              object Children extends UntypedTreeTraverser:
                def traverse(child: Tree)(using Context): Unit = walk(child, scope)
                def scan(): Unit = traverseChildren(current)
              Children.scan()
    walk(tree, Scope())
    failure.toLeft(())
