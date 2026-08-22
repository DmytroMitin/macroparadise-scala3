object ExternalHandlerAuthoringStarterSpec {
  val CaseCount = 11

  def run(): Unit = {
    import ExternalHandlerAuthoringStarter._

    assert(
      PublishingClassification ==
        "REMOTE_PUBLISHING_REMAINS_DISABLED_LOCAL_SELECTED_ARTIFACTS_ONLY"
    )

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
    assert(
      validateChildCommand(
        command.patch(
          2,
          Vector("-Dmacroparadise.starter.expandTrace=/tmp/internal-evidence-only.trace"),
          0
        )
      ).nonEmpty
    )

    val allowedClasspath = Vector(
      "/tmp/macroparadise-scala3-plugin-api.jar",
      "/tmp/scala3-compiler.jar",
      "/tmp/scala3-library.jar"
    )
    assert(validateHandlerClasspath(allowedClasspath).isEmpty)
    assert(validateHandlerClasspath(allowedClasspath :+ "/tmp/plugin-test-handlers.jar").nonEmpty)

    val explicitPrecheck = Vector(
      "--plugin=/tmp/plugin.jar",
      "--plugin-api=/tmp/plugin-api.jar",
      "--marker=/tmp/marker.jar",
      "--handler=/tmp/handler.jar",
      "--handler-compile-classpath=/tmp/plugin-api.jar:/tmp/compiler.jar",
      "--marker-class=starter.marker.generateGreeting",
      "--expected-handler-class=starter.handler.GenerateGreetingHandler",
      "--expected-annotation=starter.marker.generateGreeting",
      "--expected-scala-version=3.8.4",
      "--expected-jdk-major=25"
    )
    val compactPrecheck = Vector(
      "--compact",
      "--marker=/tmp/marker.jar",
      "--handler=/tmp/handler.jar",
      "--handler-compile-classpath=/tmp/plugin-api.jar:/tmp/compiler.jar",
      "--expected-handler-class=starter.handler.GenerateGreetingHandler",
      "--expected-annotation=starter.marker.generateGreeting",
      "--expected-scala-version=3.8.4",
      "--expected-jdk-major=25"
    )
    assert(validatePrecheckCommandShapes(explicitPrecheck, compactPrecheck).isEmpty)
    assert(
      validatePrecheckCommandShapes(
        explicitPrecheck,
        compactPrecheck :+ "--plugin=/tmp/echoed-plugin.jar"
      ).nonEmpty
    )

    val explicitDiagnostic =
      "category=HANDLER_CLASS_LOADING_FAILURE failureStage=handler-artifact " +
        "markerIdentity=robust.marker.missing expectedAnnotation=robust.marker.missing " +
        "metadataHandler=robust.handlers.Missing expectedHandler=robust.handlers.Missing " +
        "markerArtifact=/tmp/marker.jar handlerArtifact=/tmp/handler.jar detail=missing"
    val compactDiagnostic = explicitDiagnostic
    assert(
      validateMetadataDiagnosticParity(
        explicitDiagnostic,
        compactDiagnostic,
        "HANDLER_CLASS_LOADING_FAILURE"
      ).isEmpty
    )
    assert(
      validateMetadataDiagnosticParity(
        explicitDiagnostic,
        compactDiagnostic.replace("expectedHandler=robust.handlers.Missing", "expectedHandler=robust.handlers.Other"),
        "HANDLER_CLASS_LOADING_FAILURE"
      ).nonEmpty
    )
  }
}
