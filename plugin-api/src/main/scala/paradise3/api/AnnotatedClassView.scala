package paradise3.api

import dotty.tools.dotc.ast.Trees
import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags.{
  Abstract,
  Case,
  Enum,
  Final,
  Given,
  Implicit,
  Local,
  Mutable,
  ParamAccessor,
  Private,
  Contravariant,
  Covariant,
  Sealed,
  Trait
}
import dotty.tools.dotc.util.{NoSourcePosition, SrcPos}

import scala.util.control.NonFatal

/** Experimental read-only syntactic view of an annotated top-level class or trait.
  *
  * The view is decoded from the raw untyped class tree. It exposes only the
  * definition and primary-constructor evidence already needed by the current
  * experimental handlers and plugin admission rules. It performs no typing,
  * symbol or owner lookup, alias resolution, default evaluation, admission, or
  * expansion policy.
  *
  * `rawType` deliberately remains an untyped compiler tree because the current
  * syntactic contract needs exact raw type-shape evidence. Callers needing the
  * unrestricted class AST continue to use `ExpansionInput.annotatedClass`.
  * All fields and positions remain exact-compiler-sensitive experimental data.
  */
final case class AnnotatedClassView(
    className: String,
    typeParameters: List[AnnotatedClassView.TypeParameter],
    constructorClauses: List[AnnotatedClassView.ConstructorClause],
    modifiers: AnnotatedClassView.Modifiers,
    classPos: SrcPos,
    constructorPos: SrcPos,
    definitionKind: AnnotatedClassView.DefinitionKind = AnnotatedClassView.DefinitionKind.Class
):
  /** Retained exact-build constructor shape for legacy structured-view handlers. */
  def this(
      className: String,
      typeParameters: List[AnnotatedClassView.TypeParameter],
      constructorClauses: List[AnnotatedClassView.ConstructorClause],
      modifiers: AnnotatedClassView.Modifiers,
      classPos: SrcPos,
      constructorPos: SrcPos
  ) = this(
    className,
    typeParameters,
    constructorClauses,
    modifiers,
    classPos,
    constructorPos,
    AnnotatedClassView.DefinitionKind.Class
  )

  /** Retained exact-build copy shape for legacy structured-view handlers. */
  def copy(
      className: String,
      typeParameters: List[AnnotatedClassView.TypeParameter],
      constructorClauses: List[AnnotatedClassView.ConstructorClause],
      modifiers: AnnotatedClassView.Modifiers,
      classPos: SrcPos,
      constructorPos: SrcPos
  ): AnnotatedClassView =
    AnnotatedClassView(
      className,
      typeParameters,
      constructorClauses,
      modifiers,
      classPos,
      constructorPos,
      definitionKind
    )

