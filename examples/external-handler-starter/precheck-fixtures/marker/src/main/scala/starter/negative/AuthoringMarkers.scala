package starter.metadata

import paradise3.api.expander
import scala.annotation.StaticAnnotation

@expander("starter.metadata.DoesNotExist")
final class missingHandler extends StaticAnnotation

@expander("starter.metadata.NotAHandler")
final class invalidContract extends StaticAnnotation

@expander("starter.metadata.HandlerA")
final class handlerA extends StaticAnnotation

@expander("starter.metadata.DescriptorMismatchHandler")
final class descriptorMismatch extends StaticAnnotation

@expander("starter.metadata.StableHandler")
final class stale extends StaticAnnotation

@expander("")
final class emptyMetadata extends StaticAnnotation

@expander("   ")
final class whitespaceMetadata extends StaticAnnotation

@expander("starter.metadata..Broken")
final class malformedMetadata extends StaticAnnotation
