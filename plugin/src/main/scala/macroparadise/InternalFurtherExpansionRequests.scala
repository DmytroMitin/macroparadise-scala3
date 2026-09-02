package macroparadise

import dotty.tools.dotc.ast.Trees.Tree
import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.core.Names.Name

import scala.collection.mutable.ListBuffer

private[macroparadise] enum InternalGeneratedProvenance:
  case GeneratedDelegated
  case UnsupportedFixtureValue(value: String | Null)

private[macroparadise] final case class InternalGeneratedAnnotationDirective(
    annotationName: String,
    rawApplication: untpd.Tree,
    provenance: InternalGeneratedProvenance
)

private[macroparadise] final case class InternalStructuredExpansion[A](
    ordinaryResult: A,
    directives: List[InternalGeneratedAnnotationDirective]
)

private[macroparadise] object InternalStructuredExpansionValidator:
  enum Category:
    case DirectiveRejected
    case MixedAuthoring

  final case class Violation(
      category: Category,
      annotationName: String,
      rawApplication: untpd.Tree | Null,
      detail: String
  )

  def lowerToR1(
      directives: List[InternalGeneratedAnnotationDirective],
      originalSourceAnnotations: List[Tree[?]]
  )(using dotty.tools.dotc.core.Contexts.Context)
      : Either[Violation, List[InternalFurtherExpansionRequests.Draft]] =
    directives.foldLeft[
      Either[Violation, List[InternalFurtherExpansionRequests.Draft]]
    ](Right(Nil)):
      case (left @ Left(_), _) => left
      case (Right(lowered), null) =>
        Left(
          Violation(
            Category.DirectiveRejected,
            "<missing>",
            null,
            "directive structure is null"
          )
        )
      case (Right(lowered), directive) =>
        validate(directive, originalSourceAnnotations).map(_ :: lowered)
    .map(_.reverse)

  private def validate(
      directive: InternalGeneratedAnnotationDirective,
      originalSourceAnnotations: List[Tree[?]]
  )(using dotty.tools.dotc.core.Contexts.Context)
      : Either[Violation, InternalFurtherExpansionRequests.Draft] =
    val logicalName = Option(directive.annotationName).map(_.trim)
    logicalName match
      case None | Some("") =>
        Left(
          Violation(
            Category.DirectiveRejected,
            "<missing>",
            directive.rawApplication,
            "directive annotation name is empty"
          )
        )
      case Some(annotationName)
          if directive.provenance !=
            InternalGeneratedProvenance.GeneratedDelegated =>
        Left(
          Violation(
            Category.DirectiveRejected,
            annotationName,
            directive.rawApplication,
            "directive provenance is not generated/delegated"
          )
        )
      case Some(annotationName) if directive.rawApplication == null =>
        Left(
          Violation(
            Category.DirectiveRejected,
            annotationName,
            null,
            "directive raw application is null"
          )
        )
      case Some(annotationName)
          if originalSourceAnnotations.exists(_.eq(directive.rawApplication)) =>
        Left(
          Violation(
            Category.DirectiveRejected,
            annotationName,
            directive.rawApplication,
            "directive raw application reuses an original source annotation object"
          )
        )
      case Some(annotationName) =>
        rawConstructorName(directive.rawApplication) match
          case None =>
            Left(
              Violation(
                Category.DirectiveRejected,
                annotationName,
                directive.rawApplication,
                "directive raw application expected a constructor application"
              )
            )
          case Some(rawName)
              if rawName != annotationName &&
                !annotationName.endsWith(s".$rawName") =>
            Left(
              Violation(
                Category.DirectiveRejected,
                annotationName,
                directive.rawApplication,
                s"raw application names @$rawName but directive names @$annotationName"
              )
            )
          case Some(_) =>
            Right(
              InternalFurtherExpansionRequests.Draft(
                annotationName,
                directive.rawApplication
              )
            )

  private def rawConstructorName(
      rawApplication: untpd.Tree
  )(using dotty.tools.dotc.core.Contexts.Context): Option[String] =
    rawApplication match
      case untpd.Apply(
            untpd.Select(untpd.New(typeTree), constructorName),
            _
          ) if constructorName.toString == "<init>" =>
        val annotationType =
          typeTree match
            case untpd.AppliedTypeTree(base, _) => base
            case base => base
        SyntacticAnnotationIdentity.fromTree(annotationType).map(_.value)
      case _ => None

