import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

object PublicDocumentationPolicySpec {
  val CaseCount = 25
  private val Slash = "/"
  private def path(root: String, rest: String): String = root + Slash + rest
  private def controlRepository(root: String): String = root + "-scala3-" + "control"
  private val IncludedPaths = PublicDocumentationPolicy.RequiredPaths + "sbt-integration/README.md"

  def run(): Unit = {
    assert(PublicDocumentationPolicy.RequiredPaths.contains("docs/QUASIQUOTE_ARCHITECTURE.md"))
    assert(PublicDocumentationPolicy.RequiredPaths.contains("docs/EXPANSION_MODEL_AND_COMPOSITION.md"))
    val clean = fixture()
    try {
      val result = PublicDocumentationPolicy.verify(clean, IncludedPaths)
      assert(result.errors.isEmpty, result.errors.mkString("; "))
      assert(
        result.checkedPaths == IncludedPaths.toVector.sorted.filter(_.endsWith(".md"))
      )
    } finally delete(clean)

    assertFinding("README.md", s"See [private history](${path("prompts", "110.md")}).\n", "PRIVATE_HISTORY_PATH")
    assertFinding("README.md", s"See [private reviews](${path("reviews", "110/")}).\n", "PRIVATE_HISTORY_PATH")
    assertFinding("README.md", s"See ${controlRepository("macroparadise")}.\n", "CONTROL_REPOSITORY")
    assertFinding("README.md", s"See ${path("input", "22_private_exchange.md")}.\n", "PRIVATE_EXCHANGE_PATH")
    assertFinding("README.md", "Follow bootstrap-" + "prompt.md and AGENTS" + ".md.\n", "PRIVATE_INSTRUCTION_FILE")
    assertFinding("README.md", "Prompt " + "110 follows Phase " + "86.\n", "PRIVATE_PROCESS_CHRONOLOGY")
    assertFinding("README.md", "Use " + Slash + "home/alice/work/project.\n", "LOCAL_ABSOLUTE_PATH")
    assertFinding(
      "README.md",
      "See [rollback](docs/external-failed-release-rollback-" + "policy.md).\n",
      "PRIVATE_CONTROLLER_DOCUMENT"
    )
    assertFinding("README.md", "Consult the private readiness " + "ledger.\n", "PRIVATE_PROCESS_ARTIFACT")
    assertFinding("README.md", "Implementation follows lanes " + "P1-P7 and C1-C6.\n", "PRIVATE_LANE_NOTATION")
    assertFinding("README.md", "A missing generated member reports " + "E046.\n", "PRIVATE_DIAGNOSTIC_ID")
    assertFinding("README.md", "See [missing](docs/MISSING.md).\n", "BROKEN_RELATIVE_LINK")
    assertFinding(
      "docs/EXTERNAL_HANDLER_AUTHORING.md",
      "# External handler authoring\n\nSee the executable fixture.\n",
      "IDENTITY_TUTORIAL_INCOMPLETE"
    )
    assertFinding(
      "docs/EXTERNAL_HANDLER_AUTHORING.md",
      CanonicalAuthoring.replace("## Manual same-build local projects", "## Local fixture link"),
      "MANUAL_LOCAL_RECIPE_INCOMPLETE"
    )
    assertFinding(
      "docs/EXTERNAL_HANDLER_AUTHORING.md",
      CanonicalAuthoring.replace("## Manual published marker and handler modules", "## Published fixture link"),
      "MANUAL_PUBLISHED_RECIPE_INCOMPLETE"
    )
    assertFinding(
      "docs/EXTERNAL_HANDLER_AUTHORING.md",
      CanonicalAuthoring.replace("## AutoPlugin to manual translation", "## Integration summary"),
      "AUTOPLUGIN_MANUAL_TRANSLATION_MISSING"
    )
    assertFinding(
      "docs/EXTERNAL_HANDLER_AUTHORING.md",
      CanonicalAuthoring.replace("\"0.2.0-SNAPSHOT\"", "\"0.1.1\""),
      "DOCUMENTED_VERSION_IDENTITY_BLURRED"
    )
    assertFinding(
      "README.md",
      CanonicalReadme.replace("import com.example.`macro`.annotations.identity", "// consumer omitted"),
      "README_IDENTITY_CONSUMER_MISSING"
    )
    assertFinding(
      "docs/EXTERNAL_HANDLER_AUTHORING.md",
      CanonicalAuthoring.replace(
        "ExpansionEdit.finish(ExpansionEdit.start(input))",
        "ExpansionOutcome.Expanded(List(input.primary.tree))"
      ),
      "IDENTITY_CANONICAL_BODY_DIVERGED"
    )
    assertFinding(
      "docs/GETTING_STARTED.md",
      CanonicalGetting
        .replace("GenerateGreetingHandler", "GenHandler")
        .replace("generatedGreeting", "generatedHello"),
      "STALE_GENERATED_EXAMPLE_CROSS_REFERENCE"
    )
    assertFinding(
      "docs/GETTING_STARTED.md",
      CanonicalGetting.replace("## External-handler setup matrix", "## Setup choices"),
      "FOUR_SETUP_QUADRANTS_UNDISCOVERABLE"
    )
    assertFinding(
      "sbt-integration/README.md",
      CanonicalIntegration.replace("## Published marker and handler modules", "## Resolved producers"),
      "FOUR_SETUP_QUADRANTS_UNDISCOVERABLE"
    )

    val missing = fixture()
    try {
      Files.delete(new File(missing, "SUPPORT.md").toPath)
      val included = IncludedPaths - "SUPPORT.md"
      val result = PublicDocumentationPolicy.verify(missing, included)
      assert(result.errors.exists(error => error.code == "MISSING_PUBLIC_DOCUMENT" && error.path == "SUPPORT.md"))
    } finally delete(missing)
  }

