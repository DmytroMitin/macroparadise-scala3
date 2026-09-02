package macroparadise

import dotty.tools.dotc.ast.Trees.Tree
import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.core.Names.Name

import scala.collection.mutable.ListBuffer

/** Product-private same-thread request scope used by the coordinator.
  *
  * The only retained producer is a repository fixture reached reflectively, so
  * this does not add a `paradise3.api` type or outcome case. The coordinator
  * resolves every draft back through its captured handler registry before it
  * can become executable work.
  */
private[macroparadise] object InternalFurtherExpansionRequests:
  final case class Draft(annotationName: String, rawApplication: untpd.Tree)
  final case class Captured[A](value: A, drafts: List[Draft])

  private val activeDrafts = new ThreadLocal[ListBuffer[Draft]]

  def capture[A](operation: => A): Captured[A] =
    if activeDrafts.get() != null then
      throw IllegalStateException(
        "nested internal further-expansion request capture is unsupported"
      )
    val drafts = ListBuffer.empty[Draft]
    activeDrafts.set(drafts)
    try Captured(operation, drafts.toList)
    finally activeDrafts.remove()

  /** Repository-fixture seam. External product handlers have no typed access
    * to this method and cannot place request records in ordinary output.
    */
  def enqueueFixtureRequest(
      annotationName: String,
      rawApplication: untpd.Tree
  ): Unit =
    val drafts = activeDrafts.get()
    if drafts == null then
      throw IllegalStateException(
        "internal further-expansion request was emitted outside coordinator invocation"
      )
    drafts += Draft(annotationName, rawApplication)

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
