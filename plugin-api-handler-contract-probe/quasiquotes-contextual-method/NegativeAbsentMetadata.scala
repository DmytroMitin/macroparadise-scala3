package quasiquotes.macroparadisecontextual.negative

import scala.annotation.StaticAnnotation

final class NoExpanderMetadata extends StaticAnnotation

@NoExpanderMetadata
trait AbsentMetadata[A]

object AbsentMetadataUse:
  val unresolved = AbsentMetadata.apply[String]
