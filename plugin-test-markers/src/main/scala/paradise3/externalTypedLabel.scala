package paradise3

import paradise3.api.expander

@expander("demo.ExternalTypedLabelExpander")
final class externalTypedLabel[A](val value: String) extends scala.annotation.StaticAnnotation
