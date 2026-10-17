package starter.consumer

import starter.marker.rewriteEcho

@rewriteEcho
final class NegativeCallService:
  def echo(value: AnyVal): AnyVal = value

val rejected: Int = NegativeCallService().echo("bad")
