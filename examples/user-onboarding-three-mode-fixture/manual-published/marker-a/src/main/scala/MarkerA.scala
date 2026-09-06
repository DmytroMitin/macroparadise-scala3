package fixture.marker

import paradise3.api.expander
import scala.annotation.StaticAnnotation

@expander("fixture.handler.HandlerA")
final class markerA extends StaticAnnotation
