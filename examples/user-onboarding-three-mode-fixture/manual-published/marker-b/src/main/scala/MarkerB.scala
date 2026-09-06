package fixture.marker

import paradise3.api.expander
import scala.annotation.StaticAnnotation

@expander("fixture.handler.HandlerB")
final class markerB extends StaticAnnotation