object AnnotatedClassView:
  /** Retained exact-build factory shape for legacy structured-view handlers. */
  def apply(
      className: String,
      typeParameters: List[TypeParameter],
      constructorClauses: List[ConstructorClause],
      modifiers: Modifiers,
      classPos: SrcPos,
      constructorPos: SrcPos
  ): AnnotatedClassView =
    new AnnotatedClassView(
      className,
      typeParameters,
      constructorClauses,
      modifiers,
      classPos,
      constructorPos
    )

  enum DefinitionKind:
    case Class, Trait

  enum Variance:
    case Invariant, Covariant, Contravariant

  final case class TypeParameter(
      name: String,
      pos: SrcPos,
      variance: Variance = Variance.Invariant,
      isOrdinaryUnbounded: Boolean = true,
      hasContextBounds: Boolean = false,
      isOrdinaryUpperBounded: Boolean = false
  ):
    /** Retained exact-build constructor shape for legacy structured-view handlers. */
    def this(name: String, pos: SrcPos) =
      this(name, pos, Variance.Invariant, true, false, false)

    /** Retained exact-build constructor shape for existing normalized-view handlers. */
    def this(
        name: String,
        pos: SrcPos,
        variance: Variance,
        isOrdinaryUnbounded: Boolean,
        hasContextBounds: Boolean
    ) = this(name, pos, variance, isOrdinaryUnbounded, hasContextBounds, false)

    /** Retained exact-build copy shape for legacy structured-view handlers. */
    def copy(name: String, pos: SrcPos): TypeParameter =
      TypeParameter(name, pos, variance, isOrdinaryUnbounded, hasContextBounds, isOrdinaryUpperBounded)

    /** Retained exact-build copy shape for existing normalized-view handlers. */
    def copy(
        name: String,
        pos: SrcPos,
        variance: Variance,
        isOrdinaryUnbounded: Boolean,
        hasContextBounds: Boolean
    ): TypeParameter =
      TypeParameter(name, pos, variance, isOrdinaryUnbounded, hasContextBounds, isOrdinaryUpperBounded)

  object TypeParameter:
    /** Retained exact-build factory shape for legacy structured-view handlers. */
    def apply(name: String, pos: SrcPos): TypeParameter =
      new TypeParameter(name, pos)

    /** Retained exact-build factory shape for existing normalized-view handlers. */
    def apply(
        name: String,
        pos: SrcPos,
        variance: Variance,
        isOrdinaryUnbounded: Boolean,
        hasContextBounds: Boolean
    ): TypeParameter =
      new TypeParameter(name, pos, variance, isOrdinaryUnbounded, hasContextBounds)

    override def toString: String = "TypeParameter"

  final case class ConstructorClause(
      parameters: List[ConstructorParameter],
      isContextual: Boolean,
      pos: SrcPos
  )

  final case class ConstructorParameter(
      name: String,
      rawType: untpd.Tree,
      isVal: Boolean,
      isVar: Boolean,
      isContextual: Boolean,
      hasDefault: Boolean,
      pos: SrcPos,
      typePos: SrcPos
  )

  final case class Modifiers(
      isCase: Boolean,
      isAbstract: Boolean,
      isFinal: Boolean,
      isSealed: Boolean,
      constructorIsPrivate: Boolean
  )

  /** Decode one raw class tree without applying annotation admission policy.
    *
    * This method is public only as part of the exact-build experimental API so
    * the plugin and direct handler callers can share one normalization path.
    * Malformed or hostile inputs produce a controlled diagnostic rather than a
    * raw compiler exception.
    */
  def decode(tree: untpd.Tree | Null)(using Context): Either[ExpansionDiagnostic, AnnotatedClassView] =
    if tree == null then
      Left(failure("cannot decode a null annotated class tree", NoSourcePosition))
    else
      try
        tree match
          case typeDef: TypeDef => decodeTypeDef(typeDef)
          case moduleDef: ModuleDef =>
            Left(
              failure(
                s"unsupported annotation target `${moduleDef.name}`: expected a raw top-level class template, found an object/module",
                safePos(moduleDef, NoSourcePosition)
              )
            )
          case other =>
            Left(
              failure(
                s"unsupported annotation target `${safeTreeDescription(other)}`: expected a raw top-level class template",
                safePos(other, NoSourcePosition)
              )
            )
      catch
        case NonFatal(error) =>
          Left(
            failure(
              s"could not decode annotated class view: ${controlledFailureDescription(error)}",
              safePos(tree, NoSourcePosition)
            )
          )

  private def decodeTypeDef(typeDef: TypeDef)(using Context): Either[ExpansionDiagnostic, AnnotatedClassView] =
    val classPos = safePos(typeDef, NoSourcePosition)
    val className = Option(typeDef.name).map(_.toString).getOrElse("<unknown>")
    val mods = Trees.mods(typeDef)

    if !typeDef.isClassDef then
      Left(failure(s"unsupported annotation target `$className`: expected a raw top-level class template", classPos))
    else if mods.is(Enum) then
      Left(failure(s"unsupported annotation target `enum $className`: expected a raw top-level class template", classPos))
    else
      Option(typeDef.rhs) match
        case Some(template: Template) =>
          decodeTemplate(
            template,
            className,
            classPos,
            mods,
            if mods.is(Trait) then DefinitionKind.Trait else DefinitionKind.Class
          )
        case _ =>
          Left(failure(s"unsupported annotation target `$className`: expected a raw top-level class template", classPos))

  private def decodeTemplate(
      template: Template,
      className: String,
      classPos: SrcPos,
      classMods: untpd.Modifiers,
      definitionKind: DefinitionKind
  )(using Context): Either[ExpansionDiagnostic, AnnotatedClassView] =
    Option(template.constr) match
      case None =>
        Left(failure(s"could not decode annotated class view for `$className`: primary constructor was null", classPos))
      case Some(constructor) =>
        val constructorPos = safePos(constructor, classPos)
        val typeParametersEither =
          requireList(constructor.leadingTypeParams, "raw type-parameter list", constructorPos).flatMap:
            parameters => traverse(parameters)(normalizeTypeParameter(_, constructorPos))
        val clausesEither =
          requireList(constructor.termParamss, "primary-constructor clause list", constructorPos).flatMap:
            clauses => traverse(clauses)(normalizeClause(_, constructorPos))

        for
          typeParameters <- typeParametersEither
          constructorClauses <- clausesEither
        yield
          AnnotatedClassView(
            className = className,
            typeParameters = typeParameters,
            constructorClauses = constructorClauses,
            modifiers = Modifiers(
              isCase = classMods.is(Case),
              isAbstract = classMods.is(Abstract),
              isFinal = classMods.is(Final),
              isSealed = classMods.is(Sealed),
              constructorIsPrivate = Trees.mods(constructor).is(Private)
            ),
            classPos = classPos,
            constructorPos = constructorPos,
            definitionKind = definitionKind
          )

  private def normalizeTypeParameter(
      parameter: TypeDef | Null,
      fallback: SrcPos
  )(using Context): Either[ExpansionDiagnostic, TypeParameter] =
    Option(parameter) match
      case Some(value) =>
        val mods = Trees.mods(value)
        val variance =
          if mods.is(Covariant) then Variance.Covariant
          else if mods.is(Contravariant) then Variance.Contravariant
          else Variance.Invariant
        val (isOrdinaryUnbounded, hasContextBounds, isOrdinaryUpperBounded) =
          Option(value.rhs) match
            case Some(bounds: TypeBoundsTree) =>
              (
                bounds.lo.isEmpty && bounds.hi.isEmpty && bounds.alias.isEmpty,
                false,
                bounds.lo.isEmpty && !bounds.hi.isEmpty && bounds.alias.isEmpty
              )
            case Some(contextBounds: ContextBounds) =>
              val bounds = contextBounds.bounds
              (
                bounds.lo.isEmpty && bounds.hi.isEmpty && bounds.alias.isEmpty && contextBounds.cxBounds.isEmpty,
                contextBounds.cxBounds.nonEmpty,
                bounds.lo.isEmpty && !bounds.hi.isEmpty && bounds.alias.isEmpty && contextBounds.cxBounds.isEmpty
              )
            case _ => (false, false, false)
        Right(
          TypeParameter(
            Option(value.name).map(_.toString).getOrElse("<unknown>"),
            safePos(value, fallback),
            variance,
            isOrdinaryUnbounded,
            hasContextBounds,
            isOrdinaryUpperBounded
          )
        )
      case None => Left(failure("could not decode annotated class view: null raw type parameter", fallback))

  private def normalizeClause(
      rawClause: List[ValDef] | Null,
      constructorPos: SrcPos
  )(using Context): Either[ExpansionDiagnostic, ConstructorClause] =
    requireList(rawClause, "primary-constructor parameter clause", constructorPos).flatMap: clause =>
      traverse(clause)(normalizeParameter(_, constructorPos)).map: parameters =>
        ConstructorClause(
          parameters = parameters,
          isContextual = parameters.nonEmpty && parameters.forall(_.isContextual),
          pos = parameters.headOption.map(_.pos).filter(_.span.exists).getOrElse(constructorPos)
        )

  private def normalizeParameter(
      parameter: ValDef | Null,
      constructorPos: SrcPos
  )(using Context): Either[ExpansionDiagnostic, ConstructorParameter] =
    Option(parameter) match
      case None => Left(failure("could not decode annotated class view: null primary-constructor parameter", constructorPos))
      case Some(value) =>
        val parameterPos = safePos(value, constructorPos)
        Option(value.tpt) match
          case None => Left(failure("could not decode annotated class view: null constructor-parameter raw type", parameterPos))
          case Some(rawType) =>
            val mods = Trees.mods(value)
            val candidateTypePos = safePos(rawType, parameterPos)
            val rawTypePos = if candidateTypePos.span.exists then candidateTypePos else parameterPos
            Right(
              ConstructorParameter(
                name = Option(value.name).map(_.toString).getOrElse("<unknown>"),
                rawType = rawType,
                // CONFIRMED ON THE PINNED COMPILER
                // Bare parameters are local/private parameter accessors;
                // explicit vals lack Local, and vars add Mutable.
                isVal = mods.is(ParamAccessor) && !mods.is(Mutable) && !mods.is(Local),
                isVar = mods.is(Mutable),
                isContextual = mods.is(Given) || mods.is(Implicit),
                hasDefault = Option(value.rhs).exists(rhs => !rhs.isEmpty),
                pos = parameterPos,
                typePos = rawTypePos
              )
            )

  private def requireList[A](
      value: List[A] | Null,
      label: String,
      pos: SrcPos
  ): Either[ExpansionDiagnostic, List[A]] =
    Option(value).toRight(failure(s"could not decode annotated class view: null $label", pos))

  private def traverse[A, B](values: List[A])(f: A => Either[ExpansionDiagnostic, B]): Either[ExpansionDiagnostic, List[B]] =
    values.foldRight[Either[ExpansionDiagnostic, List[B]]](Right(Nil)): (value, accumulated) =>
      for
        current <- f(value)
        rest <- accumulated
      yield current :: rest

  private def failure(message: String, pos: SrcPos): ExpansionDiagnostic =
    ExpansionDiagnostic(message, pos)

  private def safePos(tree: untpd.Tree, fallback: SrcPos)(using Context): SrcPos =
    try Option(tree.sourcePos).getOrElse(fallback)
    catch case NonFatal(_) => fallback

  private def safeTreeDescription(tree: untpd.Tree): String =
    try tree.getClass.getSimpleName
    catch case NonFatal(_) => "unknown raw tree"

  private def controlledFailureDescription(error: Throwable): String =
    val kind = error.getClass.getSimpleName
    Option(error.getMessage).filter(_.nonEmpty).map(message => s"$kind: $message").getOrElse(kind)