  private def assertFinding(path: String, content: String, code: String): Unit = {
    val root = fixture()
    try {
      write(root, path, content)
      val result = PublicDocumentationPolicy.verify(root, IncludedPaths)
      assert(
        result.errors.exists(error => error.path == path && error.code == code),
        result.errors.mkString("; ")
      )
    } finally delete(root)
  }

  private def fixture(): File = {
    val root = Files.createTempDirectory("public-documentation-policy-spec-").toFile
    IncludedPaths.foreach(path => write(root, path, s"# ${path.replace('/', ' ')}\n"))
    write(
      root,
      "README.md",
      CanonicalReadme
    )
    write(root, "docs/GETTING_STARTED.md", CanonicalGetting)
    write(root, "sbt-integration/README.md", CanonicalIntegration)
    write(root, "docs/EXTERNAL_HANDLER_AUTHORING.md", CanonicalAuthoring)
    write(root, "ROADMAP.md", "# Roadmap\n\nSee [support](SUPPORT.md#support).\n")
    write(root, "CONTRIBUTING.md", "# Contributing\n\nOrdinary input and review are welcome. See [security](SECURITY.md).\n")
    write(root, "SECURITY.md", "# Security\n\nSee [stability](docs/VERSIONING_AND_STABILITY.md).\n")
    write(root, "SUPPORT.md", "# Support\n\nSee [limitations](docs/SUPPORTED_SCOPE_AND_LIMITATIONS.md).\n")
    root
  }

  private val CanonicalReadme =
    """# Project
      |
      |final class IdentityHandler extends ExpansionHandler
      |ExpansionEdit.finish(ExpansionEdit.start(input))
      |import com.example.`macro`.annotations.identity
      |@identity
      |class Something
      |The next step is source-like member generation.
      |final class GenerateGreetingHandler extends ExpansionHandler
      |def generatedGreeting: String = "Hello"
      |See [roadmap](ROADMAP.md), [getting started](docs/GETTING_STARTED.md), [external handler authoring](docs/EXTERNAL_HANDLER_AUTHORING.md), and [website](https://example.com).
      |""".stripMargin

