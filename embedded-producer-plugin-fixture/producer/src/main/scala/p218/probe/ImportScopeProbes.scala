package p218.probe

object LocalImportProbe:
  val helper: String =
    import p218.marker.addGreeting
    addGreeting.producerHelper

object ExplicitBeatsWildcardProbe:
  import p218.marker.*
  import p218.unrelated.addGreeting
  val length: Int = addGreeting.length

object RenamedImportProbe:
  import p218.marker.{addGreeting as embeddedGreeting}
  val helper: String = embeddedGreeting.producerHelper

object DirectCompanionMemberImportProbe:
  import p218.marker.addGreeting.producerHelper
  val helper: String = producerHelper

object WildcardCompanionMemberImportProbe:
  import p218.marker.addGreeting.*
  val helper: String = producerHelper
  val extracted: String = "wildcard-pattern" match
    case Extractor(value) => value
