package starter.marker

import paradise3.api.expander

import scala.annotation.StaticAnnotation

@expander("starter.handler.GenerateGreetingHandler")
final class generateGreeting extends StaticAnnotation
