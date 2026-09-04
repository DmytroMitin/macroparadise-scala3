package macroparadise

import dotty.tools.dotc.ast.untpd.{ModuleDef, Tree}
import dotty.tools.dotc.core.Contexts.Context

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private[macroparadise] object PrivateObjectPrimaryTransform:
  import RoleAwareTransactionKernel.*

  private val OptionPrefix = "privateObjectTransformKey="
  private val participants = ConcurrentHashMap[String, Participant]()

  final case class Input(
      targetId: TransactionTargetId,
      primary: ModuleDef,
      opposite: Option[OppositeRole],
      currentAnnotation: Tree,
      originalAnnotations: List[Tree],
      topLevelNames: Set[String]
  )

  final case class Failure(category: String, detail: String)

  trait Participant:
    def annotationName: String

    def transform(
        input: Input
    )(using Context): Either[Failure, RoleAwareExpansionResult]

    def validateStaged(
        input: Input,
        result: RoleAwareExpansionResult
    )(using Context): Either[Failure, Unit] = Right(())

  def withParticipant[A](participant: Participant)(operation: String => A): A =
    val key = UUID.randomUUID().toString
    val previous = participants.putIfAbsent(key, participant)
    require(previous == null, s"duplicate private object transform key $key")
    try operation(s"-P:macroparadise:$OptionPrefix$key")
    finally participants.remove(key, participant)

  def fromOptions(options: List[String]): Option[Participant] =
    options.collectFirst:
      case option if option.startsWith(OptionPrefix) =>
        participants.get(option.stripPrefix(OptionPrefix))
    .flatMap(Option(_))
