package paradise3.api

import dotty.tools.dotc.ast.Trees
import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags.{
  Final,
  Given,
  Implicit,
  Inline,
  Local,
  Mutable,
  Override,
  ParamAccessor,
  Private,
  Protected,
  Trait
}
import dotty.tools.dotc.util.{NoSourcePosition, SrcPos}

import scala.util.control.NonFatal

/** Experimental syntactic pre-typer read-only view of a class template's
  * direct body members.
  *
  * The decoder preserves direct source order and normalizes only the tiny type
  * subset needed to distinguish unqualified references to enclosing type
  * parameters from other simple unqualified type names. It performs no typing,
  * symbol or owner lookup, inheritance,
  * alias expansion, overload analysis, or consumer-specific admission policy.
  * Advanced exact-compiler consumers retain the separate raw
  * `ExpansionInput.annotatedClass` escape hatch.
  */
final case class AnnotatedClassBodyView(
    members: List[AnnotatedClassBodyView.DirectMember],
    pos: SrcPos
)

object AnnotatedClassBodyView:
  enum DirectMemberKind:
    case Method, Val, Var, Type, NestedClass, NestedTrait, NestedObject, Other

  enum DirectMethodStatus:
    case Abstract, Concrete

  enum DirectVisibility:
    case Public, Private, Protected

  enum DirectTypeShape:
    case EnclosingTypeParameter(name: String, pos: SrcPos)
    case Unsupported(kind: String, summary: String, pos: SrcPos)
    case NamedType(name: String, pos: SrcPos)

  object DirectTypeShape:
    private[api] def decode(
        rawType: untpd.Tree,
        enclosingTypeParameters: Set[String],
        localTypeParameters: Set[String],
        localTypeParameterReferenceKind: String,
        fallback: SrcPos
    )(using Context): DirectTypeShape =
      val pos = safePos(rawType, fallback)
      rawType match
        case identifier: Ident =>
          Option(identifier.name) match
            case None => DirectTypeShape.Unsupported("unqualified-reference", "<unknown>", pos)
            case Some(rawName) =>
              val name = safeName(rawName)
              if localTypeParameters.contains(name) then
                DirectTypeShape.Unsupported(localTypeParameterReferenceKind, name, pos)
              else if enclosingTypeParameters.contains(name) then
                DirectTypeShape.EnclosingTypeParameter(name, pos)
              else if name == "<error>" || name == "<unknown>" then
                DirectTypeShape.Unsupported("unqualified-reference", name, pos)
              else DirectTypeShape.NamedType(name, pos)
        case _: Function =>
          DirectTypeShape.Unsupported("function-type", safeTreeDescription(rawType), pos)
        case _: AppliedTypeTree =>
          DirectTypeShape.Unsupported("applied-type", safeTreeDescription(rawType), pos)
        case _: Select =>
          DirectTypeShape.Unsupported("qualified-type", safeTreeDescription(rawType), pos)
        case other if other.isEmpty =>
          DirectTypeShape.Unsupported("inferred-or-missing-type", safeTreeDescription(other), pos)
        case other =>
          DirectTypeShape.Unsupported("unsupported-type", safeTreeDescription(other), pos)

    private[api] def nullShape(kind: String, fallback: SrcPos): DirectTypeShape =
      DirectTypeShape.Unsupported(kind, "<null>", fallback)

    private def safePos(tree: untpd.Tree, fallback: SrcPos)(using Context): SrcPos =
      try Option(tree.sourcePos).getOrElse(fallback)
      catch case NonFatal(_) => fallback

    private def safeName(name: Any | Null): String = Option(name).map(_.toString).getOrElse("<unknown>")

    private def safeTreeDescription(tree: untpd.Tree): String =
      try tree.getClass.getSimpleName
      catch case NonFatal(_) => "unknown raw tree"

  final case class DirectMember(
      name: String,
      kind: DirectMemberKind,
      method: Option[DirectMethod],
      summary: String,
      pos: SrcPos
  )

  final case class DirectMethod(
      name: String,
      typeParameters: List[DirectMethodTypeParameter],
      parameterClauses: List[DirectMethodParameterClause],
      resultType: DirectTypeShape,
      status: DirectMethodStatus,
      modifiers: DirectMethodModifiers,
      pos: SrcPos,
      resultTypePos: SrcPos
  )

  final case class DirectMethodTypeParameter(name: String, pos: SrcPos)

  final case class DirectMethodParameterClause(
      parameters: List[DirectMethodParameter],
      isContextual: Boolean,
      isImplicit: Boolean,
      isGiven: Boolean,
      pos: SrcPos
  )

  final case class DirectMethodParameter(
      name: String,
      parameterType: DirectTypeShape,
      isContextual: Boolean,
      isImplicit: Boolean,
      isGiven: Boolean,
      isVal: Boolean,
      isVar: Boolean,
      hasDefault: Boolean,
      pos: SrcPos,
      typePos: SrcPos
  )

  final case class DirectMethodModifiers(
      visibility: DirectVisibility,
      hasAnnotations: Boolean,
      annotationCount: Int,
      unsupportedFlags: List[String]
  )

  /** Decode one raw class tree without applying handler-specific policy. */
  def decode(tree: untpd.Tree | Null)(using Context): Either[ExpansionDiagnostic, AnnotatedClassBodyView] =
    if tree == null then
      Left(failure("cannot decode a null annotated class body tree", NoSourcePosition))
    else
      try
        tree match
          case typeDef: TypeDef => decodeTypeDef(typeDef)
          case moduleDef: ModuleDef =>
            Left(
              failure(
                s"unsupported annotation target `${safeName(moduleDef.name)}`: expected a raw top-level class template for body decoding",
                safePos(moduleDef, NoSourcePosition)
              )
            )
          case other =>
            Left(
              failure(
                s"unsupported annotation target `${safeTreeDescription(other)}`: expected a raw top-level class template for body decoding",
                safePos(other, NoSourcePosition)
              )
            )
      catch
        case NonFatal(error) =>
          Left(
            failure(
              s"could not decode annotated class body view: ${controlledFailureDescription(error)}",
              safePos(tree, NoSourcePosition)
            )
          )

  private def decodeTypeDef(typeDef: TypeDef)(using Context): Either[ExpansionDiagnostic, AnnotatedClassBodyView] =
    AnnotatedClassView.decode(typeDef).flatMap: classView =>
      Option(typeDef.rhs) match
        case Some(template: Template) => decodeTemplate(template, classView)
        case _ =>
          Left(
            failure(
              s"could not decode annotated class body view for `${classView.className}`: expected a raw class template",
              classView.classPos
            )
          )

  private def decodeTemplate(
      template: Template,
      classView: AnnotatedClassView
  )(using Context): Either[ExpansionDiagnostic, AnnotatedClassBodyView] =
    val bodyPos = safePos(template, classView.classPos)
    requireList(template.body, "direct template-body member list", bodyPos).flatMap: body =>
      traverse(body)(normalizeMember(_, classView.typeParameters.map(_.name).toSet, bodyPos)).map: members =>
        AnnotatedClassBodyView(members, bodyPos)

  private def normalizeMember(
      rawMember: untpd.Tree | Null,
      enclosingTypeParameters: Set[String],
      fallback: SrcPos
  )(using Context): Either[ExpansionDiagnostic, DirectMember] =
    Option(rawMember) match
      case None => Left(failure("could not decode annotated class body view: null direct body member", fallback))
      case Some(method: DefDef) =>
        normalizeMethod(method, enclosingTypeParameters, fallback).map: decoded =>
          DirectMember(decoded.name, DirectMemberKind.Method, Some(decoded), "method", decoded.pos)
      case Some(value: ValDef) =>
        val mods = Trees.mods(value)
        Right(
          DirectMember(
            safeName(value.name),
            if mods.is(Mutable) then DirectMemberKind.Var else DirectMemberKind.Val,
            None,
            if value.rhs.isEmpty then "deferred-value" else "concrete-value",
            safePos(value, fallback)
          )
        )
      case Some(module: ModuleDef) =>
        Right(
          DirectMember(
            safeName(module.name),
            DirectMemberKind.NestedObject,
            None,
            "nested-object",
            safePos(module, fallback)
          )
        )
      case Some(definition: TypeDef) if definition.isClassDef =>
        val kind = if Trees.mods(definition).is(Trait) then DirectMemberKind.NestedTrait else DirectMemberKind.NestedClass
        Right(
          DirectMember(
            safeName(definition.name),
            kind,
            None,
            if kind == DirectMemberKind.NestedTrait then "nested-trait" else "nested-class",
            safePos(definition, fallback)
          )
        )
      case Some(definition: TypeDef) =>
        Right(
          DirectMember(
            safeName(definition.name),
            DirectMemberKind.Type,
            None,
            "type-member",
            safePos(definition, fallback)
          )
        )
      case Some(other) =>
        val description = safeTreeDescription(other)
        Right(
          DirectMember(
            description,
            DirectMemberKind.Other,
            None,
            description,
            safePos(other, fallback)
          )
        )

  private def normalizeMethod(
      method: DefDef,
      enclosingTypeParameters: Set[String],
      fallback: SrcPos
  )(using Context): Either[ExpansionDiagnostic, DirectMethod] =
    val methodPos = safePos(method, fallback)
    val methodName = safeName(method.name)
    val typeParametersEither =
      requireList(method.leadingTypeParams, "direct method type-parameter list", methodPos).flatMap: parameters =>
        traverse(parameters)(normalizeMethodTypeParameter(_, methodPos))

    typeParametersEither.flatMap: typeParameters =>
      val methodTypeParameterNames = typeParameters.map(_.name).toSet
      val clausesEither =
        requireList(method.termParamss, "direct method parameter-clause list", methodPos).flatMap: clauses =>
          traverse(clauses)(normalizeMethodClause(_, enclosingTypeParameters, methodTypeParameterNames, methodPos))
      val resultTypeEither =
        normalizeType(method.tpt, enclosingTypeParameters, methodTypeParameterNames, methodPos)

      for
        clauses <- clausesEither
        resultType <- resultTypeEither
      yield
        val mods = Trees.mods(method)
        val annotations = Option(mods.annotations).getOrElse(Nil)
        val resultTypePos = safePos(method.tpt, methodPos)
        DirectMethod(
          name = methodName,
          typeParameters = typeParameters,
          parameterClauses = clauses,
          resultType = resultType,
          status = if method.rhs.isEmpty then DirectMethodStatus.Abstract else DirectMethodStatus.Concrete,
          modifiers = DirectMethodModifiers(
            visibility =
              if mods.is(Private) then DirectVisibility.Private
              else if mods.is(Protected) then DirectVisibility.Protected
              else DirectVisibility.Public,
            hasAnnotations = annotations.nonEmpty,
            annotationCount = annotations.size,
            unsupportedFlags = List(
              Option.when(mods.is(Final))("final"),
              Option.when(mods.is(Override))("override"),
              Option.when(mods.is(Inline))("inline"),
              Option.when(mods.is(Implicit))("implicit"),
              Option.when(mods.is(Given))("given")
            ).flatten
          ),
          pos = methodPos,
          resultTypePos = if resultTypePos.span.exists then resultTypePos else methodPos
        )

  private def normalizeMethodTypeParameter(
      parameter: TypeDef | Null,
      fallback: SrcPos
  )(using Context): Either[ExpansionDiagnostic, DirectMethodTypeParameter] =
    Option(parameter) match
      case Some(value) => Right(DirectMethodTypeParameter(safeName(value.name), safePos(value, fallback)))
      case None => Left(failure("could not decode annotated class body view: null direct method type parameter", fallback))

  private def normalizeMethodClause(
      rawClause: List[ValDef] | Null,
      enclosingTypeParameters: Set[String],
      methodTypeParameters: Set[String],
      methodPos: SrcPos
  )(using Context): Either[ExpansionDiagnostic, DirectMethodParameterClause] =
    requireList(rawClause, "direct method parameter clause", methodPos).flatMap: clause =>
      traverse(clause)(normalizeMethodParameter(_, enclosingTypeParameters, methodTypeParameters, methodPos)).map:
        parameters =>
          val isGiven = parameters.nonEmpty && parameters.forall(_.isGiven)
          val isImplicit = parameters.nonEmpty && parameters.forall(_.isImplicit)
          DirectMethodParameterClause(
            parameters = parameters,
            isContextual = parameters.exists(_.isContextual),
            isImplicit = isImplicit,
            isGiven = isGiven,
            pos = parameters.headOption.map(_.pos).filter(_.span.exists).getOrElse(methodPos)
          )

  private def normalizeMethodParameter(
      parameter: ValDef | Null,
      enclosingTypeParameters: Set[String],
      methodTypeParameters: Set[String],
      methodPos: SrcPos
  )(using Context): Either[ExpansionDiagnostic, DirectMethodParameter] =
    Option(parameter) match
      case None => Left(failure("could not decode annotated class body view: null direct method parameter", methodPos))
      case Some(value) =>
        val parameterPos = safePos(value, methodPos)
        Option(value.tpt) match
          case None => Left(failure("could not decode annotated class body view: null direct method parameter type", parameterPos))
          case Some(rawType) =>
            normalizeType(rawType, enclosingTypeParameters, methodTypeParameters, parameterPos).map: parameterType =>
              val mods = Trees.mods(value)
              val isGiven = mods.is(Given)
              val isImplicit = mods.is(Implicit)
              val typePos = safePos(rawType, parameterPos)
              DirectMethodParameter(
                name = safeName(value.name),
                parameterType = parameterType,
                isContextual = isGiven || isImplicit,
                isImplicit = isImplicit,
                isGiven = isGiven,
                isVal = mods.is(ParamAccessor) && !mods.is(Mutable) && !mods.is(Local),
                isVar = mods.is(Mutable),
                hasDefault = !value.rhs.isEmpty,
                pos = parameterPos,
                typePos = if typePos.span.exists then typePos else parameterPos
              )

  private def normalizeType(
      rawType: untpd.Tree | Null,
      enclosingTypeParameters: Set[String],
      methodTypeParameters: Set[String],
      fallback: SrcPos
  )(using Context): Either[ExpansionDiagnostic, DirectTypeShape] =
    Option(rawType) match
      case None => Left(failure("could not decode annotated class body view: null direct method type", fallback))
      case Some(value) =>
        Right(
          DirectTypeShape.decode(
            value,
            enclosingTypeParameters,
            methodTypeParameters,
            "method-type-parameter-reference",
            fallback
          )
        )

  private def requireList[A](value: List[A] | Null, label: String, pos: SrcPos): Either[ExpansionDiagnostic, List[A]] =
    Option(value).toRight(failure(s"could not decode annotated class body view: null $label", pos))

  private def traverse[A, B](values: List[A])(f: A => Either[ExpansionDiagnostic, B]): Either[ExpansionDiagnostic, List[B]] =
    values.foldRight[Either[ExpansionDiagnostic, List[B]]](Right(Nil)): (value, accumulated) =>
      for
        current <- f(value)
        rest <- accumulated
      yield current :: rest

  private def failure(message: String, pos: SrcPos): ExpansionDiagnostic = ExpansionDiagnostic(message, pos)

  private def safePos(tree: untpd.Tree, fallback: SrcPos)(using Context): SrcPos =
    try Option(tree.sourcePos).getOrElse(fallback)
    catch case NonFatal(_) => fallback

  private def safeName(name: Any | Null): String = Option(name).map(_.toString).getOrElse("<unknown>")

  private def safeTreeDescription(tree: untpd.Tree): String =
    try tree.getClass.getSimpleName
    catch case NonFatal(_) => "unknown raw tree"

  private def controlledFailureDescription(error: Throwable): String =
    val kind = error.getClass.getSimpleName
    Option(error.getMessage).filter(_.nonEmpty).map(message => s"$kind: $message").getOrElse(kind)
