import paradise3.externalMarker

@externalMarker
class ExternalMarked

object ExternalMarkerExample:
  val directResult = new ExternalMarked().externalMarkerName
  def useExternalMarked(marked: ExternalMarked): String = marked.externalMarkerName
