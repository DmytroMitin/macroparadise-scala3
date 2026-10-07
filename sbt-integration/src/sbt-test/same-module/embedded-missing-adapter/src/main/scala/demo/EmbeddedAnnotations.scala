package demo

import paradise3.api.embeddedExpander
import scala.annotation.StaticAnnotation

@embeddedExpander
final class marker extends StaticAnnotation
