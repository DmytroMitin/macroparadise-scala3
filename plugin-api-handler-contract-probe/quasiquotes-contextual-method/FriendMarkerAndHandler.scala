package quasiquotes.macroparadisecontextual

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import java.util.concurrent.atomic.AtomicInteger

import dotty.tools.dotc.ast.Trees
import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Names.*
import dotty.tools.dotc.core.Symbols.NoSymbol

import paradise3.api.{
  AnnotatedClassView,
  ExpansionDiagnostic,
  ExpansionInput,
  ExpansionOutcome,
  ExpansionTargetProfile,
  ParadiseAnnotationExpander,
  StructuredExpansionOutput,
  expander
}
import paradise3.api.helpers.ExpansionHelpers
import quasiquotes.definitions.dotty.{
  GeneratedOriginDefinitionResult,
  PublicContextualMethodGeneratedOriginAdapter
}
import quasiquotes.publicapi.{
  CompletedTerm,
  CompletedType,
  DefinitionConstruction,
  DefinitionResultView
}

import scala.annotation.StaticAnnotation

@expander("quasiquotes.macroparadisecontextual.PositionedContextualMethodHandler")
final class PositionedContextualApply extends StaticAnnotation

@expander("quasiquotes.macroparadisecontextual.MismatchedBindingHandler")
final class MismatchedPositionedContextualApply extends StaticAnnotation

private object HandlerLifecycleTrace:
  private val nextInstance = new AtomicInteger(0)

  def newInstance(): Int =
    val id = nextInstance.incrementAndGet()
    append(s"construct|instance=$id")
    id

  def append(value: String): Unit =
    sys.props.get("macroparadise.contextualMethodLifecycleTrace").foreach: rawPath =>
      Files.writeString(
        Path.of(rawPath),
        value + "\n",
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND
      )

final class PositionedContextualMethodHandler extends FriendHandlerBase:
  private val instanceId = HandlerLifecycleTrace.newInstance()

  val annotationName: String =
    HandlerLifecycleTrace.append(s"descriptor|instance=$instanceId|field=annotationName")
    "PositionedContextualApply"

  override val targetProfile: ExpansionTargetProfile =
    HandlerLifecycleTrace.append(s"descriptor|instance=$instanceId|field=targetProfile")
    ExpansionTargetProfile.RestrictedGenericTraitApply

  override val consumesExistingCompanion: Boolean =
    HandlerLifecycleTrace.append(
      s"descriptor|instance=$instanceId|field=consumesExistingCompanion"
    )
    true

  protected def recordExpansion(view: AnnotatedClassView): Unit =
    HandlerLifecycleTrace.append(
      s"expand|instance=$instanceId|target=${view.className}"
    )

final class MismatchedBindingHandler extends FriendHandlerBase:
  val annotationName: String = "DifferentPositionedContextualApply"
  override val targetProfile: ExpansionTargetProfile =
    ExpansionTargetProfile.RestrictedGenericTraitApply
  override val consumesExistingCompanion: Boolean = true
  protected def recordExpansion(view: AnnotatedClassView): Unit = ()

