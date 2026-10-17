package starter.consumer

import starter.marker.rewriteEcho

@rewriteEcho
final class UnsupportedShape:
  def other(value: AnyVal): AnyVal = value