  private val CanonicalAuthoring =
    """# External handler authoring
      |
      |## Minimal `@identity` first use
      |### Project roles
      |marker handler consumer
      |@expander("com.example.macro.handlers.IdentityHandler")
      |final class IdentityHandler extends ExpansionHandler
      |def annotationName: String
      |def expand(input: ExpansionInput)(using Context): ExpansionOutcome
      |ExpansionEdit.finish(ExpansionEdit.start(input))
      |import com.example.`macro`.annotations.identity
      |@identity
      |@com.example.`macro`.annotations.identity
      |macroparadise-scala3-plugin-api
      |"org.scala-lang" %% "scala3-compiler"
      |"com.github.dmytromitin" % "macroparadise-scala3-plugin-api" % "0.2.0-SNAPSHOT"
      |
      |## Manual same-build local projects
      |.dependsOn(macroAnnotations % "provided->compile")
      |compilerPlugin(mpPlugin)
      |(macroAnnotations / Compile / packageBin).value
      |(macroHandlers / Runtime / dependencyClasspath).value
      |ExternalArtifactIdentity.combined(
      |-Xplugin-require:macroparadise
      |-P:macroparadise:handlerClasspath=
      |-P:macroparadise:externalArtifactIdentity=sha256:
      |manual translation of `MacroParadiseIntegration.precompiledProjects(macroAnnotations, macroHandlers)`
      |val mpVersion = "0.2.0-SNAPSHOT"
      |
      |## Manual published marker and handler modules
      |config("macroParadiseHandler").hide
      |libraryDependencies ++= markerModules
      |libraryDependencies ++= handlerModules.map(_ % MacroParadiseHandler.name)
      |configured $role module did not resolve
      |val direct = resolveConfigured(modules, classpath, "handler")
      |direct ++ transitive
      |manual translation of `macroParadiseMarkerModules := markerModules` and `macroParadiseHandlerModules := handlerModules`
      |val mpVersion = "0.2.0-SNAPSHOT"
      |
      |## AutoPlugin to manual translation
      |MacroParadiseIntegration.precompiledProjects
      |macroParadiseMarkerModules
      |macroParadiseHandlerModules
      |ExternalArtifactIdentity.combined
      |externalArtifactIdentity
      |""".stripMargin

  private val CanonicalGetting =
    """# Getting started
      |
      |## External-handler setup matrix
      |
      || Producer topology | `sbt-macroparadise` | Manual / no sbt plugin |
      ||---|---|---|
      || local same-build marker + handler projects | [AutoPlugin local](../sbt-integration/README.md#local-marker-and-handler-projects) | [manual local](EXTERNAL_HANDLER_AUTHORING.md#manual-same-build-local-projects) |
      || published / resolver-installed marker + handler modules | [AutoPlugin published](../sbt-integration/README.md#published-marker-and-handler-modules) | [manual published](EXTERNAL_HANDLER_AUTHORING.md#manual-published-marker-and-handler-modules) |
      |
      |These are two producer topologies and two wiring styles, not four different MacroParadise semantic modes.
      |Same-build local projects are distinct from `publishLocal`-installed module coordinates.
      |Published marker/handler modules are distinct from publication of MacroParadise itself.
      |Exact Scala lines require `CrossVersion.full`.
      |
      |Continue with the [README example](../README.md#a-small-user-authored-example):
      |GenerateGreetingHandler produces generatedGreeting.
      |""".stripMargin

  private val CanonicalIntegration =
    """# sbt integration
      |
      |## Local marker and handler projects
      |MacroParadiseIntegration.precompiledProjects(macroAnnotations, macroHandlers)
      |.dependsOn(macroAnnotations % "provided->compile")
      |.enablePlugins(macroparadise.sbt.MacroParadisePrecompiledPlugin)
      |
      |## Published marker and handler modules
      |macroParadiseMarkerModules := markerModules
      |macroParadiseHandlerModules := handlerModules
      |% Provided
      |marker compile dependency and hidden tool-only handler closure
      |""".stripMargin

  private def write(root: File, relative: String, content: String): Unit = {
    val file = new File(root, relative)
    Option(file.getParentFile).foreach(_.mkdirs())
    Files.write(file.toPath, content.getBytes(StandardCharsets.UTF_8))
  }

  private def delete(file: File): Unit = {
    if (file.isDirectory) Option(file.listFiles()).getOrElse(Array.empty).foreach(delete)
    Files.deleteIfExists(file.toPath)
  }
}
