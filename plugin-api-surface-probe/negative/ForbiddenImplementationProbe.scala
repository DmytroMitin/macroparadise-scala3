package surfaceprobe

import macroparadise.MacroParadisePlugin

object ForbiddenImplementationProbe:
  val leakedImplementation: Class[?] = classOf[MacroParadisePlugin]
