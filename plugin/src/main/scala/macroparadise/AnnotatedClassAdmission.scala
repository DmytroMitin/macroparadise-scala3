package macroparadise

import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.util.SrcPos
import paradise3.api.AnnotatedClassView
import paradise3.api.AnnotatedClassView.{ConstructorClause, DefinitionKind, Variance}

/** Plugin-owned admission policy over the shared raw syntactic class view.
  *
  * `AnnotatedClassView` owns normalization. This object owns only the common
  * annotation envelope, the current `@gen` support decision, diagnostic text,
  * and diagnostic position selection. No parameter flags or class modifiers
  * are decoded here.
  */
private[macroparadise] object AnnotatedClassAdmission:
  final case class Rejection(message: String, pos: SrcPos)

  def decode(typeDef: TypeDef)(using Context): Either[Rejection, AnnotatedClassView] =
    AnnotatedClassView.decode(typeDef).left.map(diagnostic => Rejection(diagnostic.message, diagnostic.pos))

  def commonRejection(
      view: AnnotatedClassView,
      annotationLabel: String
  ): Option[Rejection] =
    if view.definitionKind != DefinitionKind.Class then
      Some(
        Rejection(
          s"$annotationLabel currently supports only top-level classes; unsupported target `trait ${view.className}`",
          view.classPos
        )
      )
    else if view.modifiers.isCase then
      Some(
        Rejection(
          s"$annotationLabel currently supports ordinary non-case classes; unsupported class family `case class ${view.className}` is not supported by the current experimental contract",
          view.classPos
        )
      )
    else if view.typeParameters.nonEmpty then
      val count = view.typeParameters.size
      val noun = if count == 1 then "parameter" else "parameters"
      Some(
        Rejection(
          s"$annotationLabel currently supports non-generic classes; unsupported generic class shape `${view.className}` has $count raw type $noun",
          view.typeParameters.head.pos
        )
      )
    else None

  def restrictedGenericTraitApplyRejection(
      view: AnnotatedClassView,
      annotationLabel: String
  ): Option[Rejection] =
    val requirement =
      "requires one top-level non-sealed ordinary trait with exactly one invariant, ordinary unbounded type parameter and no constructor/value parameters"

    if view.definitionKind != DefinitionKind.Trait then
      Some(Rejection(s"$annotationLabel $requirement; found class `${view.className}`", view.classPos))
    else if view.modifiers.isCase then
      Some(Rejection(s"$annotationLabel $requirement; case modifiers are unsupported", view.classPos))
    else if view.modifiers.isSealed then
      Some(Rejection(s"$annotationLabel $requirement; sealed trait `${view.className}` is unsupported", view.classPos))
    else if view.typeParameters.size != 1 then
      Some(
        Rejection(
          s"$annotationLabel $requirement; found ${view.typeParameters.size} type parameters",
          view.typeParameters.headOption.map(_.pos).getOrElse(view.classPos)
        )
      )
    else
      val parameter = view.typeParameters.head
      if parameter.variance != Variance.Invariant then
        Some(
          Rejection(
            s"$annotationLabel $requirement; type parameter `${parameter.name}` is ${parameter.variance.toString.toLowerCase}",
            parameter.pos
          )
        )
      else if !parameter.isOrdinaryUnbounded || parameter.hasContextBounds then
        Some(
          Rejection(
            s"$annotationLabel $requirement; type parameter `${parameter.name}` has an explicit or contextual bound",
            parameter.pos
          )
        )
      else if view.constructorClauses.exists(_.parameters.nonEmpty) then
        val clause = view.constructorClauses.find(_.parameters.nonEmpty).get
        Some(
          Rejection(
            s"$annotationLabel $requirement; trait constructor/value parameters are unsupported",
            clause.pos
          )
        )
      else None

  def twoUpperBoundedGenericTraitRejection(
      view: AnnotatedClassView,
      annotationLabel: String
  ): Option[Rejection] =
    val requirement =
      "requires one top-level non-sealed ordinary trait with exactly two invariant, ordinary upper-bounded type parameters and no constructor/value parameters"

    if view.definitionKind != DefinitionKind.Trait then
      Some(Rejection(s"$annotationLabel $requirement; found class `${view.className}`", view.classPos))
    else if view.modifiers.isCase then
      Some(Rejection(s"$annotationLabel $requirement; case modifiers are unsupported", view.classPos))
    else if view.modifiers.isSealed then
      Some(Rejection(s"$annotationLabel $requirement; sealed trait `${view.className}` is unsupported", view.classPos))
    else if view.typeParameters.size != 2 then
      Some(
        Rejection(
          s"$annotationLabel $requirement; found ${view.typeParameters.size} type parameters",
          view.typeParameters.headOption.map(_.pos).getOrElse(view.classPos)
        )
      )
    else
      view.typeParameters.find(_.variance != Variance.Invariant) match
        case Some(parameter) =>
          Some(
            Rejection(
              s"$annotationLabel $requirement; type parameter `${parameter.name}` is ${parameter.variance.toString.toLowerCase}",
              parameter.pos
            )
          )
        case None =>
          view.typeParameters.find(parameter => !parameter.isOrdinaryUpperBounded || parameter.hasContextBounds) match
            case Some(parameter) =>
              Some(
                Rejection(
                  s"$annotationLabel $requirement; type parameter `${parameter.name}` is not an ordinary single upper-bounded parameter",
                  parameter.pos
                )
              )
            case None if view.constructorClauses.exists(_.parameters.nonEmpty) =>
              val clause = view.constructorClauses.find(_.parameters.nonEmpty).get
              Some(
                Rejection(
                  s"$annotationLabel $requirement; trait constructor/value parameters are unsupported",
                  clause.pos
                )
              )
            case None => None

  def plainZeroParameterTraitRejection(
      view: AnnotatedClassView,
      annotationLabel: String
  ): Option[Rejection] =
    val requirement =
      "requires one top-level non-sealed ordinary trait with zero type parameters and no constructor/value parameters"

    if view.definitionKind != DefinitionKind.Trait then
      Some(Rejection(s"$annotationLabel $requirement; found class `${view.className}`", view.classPos))
    else if view.modifiers.isCase then
      Some(Rejection(s"$annotationLabel $requirement; case modifiers are unsupported", view.classPos))
    else if view.modifiers.isSealed then
      Some(Rejection(s"$annotationLabel $requirement; sealed trait `${view.className}` is unsupported", view.classPos))
    else if view.typeParameters.nonEmpty then
      Some(
        Rejection(
          s"$annotationLabel $requirement; found ${view.typeParameters.size} type parameters",
          view.typeParameters.head.pos
        )
      )
    else if view.constructorClauses.exists(_.parameters.nonEmpty) then
      val clause = view.constructorClauses.find(_.parameters.nonEmpty).get
      Some(
        Rejection(
          s"$annotationLabel $requirement; trait constructor/value parameters are unsupported",
          clause.pos
        )
      )
    else None

  def genRejection(view: AnnotatedClassView): Option[Rejection] =
    val requirement =
      "current @gen prototype requires one non-contextual primary-constructor clause containing exactly `name: String` (bare or val, non-var, without a default) on a concrete class with an accessible constructor"

    if view.modifiers.isAbstract then
      Some(
        Rejection(
          s"unsupported class family for @gen on `${view.className}`: $requirement because `generatedFactory` constructs the annotated class",
          view.classPos
        )
      )
    else if view.modifiers.constructorIsPrivate then
      Some(
        Rejection(
          s"unsupported constructor shape for @gen on `${view.className}`: $requirement; the primary constructor is private",
          view.constructorPos
        )
      )
    else
      view.constructorClauses match
        case ConstructorClause(parameter :: Nil, false, _) :: Nil =>
          if parameter.name != "name" then
            Some(
              Rejection(
                s"unsupported constructor shape for @gen on `${view.className}`: $requirement; found parameter `${parameter.name}`",
                parameter.pos
              )
            )
          else if !isSimpleString(parameter.rawType) then
            Some(
              Rejection(
                s"unsupported constructor shape for @gen on `${view.className}`: $requirement; `name` has ${rawTypeDescription(parameter.rawType)} rather than the syntactic identifier `String`",
                parameter.typePos
              )
            )
          else if parameter.isVar then
            Some(
              Rejection(
                s"unsupported constructor shape for @gen on `${view.className}`: $requirement; mutable `var name` is outside the current contract",
                parameter.pos
              )
            )
          else if parameter.hasDefault then
            Some(
              Rejection(
                s"unsupported constructor shape for @gen on `${view.className}`: $requirement; defaulted constructor parameters are outside the current contract",
                parameter.pos
              )
            )
          else None
        case ConstructorClause(_, true, pos) :: _ =>
          Some(
            Rejection(
              s"unsupported constructor shape for @gen on `${view.className}`: $requirement; contextual or implicit constructor clauses are outside the current contract",
              pos
            )
          )
        case clauses =>
          val parameterCounts = clauses.map(_.parameters.size).mkString("[", ", ", "]")
          Some(
            Rejection(
              s"unsupported constructor shape for @gen on `${view.className}`: $requirement; found ${clauses.size} term parameter clause(s) with parameter counts $parameterCounts",
              clauses.drop(1).headOption.orElse(clauses.headOption).map(_.pos).getOrElse(view.constructorPos)
            )
          )

  def modifierSummary(view: AnnotatedClassView): List[String] =
    List(
      Option.when(view.modifiers.isCase)("case"),
      Option.when(view.modifiers.isAbstract)("abstract"),
      Option.when(view.modifiers.isFinal)("final"),
      Option.when(view.modifiers.isSealed)("sealed"),
      Option.when(view.modifiers.constructorIsPrivate)("private-constructor")
    ).flatten

  private def isSimpleString(tree: untpd.Tree): Boolean =
    tree match
      case Ident(name) => name.toString == "String"
      case _ => false

  private def rawTypeDescription(tree: untpd.Tree): String =
    tree match
      case Ident(name) => s"raw type identifier `${name.toString}`"
      case Select(_, name) => s"a qualified raw type ending in `${name.toString}`"
      case AppliedTypeTree(_, arguments) =>
        val noun = if arguments.size == 1 then "argument" else "arguments"
        s"an applied raw type with ${arguments.size} type $noun"
      case _ => "another unsupported raw type form"
