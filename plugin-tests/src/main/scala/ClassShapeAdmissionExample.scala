import paradise3.{
  debug,
  externalCompanionDebug,
  externalDebug,
  externalLabel,
  externalMarker,
  externalSiblingDebug,
  externalTypedLabel,
  gen
}

@gen
final class FinalGenUser(val name: String)

@gen
sealed class SealedGenUser(name: String)

@debug
abstract class AbstractDebugUser
final class ConcreteDebugUser extends AbstractDebugUser

@externalDebug
abstract class AbstractExternalDebugUser()
final class ConcreteExternalDebugUser extends AbstractExternalDebugUser()

@externalCompanionDebug
abstract class AbstractExternalCompanionUser private (other: Int)

@externalSiblingDebug
abstract class AbstractExternalSiblingUser(other: Int)(using Ordering[String])

@externalLabel
abstract class AbstractExternalLabelUser(other: Int, more: String)
final class ConcreteExternalLabelUser
    extends AbstractExternalLabelUser(1, "two")

@externalTypedLabel[Int]("typed-abstract")
abstract class AbstractExternalTypedLabelUser(var other: Int)
final class ConcreteExternalTypedLabelUser
    extends AbstractExternalTypedLabelUser(1)

@externalMarker
abstract class AbstractExternalMarkerUser(other: Int = 1)
final class ConcreteExternalMarkerUser
    extends AbstractExternalMarkerUser()

object ClassShapeAdmissionExample:
  val finalGenResult = new FinalGenUser("final").generatedHello
  val sealedGenResult = new SealedGenUser("sealed").generatedHello
  val abstractDebugResult = new ConcreteDebugUser().debugName
  val abstractExternalDebugResult =
    new ConcreteExternalDebugUser().externalDebugName
  val abstractExternalCompanionResult =
    AbstractExternalCompanionUser.externalCompanionDebugName
  val abstractExternalSiblingResult =
    new AbstractExternalSiblingUserExternalMeta().externalSiblingDebugName
  val abstractExternalLabelResult =
    new ConcreteExternalLabelUser().externalLabel
  val abstractExternalTypedLabelResult =
    new ConcreteExternalTypedLabelUser().externalTypedLabel
  val abstractExternalMarkerResult =
    new ConcreteExternalMarkerUser().externalMarkerName
