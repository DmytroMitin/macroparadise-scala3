package paradise3.api

import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.util.SrcPos

/** Experimental normalized view of one raw pre-typer annotation application.
  *
  * This is not stable public API. It normalizes only the constructor/application
  * tree shapes proven on the repository's pinned Scala compiler. The contained
  * trees remain raw, untyped, and compiler-version-sensitive. This view does not
  * represent default or implicit arguments, repeated parameters, constructor
  * invocation semantics, or semantic types and values.
  */
final case class AnnotationApplication(
    annotationName: String,
    typeArguments: List[untpd.Tree],
    termArguments: List[AnnotationTermArgument],
    rawTree: untpd.Tree,
    pos: SrcPos
):
  /** Require the one explicit raw type argument used by the typed-label fixture. */
  def requireExactlyOneTypeArgument(
      using Context
  ): Either[ExpansionDiagnostic, untpd.Tree] =
    typeArguments match
      case argument :: Nil =>
        Right(argument)
      case arguments =>
        val diagnosticPos =
          arguments.drop(1).headOption.map(_.sourcePos).getOrElse(pos)
        Left(
          ExpansionDiagnostic(
            s"@$annotationName requires exactly one explicit type argument; found ${arguments.size}",
            diagnosticPos
          )
        )

  /** Decode the typed-label fixture's one positional or named string literal.
    *
    * This is deliberately syntactic. It does not fold constants, expand
    * defaults, resolve parameter symbols, or implement general named-argument
    * semantics.
    */
  def requireSingleStringLiteralArgument(
      parameterName: String
  ): Either[ExpansionDiagnostic, String] =
    termArguments match
      case argument :: Nil =>
        argument match
          case AnnotationTermArgument.Named(name, _, argumentPos)
              if name != parameterName =>
            Left(
              ExpansionDiagnostic(
                s"@$annotationName argument 0 uses unsupported named parameter `$name`; expected `$parameterName`",
                argumentPos
              )
            )
          case _ =>
            argument.valueTree match
              case Literal(Constant(value: String)) =>
                Right(value)
              case unsupported =>
                Left(
                  ExpansionDiagnostic(
                    s"@$annotationName parameter `$parameterName` (argument 0) requires a string literal; found raw ${AnnotationApplication.rawCategory(unsupported)}",
                    argument.pos
                  )
                )
      case arguments =>
        val diagnosticPos =
          arguments.drop(1).headOption.map(_.pos).getOrElse(pos)
        Left(
          ExpansionDiagnostic(
            s"@$annotationName requires exactly one term argument for parameter `$parameterName`; found ${arguments.size}",
            diagnosticPos
          )
        )

/** One ordered raw term argument in an [[AnnotationApplication]].
  *
  * `valueTree` is deliberately untyped. `pos` is the most precise defensible
  * position for the complete positional or named argument on the current
  * compiler.
  */
enum AnnotationTermArgument:
  case Positional(tree: untpd.Tree, argumentPos: SrcPos)
  case Named(name: String, tree: untpd.Tree, argumentPos: SrcPos)

  def valueTree: untpd.Tree =
    this match
      case Positional(tree, _) => tree
      case Named(_, tree, _) => tree

  def pos: SrcPos =
    this match
      case Positional(_, argumentPos) => argumentPos
      case Named(_, _, argumentPos) => argumentPos

