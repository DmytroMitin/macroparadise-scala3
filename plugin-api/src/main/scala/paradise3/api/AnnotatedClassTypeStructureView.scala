package paradise3.api

import dotty.tools.dotc.ast.Trees
import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags.{Contravariant, Covariant, Final, Opaque, Override, Private, Protected}
import dotty.tools.dotc.util.{NoSourcePosition, SrcPos}

import scala.util.control.NonFatal

/** Experimental syntactic pre-typer view of enclosing type-parameter bounds
  * and direct type members.
  *
  * This additive view exposes source structure only. It performs no typing,
  * symbol lookup, alias expansion, subtyping, inherited-member discovery, or
  * consumer-specific admission. Supported bound shapes reuse
  * [[AnnotatedClassBodyView.DirectTypeShape]]; advanced exact-compiler
  * handlers retain the raw `ExpansionInput.annotatedClass` escape hatch.
  */
final case class AnnotatedClassTypeStructureView(
    typeParameters: List[AnnotatedClassTypeStructureView.EnclosingTypeParameter],
    directTypeMembers: List[AnnotatedClassTypeStructureView.DirectTypeMember],
    pos: SrcPos
)

object AnnotatedClassTypeStructureView:
  import AnnotatedClassBodyView.{DirectTypeShape, DirectVisibility}

  enum Bound:
    case Absent
    case Present(shape: DirectTypeShape)

  enum DirectTypeMemberKind:
    case AbstractBounds, Alias, Unsupported

  final case class EnclosingTypeParameter(
      name: String,
      variance: AnnotatedClassView.Variance,
      lowerBound: Bound,
      upperBound: Bound,
      hasContextBounds: Boolean,
      pos: SrcPos
  )

  final case class DirectTypeMember(
      name: String,
      bodyIndex: Int,
      kind: DirectTypeMemberKind,
      typeParameters: List[DirectTypeMemberTypeParameter],
      lowerBound: Bound,
      upperBound: Bound,
      aliasTarget: Option[DirectTypeShape],
      modifiers: DirectTypeMemberModifiers,
      pos: SrcPos
  )

  final case class DirectTypeMemberTypeParameter(name: String, pos: SrcPos)

  final case class DirectTypeMemberModifiers(
      visibility: DirectVisibility,
      hasAnnotations: Boolean,
      annotationCount: Int,
      unsupportedFlags: List[String]
  )

  /** Decode one raw class tree without applying handler-specific policy. */
  def decode(tree: untpd.Tree | Null)(using Context): Either[ExpansionDiagnostic, AnnotatedClassTypeStructureView] =
    if tree == null then
      Left(failure("cannot decode a null annotated class type-structure tree", NoSourcePosition))
    else
      try
        tree match
          case typeDef: TypeDef => decodeTypeDef(typeDef)
          case moduleDef: ModuleDef =>
            Left(
              failure(
                s"unsupported annotation target `${safeName(moduleDef.name)}`: expected a raw top-level class template for type-structure decoding",
                safePos(moduleDef, NoSourcePosition)
              )
            )
          case other =>
            Left(
              failure(
                s"unsupported annotation target `${safeTreeDescription(other)}`: expected a raw top-level class template for type-structure decoding",
                safePos(other, NoSourcePosition)
              )
            )
      catch
        case NonFatal(error) =>
          Left(
            failure(
              s"could not decode annotated class type-structure view: ${controlledFailureDescription(error)}",
              safePos(tree, NoSourcePosition)
            )
          )

  private def decodeTypeDef(
      typeDef: TypeDef
  )(using Context): Either[ExpansionDiagnostic, AnnotatedClassTypeStructureView] =
    AnnotatedClassView.decode(typeDef).flatMap: classView =>
      Option(typeDef.rhs) match
        case Some(template: Template) => decodeTemplate(template, classView)
        case _ =>
          Left(
            failure(
              s"could not decode annotated class type-structure view for `${classView.className}`: expected a raw class template",
              classView.classPos
            )
          )

  private def decodeTemplate(
      template: Template,
      classView: AnnotatedClassView
  )(using Context): Either[ExpansionDiagnostic, AnnotatedClassTypeStructureView] =
    val viewPos = safePos(template, classView.classPos)
    Option(template.constr) match
      case None => Left(failure("could not decode annotated class type-structure view: null primary constructor", viewPos))
      case Some(constructor) =>
        val enclosingNames = classView.typeParameters.map(_.name).toSet
        val typeParametersEither =
          requireList(constructor.leadingTypeParams, "raw type-parameter list", viewPos).flatMap: parameters =>
            traverse(parameters)(normalizeEnclosingTypeParameter(_, enclosingNames, viewPos))
        val typeMembersEither =
          requireList(template.body, "direct template-body member list", viewPos).flatMap: body =>
            traverse(body.zipWithIndex): entry =>
              normalizeDirectTypeMember(entry._1, entry._2, enclosingNames, viewPos)
            .map(_.flatten)

        for
          typeParameters <- typeParametersEither
          typeMembers <- typeMembersEither
        yield AnnotatedClassTypeStructureView(typeParameters, typeMembers, viewPos)

  private def normalizeEnclosingTypeParameter(
      parameter: TypeDef | Null,
      enclosingTypeParameters: Set[String],
      fallback: SrcPos
  )(using Context): Either[ExpansionDiagnostic, EnclosingTypeParameter] =
    Option(parameter) match
      case None => Left(failure("could not decode annotated class type-structure view: null raw type parameter", fallback))
      case Some(value) =>
        val pos = safePos(value, fallback)
        val mods = Trees.mods(value)
        val variance =
          if mods.is(Covariant) then AnnotatedClassView.Variance.Covariant
          else if mods.is(Contravariant) then AnnotatedClassView.Variance.Contravariant
          else AnnotatedClassView.Variance.Invariant
        val (lowerBound, upperBound, hasContextBounds) =
          Option(value.rhs) match
            case Some(bounds: TypeBoundsTree) =>
              (
                normalizeBound(bounds.lo, enclosingTypeParameters, Set.empty, "type-parameter-reference", safePos(bounds, pos)),
                normalizeBound(bounds.hi, enclosingTypeParameters, Set.empty, "type-parameter-reference", safePos(bounds, pos)),
                false
              )
            case Some(contextBounds: ContextBounds) =>
              val bounds = contextBounds.bounds
              (
                normalizeBound(bounds.lo, enclosingTypeParameters, Set.empty, "type-parameter-reference", safePos(bounds, pos)),
                normalizeBound(bounds.hi, enclosingTypeParameters, Set.empty, "type-parameter-reference", safePos(bounds, pos)),
                Option(contextBounds.cxBounds).exists(_.nonEmpty)
              )
            case Some(other) =>
              val unsupported = Bound.Present(
                DirectTypeShape.Unsupported("unsupported-type-parameter-bounds", safeTreeDescription(other), safePos(other, pos))
              )
              (unsupported, unsupported, false)
            case None =>
              val unsupported = Bound.Present(DirectTypeShape.nullShape("null-type-parameter-bounds", pos))
              (unsupported, unsupported, false)
        Right(
          EnclosingTypeParameter(
            safeName(value.name),
            variance,
            lowerBound,
            upperBound,
            hasContextBounds,
            pos
          )
        )

  private def normalizeDirectTypeMember(
      rawMember: untpd.Tree | Null,
      bodyIndex: Int,
      enclosingTypeParameters: Set[String],
      fallback: SrcPos
  )(using Context): Either[ExpansionDiagnostic, Option[DirectTypeMember]] =
    Option(rawMember) match
      case None => Left(failure("could not decode annotated class type-structure view: null direct body member", fallback))
      case Some(definition: TypeDef) if !definition.isClassDef =>
        normalizeTypeMember(definition, bodyIndex, enclosingTypeParameters, fallback).map(Some(_))
      case Some(_) => Right(None)

  private def normalizeTypeMember(
      member: TypeDef,
      bodyIndex: Int,
      enclosingTypeParameters: Set[String],
      fallback: SrcPos
  )(using Context): Either[ExpansionDiagnostic, DirectTypeMember] =
    val pos = safePos(member, fallback)
    val name = safeName(member.name)
    val base = normalizeTypeMemberRhs(member.rhs, enclosingTypeParameters, pos)
    base.map: normalized =>
      DirectTypeMember(
        name = name,
        bodyIndex = bodyIndex,
        kind =
          if name == "<unknown>" || name == "<error>" then DirectTypeMemberKind.Unsupported
          else normalized._1,
        typeParameters = normalized._2,
        lowerBound = normalized._3,
        upperBound = normalized._4,
        aliasTarget = normalized._5,
        modifiers = normalizeModifiers(member),
        pos = pos
      )

  private type NormalizedTypeMemberRhs = (
      DirectTypeMemberKind,
      List[DirectTypeMemberTypeParameter],
      Bound,
      Bound,
      Option[DirectTypeShape]
  )

  private def normalizeTypeMemberRhs(
      rawRhs: untpd.Tree | Null,
      enclosingTypeParameters: Set[String],
      fallback: SrcPos
  )(using Context): Either[ExpansionDiagnostic, NormalizedTypeMemberRhs] =
    Option(rawRhs) match
      case None =>
        Right(
          (
            DirectTypeMemberKind.Unsupported,
            Nil,
            Bound.Present(DirectTypeShape.nullShape("null-type-member-rhs", fallback)),
            Bound.Absent,
            None
          )
        )
      case Some(bounds: TypeBoundsTree) =>
        Right(normalizeAbstractBounds(bounds, Nil, enclosingTypeParameters, Set.empty, fallback))
      case Some(lambda: LambdaTypeTree) =>
        requireList(lambda.tparams, "direct type-member type-parameter list", fallback).flatMap: parameters =>
          traverse(parameters)(normalizeTypeMemberTypeParameter(_, fallback)).map: decodedParameters =>
            val localNames = decodedParameters.map(_.name).toSet
            Option(lambda.body) match
              case Some(bounds: TypeBoundsTree) =>
                normalizeAbstractBounds(bounds, decodedParameters, enclosingTypeParameters, localNames, fallback)
              case Some(alias) if !alias.isEmpty =>
                (
                  DirectTypeMemberKind.Alias,
                  decodedParameters,
                  Bound.Absent,
                  Bound.Absent,
                  Some(
                    DirectTypeShape.decode(
                      alias,
                      enclosingTypeParameters,
                      localNames,
                      "type-member-type-parameter-reference",
                      fallback
                    )
                  )
                )
              case _ =>
                (
                  DirectTypeMemberKind.Unsupported,
                  decodedParameters,
                  Bound.Present(DirectTypeShape.nullShape("null-type-member-lambda-body", fallback)),
                  Bound.Absent,
                  None
                )
      case Some(alias) if !alias.isEmpty =>
        Right(
          (
            DirectTypeMemberKind.Alias,
            Nil,
            Bound.Absent,
            Bound.Absent,
            Some(
              DirectTypeShape.decode(
                alias,
                enclosingTypeParameters,
                Set.empty,
                "type-member-type-parameter-reference",
                fallback
              )
            )
          )
        )
      case Some(other) =>
        Right(
          (
            DirectTypeMemberKind.Unsupported,
            Nil,
            Bound.Present(
              DirectTypeShape.Unsupported("inferred-or-missing-type-member", safeTreeDescription(other), safePos(other, fallback))
            ),
            Bound.Absent,
            None
          )
        )

  private def normalizeAbstractBounds(
      bounds: TypeBoundsTree,
      typeParameters: List[DirectTypeMemberTypeParameter],
      enclosingTypeParameters: Set[String],
      localTypeParameters: Set[String],
      fallback: SrcPos
  )(using Context): NormalizedTypeMemberRhs =
    val boundsPos = safePos(bounds, fallback)
    val alias = Option(bounds.alias).filterNot(_.isEmpty)
    (
      if alias.isEmpty then DirectTypeMemberKind.AbstractBounds else DirectTypeMemberKind.Unsupported,
      typeParameters,
      normalizeBound(
        bounds.lo,
        enclosingTypeParameters,
        localTypeParameters,
        "type-member-type-parameter-reference",
        boundsPos
      ),
      normalizeBound(
        bounds.hi,
        enclosingTypeParameters,
        localTypeParameters,
        "type-member-type-parameter-reference",
        boundsPos
      ),
      alias.map: value =>
        DirectTypeShape.decode(
          value,
          enclosingTypeParameters,
          localTypeParameters,
          "type-member-type-parameter-reference",
          boundsPos
        )
    )

  private def normalizeBound(
      rawBound: untpd.Tree | Null,
      enclosingTypeParameters: Set[String],
      localTypeParameters: Set[String],
      localTypeParameterReferenceKind: String,
      fallback: SrcPos
  )(using Context): Bound =
    Option(rawBound) match
      case None => Bound.Present(DirectTypeShape.nullShape("null-type", fallback))
      case Some(value) if value.isEmpty => Bound.Absent
      case Some(value) =>
        Bound.Present(
          DirectTypeShape.decode(
            value,
            enclosingTypeParameters,
            localTypeParameters,
            localTypeParameterReferenceKind,
            fallback
          )
        )

  private def normalizeTypeMemberTypeParameter(
      parameter: TypeDef | Null,
      fallback: SrcPos
  )(using Context): Either[ExpansionDiagnostic, DirectTypeMemberTypeParameter] =
    Option(parameter) match
      case Some(value) => Right(DirectTypeMemberTypeParameter(safeName(value.name), safePos(value, fallback)))
      case None =>
        Left(failure("could not decode annotated class type-structure view: null direct type-member type parameter", fallback))

  private def normalizeModifiers(member: TypeDef)(using Context): DirectTypeMemberModifiers =
    val mods = Trees.mods(member)
    val annotations = Option(mods.annotations).getOrElse(Nil)
    val visibility =
      if mods.is(Private) then DirectVisibility.Private
      else if mods.is(Protected) then DirectVisibility.Protected
      else DirectVisibility.Public
    DirectTypeMemberModifiers(
      visibility = visibility,
      hasAnnotations = annotations.nonEmpty,
      annotationCount = annotations.size,
      unsupportedFlags = List(
        Option.when(mods.is(Private))("private"),
        Option.when(mods.is(Protected))("protected"),
        Option.when(mods.is(Final))("final"),
        Option.when(mods.is(Override))("override"),
        Option.when(mods.is(Opaque))("opaque")
      ).flatten
    )

  private def requireList[A](
      value: List[A] | Null,
      label: String,
      pos: SrcPos
  ): Either[ExpansionDiagnostic, List[A]] =
    Option(value).toRight(failure(s"could not decode annotated class type-structure view: null $label", pos))

  private def traverse[A, B](
      values: List[A]
  )(f: A => Either[ExpansionDiagnostic, B]): Either[ExpansionDiagnostic, List[B]] =
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
