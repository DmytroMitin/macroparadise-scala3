import java.io.File

object IndependentExternalSbtConsumerSpec {
  val CaseCount = 26

  def run(): Unit = {
    import IndependentExternalSbtConsumer._
    var completed = 0
    def check(condition: Boolean, message: String): Unit = {
      require(condition, message)
      completed += 1
    }

    val api = repositoryCoordinate(PluginApiModule)
    val plugin = repositoryCoordinate(PluginModule)
    val handler = producerCoordinate
    check(PluginApiModule == s"macroparadise-scala3-plugin-api_$ExpectedScalaVersion", "API module is not full-cross")
    check(PluginModule == s"macroparadise-scala3-plugin_$ExpectedScalaVersion", "plugin module is not full-cross")
    check(validateSyntheticCoordinate(api).isEmpty, "API coordinate is not synthetic")
    check(validateSyntheticCoordinate(plugin).isEmpty, "plugin coordinate is not synthetic")
    check(validateSyntheticCoordinate(handler).isEmpty, "handler coordinate is not synthetic")
    check(validateSyntheticCoordinate(api.copy(organization = "com.example")).nonEmpty, "public-looking organization was accepted")
    check(validateSyntheticCoordinate(api.copy(version = "1.0.0")).nonEmpty, "non-task version was accepted")
    check(validateMavenRelativePath(api, api.artifactRelative("jar")), "valid Maven path was rejected")
    check(!validateMavenRelativePath(api, "/" + api.artifactRelative("jar")), "absolute Maven path was accepted")
    check(!validateMavenRelativePath(api, api.rootRelative + "/../foreign.jar"), "traversing Maven path was accepted")
    check(expectedIndependentPayload.size == 4, "thin independent payload changed")
    check(!expectedIndependentPayload.exists(_.startsWith("paradise3/")), "thin payload contains API classes")
    check(forbiddenModuleFragments.contains("plugin-test-markers"), "marker exclusion is absent")
    check(forbiddenModuleFragments.contains("plugin-test-handlers"), "handler-fixture exclusion is absent")
    check(forbiddenModuleFragments.contains("quasiquotes"), "quasiquotes exclusion is absent")

    val pluginFile = new File("/task/cache/" + PluginModule + "-" + Version + ".jar")
    val apiFile = new File("/task/cache/" + PluginApiModule + "-" + Version + ".jar")
    val handlerFile = new File("/task/cache/" + IndependentModule + "-" + Version + ".jar")
    val options = pluginOptions(pluginFile, apiFile, Some(handlerFile))
    check(options.head.contains(File.pathSeparator), "plugin option does not use platform separator")
    check(options.contains("-Xplugin-require:helloWorld"), "plugin require option is absent")
    check(options.exists(_.contains("handlerClasspath=")), "handler option is absent")
    check(pluginOptions(pluginFile, apiFile, None).forall(!_.contains("handlerClasspath=")), "missing-handler options retained handler path")

    val validGraph = Vector(
      (RepositoryOrganization, PluginModule, Version, pluginFile.getAbsolutePath),
      (RepositoryOrganization, PluginApiModule, Version, apiFile.getAbsolutePath),
      (ProducerOrganization, IndependentModule, Version, handlerFile.getAbsolutePath),
      ("org.scala-lang", "scala3-compiler_3", ExpectedScalaVersion, "/task/cache/compiler.jar")
    )
    check(validateResolvedGraph(validGraph, Vector("/workspace/target/classes")).isEmpty, "valid graph was rejected")
    val duplicatePlugin = validGraph.head match {
      case (organization, module, version, _) => (organization, module, version, "/other/plugin.jar")
    }
    check(validateResolvedGraph(validGraph :+ duplicatePlugin, Vector.empty).exists(_.contains("plugin coordinate")), "duplicate plugin coordinate was accepted")
    check(validateResolvedGraph(validGraph.filterNot(_._2 == PluginApiModule), Vector.empty).exists(_.contains("pluginApi coordinate")), "missing API coordinate was accepted")
    check(validateResolvedGraph(validGraph :+ ("x", "plugin-test-markers", Version, "/task/cache/markers.jar"), Vector.empty).exists(_.contains("forbidden module")), "forbidden marker module was accepted")
    val workspaceGraph = validGraph.map {
      case (organization, module, version, _) if module == IndependentModule =>
        (organization, module, version, "/workspace/target/classes/handler.jar")
      case value => value
    }
    check(validateResolvedGraph(workspaceGraph, Vector("/workspace/target/classes")).exists(_.contains("forbidden workspace path")), "workspace classpath was accepted")
    check(StagingClassification.startsWith("TASK_OWNED_"), "staging classification changed")
    check(PublicationClassification == "NO_GLOBAL_OR_REMOTE_PUBLICATION_PERFORMED", "publication guard classification changed")

    require(completed == CaseCount, s"focused model spec ran $completed/$CaseCount cases")
  }
}
