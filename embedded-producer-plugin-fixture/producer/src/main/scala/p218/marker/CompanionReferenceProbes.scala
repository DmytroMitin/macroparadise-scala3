package p218.marker

object CrossFileQualifiedReferenceProbe:
  val helper: String = p218.marker.addGreeting.producerHelper

object SamePackageShortReferenceProbe:
  val helper: String = addGreeting.producerHelper

object LocalShadowProbe:
  def length: Int =
    val addGreeting = "local-shadow"
    addGreeting.length

object MethodParameterShadowProbe:
  def length(addGreeting: String): Int = addGreeting.length

object LambdaParameterShadowProbe:
  val length: Int = ((addGreeting: String) => addGreeting.length)("lambda-shadow")

final class ConstructorParameterShadowProbe(val addGreeting: String):
  val length: Int = addGreeting.length

object MemberShadowProbe:
  private val addGreeting = "member-shadow"
  val length: Int = addGreeting.length

object PatternShadowProbe:
  val length: Int =
    "pattern-shadow" match
      case addGreeting => addGreeting.length

object ForComprehensionShadowProbe:
  val length: Int =
    (for addGreeting <- List("for-shadow") yield addGreeting.length).sum

object ImportAfterUseProbe:
  val helperBeforeImport: String = addGreeting.producerHelper
  import p218.unrelated.addGreeting
  val lengthAfterImport: Int = addGreeting.length

object NestedLocalImportProbe:
  val helper: String =
    import p218.marker.addGreeting
    addGreeting.producerHelper

object CompanionExtractorPatternProbe:
  val shortLength: Int =
    "short-pattern" match
      case addGreeting.Extractor(value) => value.length

  val qualifiedLength: Int =
    "qualified-pattern" match
      case p218.marker.addGreeting.Extractor(value) => value.length