object AnnotationApplication:
  /** Normalize the current raw annotation supplied to an external handler.
    *
    * Expected missing or unsupported shapes return a focused diagnostic rather
    * than throwing. Parsing is structural and never uses printable tree text.
    */
  def fromInput(
      input: ExpansionInput
  )(using Context): Either[ExpansionDiagnostic, AnnotationApplication] =
    input.currentAnnotation match
      case None =>
        Left(
          ExpansionDiagnostic(
            s"@${input.annotationName} annotation application is unavailable: current raw annotation tree is missing",
            input.annotatedClass.sourcePos
          )
        )
      case Some(rawTree) =>
        fromRawTree(input.annotationName, rawTree)

  private final case class RawConstructor(
      annotationName: String,
      typeArguments: List[untpd.Tree]
  )

  private def fromRawTree(
      expectedAnnotationName: String,
      rawTree: untpd.Tree
  )(using Context): Either[ExpansionDiagnostic, AnnotationApplication] =
    rawTree match
      // CONFIRMED ON PINNED COMPILER
      // @annotation[T](...) is Apply(Select(New(AppliedTypeTree(...)), <init>), args).
      case Apply(constructor, rawTermArguments) =>
        normalizeConstructor(expectedAnnotationName, constructor, rawTree).flatMap: normalized =>
          if normalized.annotationName != expectedAnnotationName then
            Left(
              ExpansionDiagnostic(
                s"annotation application name mismatch: input expects @$expectedAnnotationName but raw tree names @${normalized.annotationName}",
                rawTree.sourcePos
              )
            )
          else
            Right(
              AnnotationApplication(
                annotationName = normalized.annotationName,
                typeArguments = normalized.typeArguments,
                termArguments = rawTermArguments.map(normalizeTermArgument),
                rawTree = rawTree,
                pos = rawTree.sourcePos
              )
            )
      case unsupported =>
        Left(unsupportedShape(expectedAnnotationName, unsupported))

  private def normalizeConstructor(
      expectedAnnotationName: String,
      constructor: untpd.Tree,
      rawTree: untpd.Tree
  )(using Context): Either[ExpansionDiagnostic, RawConstructor] =
    constructor match
      // CONFIRMED ON PINNED COMPILER
      case Select(New(typeTree), constructorName)
          if constructorName.toString == "<init>" =>
        normalizeAppliedType(expectedAnnotationName, typeTree, rawTree)
      // MAY DEPEND ON SCALA VERSION
      // TypeApply did not occur in the pinned probe, so it is rejected instead
      // of being guessed into the shared contract.
      case unsupported =>
        Left(
          unsupportedShapeFrom(
            expectedNameHint = Some(expectedAnnotationName),
            unsupported,
            rawTree
          )
        )

  private def normalizeAppliedType(
      expectedAnnotationName: String,
      typeTree: untpd.Tree,
      rawTree: untpd.Tree
  )(using Context): Either[ExpansionDiagnostic, RawConstructor] =
    typeTree match
      // CONFIRMED ON PINNED COMPILER
      case AppliedTypeTree(annotationType, typeArguments) =>
        annotationSyntacticName(annotationType)
          .map(RawConstructor(_, typeArguments))
          .toRight(
            unsupportedShapeFrom(
              Some(expectedAnnotationName),
              annotationType,
              rawTree
            )
          )
      // CONFIRMED ON PINNED COMPILER
      // A missing explicit type argument omits AppliedTypeTree.
      case annotationType =>
        annotationSyntacticName(annotationType)
          .map(RawConstructor(_, Nil))
          .toRight(
            unsupportedShapeFrom(
              Some(expectedAnnotationName),
              annotationType,
              rawTree
            )
          )

  private def annotationSyntacticName(tree: untpd.Tree): Option[String] =
    tree match
      case Ident(name) =>
        Some(name.toString)
      case Select(qualifier, name) =>
        annotationSyntacticName(qualifier).map(prefix => s"$prefix.${name.toString}")
      case _ =>
        None

  private def normalizeTermArgument(
      tree: untpd.Tree
  )(using Context): AnnotationTermArgument =
    tree match
      // CONFIRMED ON PINNED COMPILER
      case NamedArg(name, value) =>
        AnnotationTermArgument.Named(name.toString, value, tree.sourcePos)
      case positional =>
        AnnotationTermArgument.Positional(positional, positional.sourcePos)

  private def unsupportedShape(
      expectedAnnotationName: String,
      unsupported: untpd.Tree
  )(using Context): ExpansionDiagnostic =
    ExpansionDiagnostic(
      s"@$expectedAnnotationName has unsupported raw annotation application shape `${rawCategory(unsupported)}`; expected a constructor application",
      unsupported.sourcePos
    )

  private def unsupportedShapeFrom(
      expectedNameHint: Option[String],
      unsupported: untpd.Tree,
      rawTree: untpd.Tree
  )(using Context): ExpansionDiagnostic =
    val label = expectedNameHint.fold("annotation application")(name => s"@$name")
    ExpansionDiagnostic(
      s"$label has unsupported raw constructor shape `${rawCategory(unsupported)}` on the pinned compiler",
      if unsupported.sourcePos.span.exists then unsupported.sourcePos
      else rawTree.sourcePos
    )

  private[api] def rawCategory(tree: untpd.Tree): String =
    tree.getClass.getSimpleName.stripSuffix("$")
