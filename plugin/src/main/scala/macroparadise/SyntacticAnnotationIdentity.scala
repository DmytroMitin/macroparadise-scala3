package macroparadise

import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Contexts.Context

/** Canonical class identity obtainable from a raw pre-typer annotation tree.
  *
  * This deliberately records only a syntactic identifier/select chain. It
  * performs no import, alias, symbol, or type resolution and never fabricates
  * a package for an unresolved simple name.
  */
private[macroparadise] final case class SyntacticAnnotationIdentity private (
    value: String
):
  def isQualified: Boolean = value.contains('.')

private[macroparadise] object SyntacticAnnotationIdentity:
  def fromDeclaredName(value: String): Either[String, SyntacticAnnotationIdentity] =
    Option(value).map(_.trim) match
      case Some(trimmed) if trimmed.nonEmpty && trimmed == value =>
        val segments = trimmed.split("\\.", -1).toList
        if segments.forall(validIdentifier) then
          Right(SyntacticAnnotationIdentity(trimmed))
        else
          Left("expected a canonical simple or dot-qualified annotation class name")
      case _ =>
        Left("expected a canonical simple or dot-qualified annotation class name")

  def fromTree(tree: Tree)(using Context): Option[SyntacticAnnotationIdentity] =
    tree match
      case Apply(fn, _) => fromTree(fn)
      case TypeApply(fn, _) => fromTree(fn)
      case Select(qualifier, name) if name.toString == "<init>" =>
        fromTree(qualifier)
      case New(tpt) => fromTree(tpt)
      case AppliedTypeTree(tpt, _) => fromTree(tpt)
      case reference =>
        referenceSegments(reference)
          .filter(_.forall(validIdentifier))
          .map(segments => SyntacticAnnotationIdentity(segments.mkString(".")))

  private def referenceSegments(tree: Tree): Option[List[String]] =
    tree match
      case Ident(name) => Some(List(name.toString))
      case Select(qualifier, name) =>
        referenceSegments(qualifier).map(_ :+ name.toString)
      case _ => None

  private def validIdentifier(value: String): Boolean =
    value.nonEmpty &&
      Character.isJavaIdentifierStart(value.codePointAt(0)) &&
      value.codePoints().skip(1).allMatch(Character.isJavaIdentifierPart(_))
