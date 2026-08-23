package macroparadise

import dotty.tools.dotc.config.Properties

final class ExactCompilerLineSpec extends munit.FunSuite:
  test("accepts the exact compiler that is running the plugin"):
    val phases = ExactCompilerLine.pluginPhases(Properties.versionNumberString, Nil)

    assertEquals(phases.map(_.phaseName), List("paradiseGen"))

  test("rejects a plugin artifact built for the other exact compiler line"):
    val otherLine =
      if Properties.versionNumberString == "3.3.8" then "3.8.4" else "3.3.8"

    val phases = ExactCompilerLine.pluginPhases(otherLine, Nil)

    assertEquals(phases.map(_.phaseName), List("macroparadiseExactCompilerMismatch"))
