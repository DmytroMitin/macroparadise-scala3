import paradise3.{PreservedRuntimeMarker, externalRestrictedTraitApply}

trait RestrictedShowParent:
  def parentValue: Int = 7

@externalRestrictedTraitApply
@PreservedRuntimeMarker("restricted-trait-kept")
trait RestrictedShow[A] extends RestrictedShowParent:
  def existingValue: Int = 8

@externalRestrictedTraitApply
trait RestrictedExistingShow[A]

object RestrictedExistingShow:
  val preservedBefore: Int = 41
  object Nested:
    def apply(value: Int): Int = value + 1
  def applyLike(value: Int): Int = value + 2
  val preservedAfter: Int = 43

@externalRestrictedTraitApply
trait RestrictedDirectApplyShow[A]

object RestrictedDirectApplyShow:
  var directApplyCalls: Int = 0
  def apply[A](using instance: RestrictedDirectApplyShow[A]): RestrictedDirectApplyShow[A] =
    directApplyCalls += 1
    instance

object RestrictedGenericTraitApplyExample:
  val supplied: RestrictedShow[String] = new RestrictedShow[String] {}
  val returned: RestrictedShow[String] = RestrictedShow.apply[String](using supplied)
  val preservedMarker: String =
    classOf[RestrictedShow[?]]
      .getAnnotation(classOf[PreservedRuntimeMarker])
      .value()

  val existingSupplied: RestrictedExistingShow[String] =
    new RestrictedExistingShow[String] {}
  val existingReturned: RestrictedExistingShow[String] =
    RestrictedExistingShow.apply[String](using existingSupplied)

  val directSupplied: RestrictedDirectApplyShow[String] =
    new RestrictedDirectApplyShow[String] {}
  val directReturned: RestrictedDirectApplyShow[String] =
    RestrictedDirectApplyShow.apply[String](using directSupplied)
