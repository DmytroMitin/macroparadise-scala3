import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

object IndependentPrecompiledHandlerPackagedConsumerSpec {
  val CaseCount = 20

  def run(repositoryRoot: File): Unit = {
    val independentRoot = new File(repositoryRoot, "plugin-api-handler-contract-probe/positive")
    val consumerRoot = new File(repositoryRoot, "plugin-api-handler-contract-probe/e2e")
    val independentSource = new File(independentRoot, "IndependentMarkerAndHandler.scala")
    val consumerSource = new File(consumerRoot, "IndependentPackagedConsumer.scala")
    val independent = read(independentSource)
    val consumer = read(consumerSource)
    var completed = 0

    def check(condition: Boolean, message: String): Unit = {
      require(condition, message)
      completed += 1
    }

    check(scalaSources(independentRoot) == Vector(independentSource.getCanonicalFile), "independent source inventory changed")
    check(scalaSources(consumerRoot) == Vector(consumerSource.getCanonicalFile), "consumer source inventory changed")
    check(independent.contains("package contractprobe"), "independent package changed")
    check(independent.contains("final class IndependentMarker"), "independent marker class changed")
    check(independent.contains("final class IndependentHandler extends ParadiseAnnotationExpander"), "independent handler parent changed")
    check(independent.contains("@expander(\"contractprobe.IndependentHandler\")"), "independent marker metadata changed")
    check(independent.contains("val annotationName: String = \"IndependentMarker\""), "production annotation-name identity changed")
    check(independent.contains("ExpansionHelpers.withAnnotatedClassView(input)"), "structured class view is not used")
    check(independent.contains("ExpansionHelpers.addStringMethodToClass("), "bounded string-method helper is not used")
    check(independent.contains("methodName = \"independentHandlerName\""), "generated method name changed")
    check(!independent.contains("macroparadise."), "independent source imports plugin implementation")
    check(!independent.contains("paradise3.external"), "independent source imports repository markers")
    check(consumer.contains("import contractprobe.IndependentMarker"), "consumer does not import the independent marker")
    check(consumer.contains("@IndependentMarker"), "consumer is not ordinarily annotated")
    check(consumer.contains("final class IndependentConsumerUser"), "consumer class changed")
    check(consumer.contains("object IndependentPackagedConsumer"), "consumer entrypoint changed")
    check(consumer.contains("new IndependentConsumerUser().independentHandlerName"), "consumer does not typecheck the generated method")
    check(!consumer.contains("paradise3."), "consumer imports repository fixtures")
    check(
      IndependentPrecompiledHandlerPackagedConsumer.expectedCompiledEntries == Set(
        "contractprobe/IndependentHandler.class",
        "contractprobe/IndependentHandler.tasty",
        "contractprobe/IndependentMarker.class",
        "contractprobe/IndependentMarker.tasty"
      ),
      "thin compiled-entry allowlist changed"
    )
    check(
      IndependentPrecompiledHandlerPackagedConsumer.forbiddenClasspathFragments.contains("plugin-test-markers"),
      "repository marker classpath exclusion changed"
    )
    require(completed == CaseCount, s"focused model spec ran $completed/$CaseCount cases")
  }

  private def scalaSources(root: File): Vector[File] = {
    if (!root.isDirectory) Vector.empty
    else root.listFiles().toVector.filter(file => file.isFile && file.getName.endsWith(".scala")).map(_.getCanonicalFile).sortBy(_.getAbsolutePath)
  }

  private def read(file: File): String =
    new String(Files.readAllBytes(file.toPath), StandardCharsets.UTF_8)
}
