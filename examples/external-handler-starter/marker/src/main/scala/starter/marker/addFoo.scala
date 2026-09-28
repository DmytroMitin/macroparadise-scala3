package starter.marker

import paradise3.api.expander

import scala.annotation.StaticAnnotation

@expander("starter.handler.AddFooHandler")
final class addFoo extends StaticAnnotation
