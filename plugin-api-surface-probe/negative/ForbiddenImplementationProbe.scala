package surfaceprobe

import macroparadise.HelloWorldPlugin

object ForbiddenImplementationProbe:
  val leakedImplementation: Class[?] = classOf[HelloWorldPlugin]
