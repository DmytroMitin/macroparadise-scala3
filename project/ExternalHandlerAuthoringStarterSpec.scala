object ExternalHandlerAuthoringStarterSpec {
  val CaseCount = 5

  def run(): Unit = {
    import ExternalHandlerAuthoringStarter._

    assert(
      validatePositiveFlow(
        Vector(
          "precheck-start",
          "precheck-success",
          "consumer-compile-start",
          "consumer-compile-success",
          "runtime-success"
        )
      ).isEmpty
    )
    assert(validatePositiveFlow(Vector("consumer-compile-start")).nonEmpty)

    assert(validateNegativeFlow(Vector("precheck-start", "precheck-failed")).isEmpty)
    assert(validateNegativeFlow(Vector("precheck-start", "consumer-compile-start")).nonEmpty)

    val command = Vector(
      "sbt",
      "-batch",
      "-Dmacroparadise.starter.plugin=/tmp/plugin.jar",
      "-Dmacroparadise.starter.pluginApi=/tmp/plugin-api.jar",
      "clean",
      "verifyStarter",
      "verifyNegativeMatrix"
    )
    assert(validateChildCommand(command).isEmpty)
    assert(validateChildCommand(command :+ ("publish" + "Local")).nonEmpty)

    val allowedClasspath = Vector(
      "/tmp/macroparadise-scala3-plugin-api.jar",
      "/tmp/scala3-compiler.jar",
      "/tmp/scala3-library.jar"
    )
    assert(validateHandlerClasspath(allowedClasspath).isEmpty)
    assert(validateHandlerClasspath(allowedClasspath :+ "/tmp/plugin-test-handlers.jar").nonEmpty)
  }
}