/** Product-private same-thread request scope used by the coordinator.
  *
  * The only retained producer is a repository fixture reached reflectively, so
  * this does not add a `paradise3.api` type or outcome case. The coordinator
  * resolves every draft back through its captured handler registry before it
  * can become executable work.
  */
private[macroparadise] object InternalFurtherExpansionRequests:
  final case class Draft(annotationName: String, rawApplication: untpd.Tree)
  final case class Captured[A](
      drafts: List[Draft],
      structured: InternalStructuredExpansion[A]
  ):
    def value: A = structured.ordinaryResult

  private final class ActiveCapture:
    val drafts = ListBuffer.empty[Draft]
    var directives: ListBuffer[InternalGeneratedAnnotationDirective] | Null = null

  private val activeCapture = new ThreadLocal[ActiveCapture]

  def capture[A](operation: => A): Captured[A] =
    if activeCapture.get() != null then
      throw IllegalStateException(
        "nested internal further-expansion request capture is unsupported"
      )
    val active = new ActiveCapture
    activeCapture.set(active)
    try
      val result = operation
      Captured(
        drafts = active.drafts.toList,
        structured = InternalStructuredExpansion(
          ordinaryResult = result,
          directives = Option(active.directives).fold(Nil)(_.toList)
        )
      )
    finally activeCapture.remove()

  /** Repository-fixture seam. External product handlers have no typed access
    * to this method and cannot place request records in ordinary output.
    */
  def enqueueFixtureRequest(
      annotationName: String,
      rawApplication: untpd.Tree
  ): Unit =
    val active = activeCapture.get()
    if active == null then
      throw IllegalStateException(
        "internal further-expansion request was emitted outside coordinator invocation"
      )
    active.drafts += Draft(annotationName, rawApplication)

  /** Repository-fixture seam for an explicit structured R2 directive. The
    * directive remains disjoint from the ordinary expansion result until the
    * coordinator validates and lowers it to a private R1 draft.
    */
  def emitFixtureStructuredDirective(
      annotationName: String,
      rawApplication: untpd.Tree,
      provenance: String
  ): Unit =
    val active = activeCapture.get()
    if active == null then
      throw IllegalStateException(
        "internal structured directive was emitted outside coordinator invocation"
      )
    val normalizedProvenance =
      if provenance == "generated/delegated" then
        InternalGeneratedProvenance.GeneratedDelegated
      else InternalGeneratedProvenance.UnsupportedFixtureValue(provenance)
    if active.directives == null then
      active.directives = ListBuffer.empty[InternalGeneratedAnnotationDirective]
    active.directives.nn += InternalGeneratedAnnotationDirective(
      annotationName,
      rawApplication,
      normalizedProvenance
    )

/** Position-independent structural evidence for bounded request-state keys.
  *
  * Untyped compiler trees use identity equality and carry positions outside
  * their product fields. This immutable normalization walks only product
  * structure, names, constants, and ordered collections. It retains no printed
  * tree text and is stored for at most the private transaction step budget.
  */
private[macroparadise] enum PositionIndependentStructure:
  case Atom(kind: String, value: String)
  case Node(kind: String, fields: Vector[PositionIndependentStructure])

private[macroparadise] object PositionIndependentStructure:
  import PositionIndependentStructure.*

  def tree(tree: Tree[?]): PositionIndependentStructure =
    normalize(tree)

  def trees(trees: List[Tree[?]]): PositionIndependentStructure =
    normalize(trees)

  private def normalize(value: Any): PositionIndependentStructure =
    value match
      case null => Atom("null", "")
      case tree: Tree[?] =>
        Node(
          tree.productPrefix,
          tree.productIterator.map(normalize).toVector
        )
      case name: Name => Atom("name", name.toString)
      case values: List[?] =>
        Node("list", values.iterator.map(normalize).toVector)
      case values: Vector[?] =>
        Node("vector", values.iterator.map(normalize).toVector)
      case value: Option[?] =>
        value match
          case Some(element) => Node("some", Vector(normalize(element)))
          case None => Node("none", Vector.empty)
      case value: Product =>
        Node(
          value.productPrefix,
          value.productIterator.map(normalize).toVector
        )
      case value =>
        Atom(value.getClass.getName, String.valueOf(value))