abstract class FriendHandlerBase extends ParadiseAnnotationExpander:
  protected def recordExpansion(view: AnnotatedClassView): Unit

  final def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionHelpers.withAnnotatedClassView(input): view =>
      recordExpansion(view)
      val outcome =
        for
          method <- construct(view)
          lowered <- lower(method, virtualSourceName(view))
          value <- Option(lowered).toRight("adapter returned a null result")
          tree <- validateGeneratedOrigin(value, method, view)
        yield tree
      outcome match
        case Right(method) =>
          recordIdentity(method, input, view)
          val primary = stripCurrentAnnotation(input)
          val companion =
            input.existingCompanion match
              case Some(existing) => mergeExactMethod(existing, method)
              case None => freshCompanion(input, method :: Nil)
          ExpansionOutcome.Structured(
            StructuredExpansionOutput(primary, Some(companion), Nil)
          )
        case Left(message) =>
          ExpansionOutcome.Rejected(
            List(
              ExpansionDiagnostic(
                s"Quasiquotes contextual-method friend rejection: $message",
                input.annotatedClass.sourcePos
              )
            ),
            input.annotatedClass
          )

  protected def traitName(view: AnnotatedClassView): String =
    if sys.props.get("macroparadise.contextualMethodFailureMode").contains("public-construction")
    then "invalid trait name"
    else view.className

  protected def virtualSourceName(view: AnnotatedClassView): String =
    if sys.props.get("macroparadise.contextualMethodFailureMode").contains("generated-origin")
    then ""
    else
      s"<macroparadise-contextual-method:${view.className}:${view.typeParameters.head.name}:apply>"

  protected def lower(
      method: DefinitionResultView,
      sourceName: String
  )(using Context): Either[String, GeneratedOriginDefinitionResult] =
    PublicContextualMethodGeneratedOriginAdapter
      .lower(method, sourceName)
      .left
      .map(_.message)

  private def construct(
      view: AnnotatedClassView
  ): Either[String, DefinitionResultView] =
    val typeParameterName = view.typeParameters.head.name
    for
      constructor <- CompletedType.named(traitName(view)).left.map(_.message)
      binder <- CompletedType.typeParameter(typeParameterName).left.map(_.message)
      applied <- CompletedType.applied(constructor, Vector(binder)).left.map(_.message)
      body <- CompletedTerm.reference("instance").left.map(_.message)
      method <- DefinitionConstruction
        .contextualMethod(
          name = "apply",
          typeParameterName = typeParameterName,
          contextualParameterName = "instance",
          contextualParameterType = applied,
          resultType = applied,
          body = body
        )
        .left
        .map(_.message)
    yield method

  private def validateGeneratedOrigin(
      result: GeneratedOriginDefinitionResult,
      method: DefinitionResultView,
      view: AnnotatedClassView
  )(using Context): Either[String, untpd.DefDef] =
    val binder = view.typeParameters.head.name
    val expected =
      s"def apply[$binder](using instance: ${view.className}[$binder]): ${view.className}[$binder] = instance"
    for
      _ <- requireInvariant(
        result.generatedSource == expected,
        s"generated source mismatch: `${result.generatedSource}`"
      )
      _ <- requireInvariant(
        result.virtualSourceName == virtualSourceName(view) &&
          result.virtualSourceName.nonEmpty,
        "virtual source identity mismatch"
      )
      tree <- result.tree match
        case definition: untpd.DefDef => Right(definition)
        case other =>
          Left(s"adapter returned ${other.getClass.getSimpleName}, expected DefDef")
      _ <- requireInvariant(
        tree.name.toString == method.name,
        "returned method name diverged from the public result"
      )
      _ <- validateTreeClosure(tree, result)
    yield tree

  private def validateTreeClosure(
      root: untpd.DefDef,
      result: GeneratedOriginDefinitionResult
  )(using Context): Either[String, Unit] =
    val errors = Vector.newBuilder[String]
    nonEmptyTrees(root).foreach: tree =>
      if !tree.source.exists || tree.source.path != result.virtualSourceName then
        errors += s"${tree.getClass.getSimpleName} lost generated source identity"
      if !tree.span.exists || tree.span.start < 0 ||
          tree.span.start > tree.span.point || tree.span.point > tree.span.end ||
          tree.span.end > result.generatedSource.length
      then errors += s"${tree.getClass.getSimpleName} has an invalid structural span"
      if tree.symbol != NoSymbol then
        errors += s"${tree.getClass.getSimpleName} gained a symbol before insertion"
      if tree.isInstanceOf[untpd.TypedSplice] then
        errors += "TypedSplice remained in the returned method"

    root.leadingTypeParams.head.rhs match
      case bounds: untpd.TypeBoundsTree =>
        if !bounds.span.exists || bounds.span.start != bounds.span.end then
          errors += "wildcard bounds do not use the parser-equivalent zero-width span"
        Vector(bounds.lo, bounds.hi).foreach: empty =>
          if !empty.isEmpty || empty.span.exists || empty.source.exists then
            errors += "empty wildcard-bound child gained source or span"
      case other =>
        errors += s"type parameter has ${other.getClass.getSimpleName}, expected bounds"

    root.trailingParamss.head.head match
      case parameter: untpd.ValDef =>
        if !parameter.rhs.isEmpty || parameter.rhs.span.exists ||
            parameter.rhs.source.exists
        then errors += "empty contextual-parameter RHS gained source or span"
      case other =>
        errors += s"contextual clause has ${other.getClass.getSimpleName}, expected ValDef"

    val found = errors.result()
    Either.cond(found.isEmpty, (), found.mkString("; "))

  private def requireInvariant(
      condition: Boolean,
      message: => String
  ): Either[String, Unit] =
    Either.cond(condition, (), message)

  private def nonEmptyTrees(tree: untpd.Tree)(using Context): Vector[untpd.Tree] =
    if tree.isEmpty then Vector.empty
    else tree +: directChildren(tree).flatMap(nonEmptyTrees)

  private def directChildren(tree: untpd.Tree)(using Context): Vector[untpd.Tree] =
    tree match
      case value: untpd.DefDef =>
        value.paramss.flatten.toVector ++ Vector(value.tpt, value.rhs)
      case value: untpd.TypeDef => Vector(value.rhs)
      case value: untpd.TypeBoundsTree => Vector(value.lo, value.hi)
      case value: untpd.ValDef => Vector(value.tpt, value.rhs)
      case value: untpd.AppliedTypeTree => value.tpt +: value.args.toVector
      case _ => Vector.empty

  private def stripCurrentAnnotation(input: ExpansionInput)(using Context): TypeDef =
    val currentMods = Trees.mods(input.annotatedClass)
    val preserved =
      input.currentAnnotation match
        case Some(current) => currentMods.annotations.filterNot(_ eq current)
        case None => Nil
    input.annotatedClass
      .withMods(currentMods.withAnnotations(preserved))
      .asInstanceOf[TypeDef]

  private def mergeExactMethod(
      existing: ModuleDef,
      method: untpd.DefDef
  )(using Context): ModuleDef =
    val template = existing.impl
    val existingBody = template.body(using summon[Context])
    val mergedBody =
      if existingBody.exists(directApply) then existingBody
      else existingBody :+ method
    val mergedTemplate =
      untpd.cpy.Template(template)(
        template.constr,
        template.parentsOrDerived(using summon[Context]),
        template.derived,
        template.self,
        mergedBody
      )
    untpd.cpy.ModuleDef(existing)(existing.name, mergedTemplate)

  private def directApply(tree: untpd.Tree): Boolean =
    tree match
      case member: MemberDef => member.name.toString == "apply"
      case _ => false

  private def freshCompanion(
      input: ExpansionInput,
      body: List[untpd.Tree]
  )(using Context): ModuleDef =
    given dotty.tools.dotc.util.SourceFile = input.annotatedClass.source
    ModuleDef(
      termName(input.className),
      Template(emptyConstructor, Nil, Nil, EmptyValDef, body)
    )

  private def recordIdentity(
      method: untpd.DefDef,
      input: ExpansionInput,
      view: AnnotatedClassView
  )(using Context): Unit =
    sys.props.get("macroparadise.contextualMethodIdentityTrace").foreach: rawPath =>
      val context = summon[Context]
      val values = Vector(
        "dotty.Context" -> classOf[dotty.tools.dotc.core.Contexts.Context],
        "untpd.Tree" -> classOf[dotty.tools.dotc.ast.Trees.Tree[?]],
        "untpd.DefDef.runtime" -> method.getClass,
        "DefinitionResultView" -> classOf[DefinitionResultView],
        "PublicContextualMethodGeneratedOriginAdapter" ->
          PublicContextualMethodGeneratedOriginAdapter.getClass,
        "GeneratedOriginDefinitionResult" -> classOf[GeneratedOriginDefinitionResult],
        "ParadiseAnnotationExpander" -> classOf[ParadiseAnnotationExpander],
        "independentHandler" -> getClass,
        "activeContext.runtime" -> context.getClass,
        "annotatedTree.runtime" -> input.annotatedClass.getClass,
        "AnnotatedClassView.runtime" -> view.getClass
      )
      val rendered = values.map: (label, clazz) =>
        val loader = Option(clazz.getClassLoader).fold("bootstrap")(_.toString)
        val source =
          Option(clazz.getProtectionDomain)
            .flatMap(domain => Option(domain.getCodeSource))
            .flatMap(codeSource => Option(codeSource.getLocation))
            .fold("none")(_.toString)
        s"$label|class=${clazz.getName}|loader=$loader|source=$source"
      Files.writeString(
        Path.of(rawPath),
        rendered.mkString("", "\n", "\n"),
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND
      )
