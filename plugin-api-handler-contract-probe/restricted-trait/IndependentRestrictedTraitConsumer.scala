package external.traitconsumer

import external.traitprobe.RestrictedApply

@RestrictedApply
trait IndependentShow[A]

@RestrictedApply
trait IndependentExistingShow[A]

object IndependentExistingShow:
  val preservedBefore: Int = 41
  object Nested:
    def apply(value: Int): Int = value + 1
  def applyLike(value: Int): Int = value + 2
  val preservedAfter: Int = 43

@RestrictedApply
trait IndependentDirectShow[A]

object IndependentDirectShow:
  var directCalls: Int = 0
  def apply[A](using instance: IndependentDirectShow[A]): IndependentDirectShow[A] =
    directCalls += 1
    instance

object IndependentRestrictedTraitConsumer:
  def main(args: Array[String]): Unit =
    val created: IndependentShow[String] = new IndependentShow[String] {}
    val createdResult = IndependentShow.apply[String](using created)
    require(createdResult eq created)

    val existing: IndependentExistingShow[String] =
      new IndependentExistingShow[String] {}
    val existingResult =
      IndependentExistingShow.apply[String](using existing)
    require(existingResult eq existing)
    require(IndependentExistingShow.preservedBefore == 41)
    require(IndependentExistingShow.Nested(1) == 2)
    require(IndependentExistingShow.applyLike(1) == 3)
    require(IndependentExistingShow.preservedAfter == 43)

    val direct: IndependentDirectShow[String] =
      new IndependentDirectShow[String] {}
    val directResult = IndependentDirectShow.apply[String](using direct)
    require(directResult eq direct)
    require(IndependentDirectShow.directCalls == 1)

    println("IndependentRestrictedTraitConsumer")
