package legacybinaryprobe

import paradise3.api.expander
import scala.annotation.StaticAnnotation

@expander("legacybinaryprobe.LegacyBinaryClassHandler")
final class LegacyBinaryClassMarker extends StaticAnnotation

@expander("legacybinaryprobe.LegacyBinaryTraitHandler")
final class LegacyBinaryTraitMarker extends StaticAnnotation
