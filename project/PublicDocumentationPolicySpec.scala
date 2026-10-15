import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

object PublicDocumentationPolicySpec {
  val CaseCount = 67
  private val Slash = "/"
  private def path(root: String, rest: String): String = root + Slash + rest
  private def controlRepository(root: String): String = root + "-scala3-" + "control"
  private val ParityGuidePath = "docs/AUTHORING_MODES_AND_FEATURE_PARITY.md"
  private val IncludedPaths = PublicDocumentationPolicy.RequiredPaths + ParityGuidePath + "sbt-integration/README.md"

  def run(): Unit = {
    assert(PublicDocumentationPolicy.RequiredPaths.contains("docs/QUASIQUOTE_ARCHITECTURE.md"))
    assert(PublicDocumentationPolicy.RequiredPaths.contains("docs/EXPANSION_MODEL_AND_COMPOSITION.md"))
    assert(PublicDocumentationPolicy.RequiredPaths.contains("docs/EMBEDDED_PRODUCER_AUTHORING.md"))
    assert(PublicDocumentationPolicy.RequiredPaths.contains(ParityGuidePath))
    assert(PublicDocumentationPolicy.RequiredPaths.contains("examples/embedded-producer-starter/README.md"))
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
      CanonicalAuthoring.replace(
        "ExpansionTransforms.placeMemberInPrimary",
        "ExpansionHelpers.placeMemberInPrimary"
      ),
      "EDIT_FIRST_TRANSFORMS_MISSING"
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
    assertFinding(
      "docs/EMBEDDED_PRODUCER_AUTHORING.md",
      CanonicalEmbeddedGuide.replace("source-built/local-development only", "available remotely"),
      "EMBEDDED_GUIDE_INCOMPLETE"
    )
    assertFinding(
      "docs/EMBEDDED_PRODUCER_AUTHORING.md",
      CanonicalEmbeddedGuide.replace("precompiled producer", "producer"),
      "EMBEDDED_GUIDE_INCOMPLETE"
    )
    assertFinding(
      "docs/EMBEDDED_PRODUCER_AUTHORING.md",
      CanonicalEmbeddedGuide.replace("generated adapter name is not a public contract", "generated adapter"),
      "EMBEDDED_GUIDE_INCOMPLETE"
    )
    assertFinding(
      "docs/EMBEDDED_PRODUCER_AUTHORING.md",
      CanonicalEmbeddedGuide.replace("same-module mode is documented separately", "same-module setup"),
      "EMBEDDED_GUIDE_INCOMPLETE"
    )
    assertFinding(
      "docs/EMBEDDED_PRODUCER_AUTHORING.md",
      CanonicalEmbeddedGuide.replace("no producer `publishLocal`", "publish the producer locally"),
      "EMBEDDED_GUIDE_INCOMPLETE"
    )
    assertFinding(
      "docs/EMBEDDED_PRODUCER_AUTHORING.md",
      CanonicalEmbeddedGuide.replace("External handler authoring remains supported", "Embedded only"),
      "EMBEDDED_GUIDE_INCOMPLETE"
    )
    assertFinding(
      "docs/EMBEDDED_PRODUCER_AUTHORING.md",
      CanonicalEmbeddedGuide.replace("3.3.8, 3.8.4, and 3.9.0", "3.x"),
      "EMBEDDED_GUIDE_INCOMPLETE"
    )
    assertFinding(
      "examples/embedded-producer-starter/README.md",
      CanonicalEmbeddedStarterReadme.replace("marker and handler roles", "generated artifact"),
      "EMBEDDED_STARTER_INCOMPLETE"
    )
    assertFinding(
      "docs/EMBEDDED_PRODUCER_AUTHORING.md",
      CanonicalEmbeddedGuide.replace("MacroParadiseIntegration.precompiledEmbeddedProject", "local wiring"),
      "EMBEDDED_FOUR_QUADRANT_RECIPE_INCOMPLETE"
    )
    assertFinding(
      "docs/EMBEDDED_PRODUCER_AUTHORING.md",
      CanonicalEmbeddedGuide.replace("embeddedMarkerPublicationFacade", "marker facade"),
      "EMBEDDED_FOUR_QUADRANT_RECIPE_INCOMPLETE"
    )
    assertFinding(
      "docs/EMBEDDED_PRODUCER_AUTHORING.md",
      CanonicalEmbeddedGuide.replace("EmbeddedProducerRoles.packageRoles", "manual packaging"),
      "EMBEDDED_FOUR_QUADRANT_RECIPE_INCOMPLETE"
    )
    assertFinding(
      "docs/EMBEDDED_PRODUCER_AUTHORING.md",
      CanonicalEmbeddedGuide.replace("MacroParadiseIntegration.embeddedModuleIds", "resolved modules"),
      "EMBEDDED_FOUR_QUADRANT_RECIPE_INCOMPLETE"
    )
    assertFinding(
      "docs/GETTING_STARTED.md",
      CanonicalGetting.replace("## Embedded-producer setup matrix", "## Embedded setup"),
      "EMBEDDED_NAVIGATION_MISSING"
    )
    assertFinding(
      "docs/EXTERNAL_HANDLER_AUTHORING.md",
      CanonicalAuthoring.replace("Embedded producer authoring", "Embedded authoring"),
      "EMBEDDED_NAVIGATION_MISSING"
    )

    assertFinding(
      "README.md",
      CanonicalReadme.replace("## Talks / presentations", "## Community"),
      "TALK_PRESENTATION_REFERENCE_MISSING"
    )
    assertFinding(
      "ROADMAP.md",
      CanonicalRoadmap.replace(
        "There is no composition switch.",
        "Keep source-ordered composition opt-in."
      ),
      "STALE_COMPOSITION_OPT_IN"
    )
    assertFinding(
      "ROADMAP.md",
      CanonicalRoadmap.replace("four quadrants", "three qualified setup modes"),
      "FOUR_SETUP_QUADRANTS_UNDISCOVERABLE"
    )
    assertFinding(
      "README.md",
      CanonicalReadme + "q\"def generated: Int = 1\".asInstanceOf[Defn.Def]\n",
      "CANONICAL_GENERATED_DEFINITION_CAST"
    )
    assertFinding(
      "docs/EXTERNAL_HANDLER_AUTHORING.md",
      CanonicalAuthoring + "q\"def generated: Int = 1\".asInstanceOf[Defn.Def]\n",
      "CANONICAL_GENERATED_DEFINITION_CAST"
    )
    assertFinding(
      "examples/external-handler-starter/handler/src/main/scala/starter/handler/GenerateGreetingHandler.scala",
      CanonicalStarter + "q\"def generated: Int = 1\".asInstanceOf[Defn.Def]\n",
      "CANONICAL_GENERATED_DEFINITION_CAST"
    )

    assertFinding(
      "docs/EXPANSION_MODEL_AND_COMPOSITION.md",
      CanonicalExpansionModel.replace("sourceOrderedHandledAnnotationNames: List[String]", "participantNames"),
      "PARTICIPANT_PROVENANCE_CONTRACT_MISSING"
    )
    assertFinding(
      "docs/EXTERNAL_HANDLER_AUTHORING.md",
      CanonicalAuthoring.replace("first visible staged revision", "generated order"),
      "PARTICIPANT_PROVENANCE_CONTRACT_MISSING"
    )

    assertFinding(
      "docs/SUPPORTED_SCOPE_AND_LIMITATIONS.md",
      CanonicalSupportedScope.replace("macroParadiseSameModuleEmbeddedBinding", "embeddedBinding"),
      "SAME_MODULE_EMBEDDED_SUPPORT_CONTRACT_MISSING"
    )

    assertFinding(
      "docs/EMBEDDED_PRODUCER_AUTHORING.md",
      (CanonicalEmbeddedGuide + CanonicalSameModuleGuide)
        .replace("macroParadiseSameModuleEmbeddedBinding", "embeddedBinding"),
      "SAME_MODULE_EMBEDDED_GUIDE_INCOMPLETE"
    )
    assertFinding(
      "sbt-integration/README.md",
      (CanonicalIntegration + CanonicalSameModuleIntegration)
        .replace("macroParadiseSameModuleEmbedded", "sameModuleEmbedded"),
      "SAME_MODULE_EMBEDDED_RECIPE_INCOMPLETE"
    )
    assertFinding(
      "docs/GETTING_STARTED.md",
      (CanonicalGetting + CanonicalSameModuleGetting)
        .replace("#bounded-same-module-embedded-recipe", "#same-module-recipe"),
      "SAME_MODULE_EMBEDDED_NAVIGATION_MISSING"
    )
    assertFinding(
      "README.md",
      (CanonicalReadme + CanonicalSameModuleReadme)
        .replace("source-built main", "a future release"),
      "SAME_MODULE_EMBEDDED_README_DISCOVERY_MISSING"
    )
    assertFinding(
      "docs/SUPPORTED_SCOPE_AND_LIMITATIONS.md",
      CanonicalSupportedScope + "\nSame-module embedded declaration and use is unsupported.\n",
      "STALE_SAME_MODULE_EMBEDDED_BLANKET_CLAIM"
    )
    assertFinding(
      ParityGuidePath,
      CanonicalParityGuide.replace("bounded embedded same-module", "unsupported"),
      "AUTHORING_MODE_GUIDE_INCOMPLETE"
    )
    assertFinding(
      ParityGuidePath,
      CanonicalParityGuide + "\nAll programs are behaviorally equivalent across the four modes.\n",
      "FULL_BEHAVIORAL_PARITY_OVERCLAIM"
    )
    assertFinding(
      ParityGuidePath,
      CanonicalParityGuide + "\nMultiple same-module bindings are supported.\n",
      "SAME_MODULE_MULTI_BINDING_OVERCLAIM"
    )
    assertFinding(
      ParityGuidePath,
      CanonicalParityGuide + CanonicalExternalPairParity.replace("macroParadiseSameModuleBindings", "sameModuleBindings"),
      "EXACT_TWO_EXTERNAL_PARITY_INCOMPLETE"
    )
    assertFinding(
      "docs/SUPPORTED_SCOPE_AND_LIMITATIONS.md",
      CanonicalSupportedScope + CanonicalExternalPairScope.replace("exactly two independent", "multiple"),
      "EXACT_TWO_EXTERNAL_SUPPORT_CONTRACT_MISSING"
    )
    assertFinding(
      "sbt-integration/README.md",
      CanonicalIntegration + CanonicalSameModuleIntegration +
        CanonicalExternalPairRecipe.replace("macroParadiseSameModuleHandler", "sameModuleHandler"),
      "EXACT_TWO_EXTERNAL_RECIPE_INCOMPLETE"
    )
    assertFinding(
      "docs/GETTING_STARTED.md",
      CanonicalGetting + CanonicalSameModuleGetting +
        CanonicalExternalPairGetting.replace("#bounded-two-independent-external-same-module-recipe", "#external-pair"),
      "EXACT_TWO_EXTERNAL_NAVIGATION_MISSING"
    )
    assertFinding(
      "README.md",
      CanonicalReadme + CanonicalSameModuleReadme +
        CanonicalExternalPairReadme.replace("exact pair", "future capability"),
      "EXACT_TWO_EXTERNAL_README_DISCOVERY_MISSING"
    )
    assertFinding(
      ParityGuidePath,
      CanonicalParityGuide + CanonicalExternalPairParity +
        "\nAll same-module use allows one binding.\n",
      "STALE_EXACT_TWO_EXTERNAL_BLANKET_CLAIM"
    )
    assertFinding(
      ParityGuidePath,
      CanonicalParityGuide + CanonicalExternalPairParity +
        "\nAll multi-binding same-module use is unsupported.\n",
      "STALE_EXACT_TWO_EXTERNAL_BLANKET_CLAIM"
    )
    assertFinding(
      ParityGuidePath,
      CanonicalParityGuide + CanonicalExternalPairParity +
        "\nArbitrary multiple same-module bindings are supported.\n",
      "SAME_MODULE_MULTI_BINDING_OVERCLAIM"
    )
    assertFinding(
      ParityGuidePath,
      CanonicalParityGuide + "\nA same sbt multi-project build is the same Scala compilation module.\n",
      "COMPILATION_TOPOLOGY_CONFLATED"
    )
    assertFinding(
      "README.md",
      (CanonicalReadme + CanonicalSameModuleReadme).replace("docs/AUTHORING_MODES_AND_FEATURE_PARITY.md", "docs/GETTING_STARTED.md"),
      "AUTHORING_MODE_GUIDE_NAVIGATION_MISSING"
    )
    assertFinding(
      "docs/GETTING_STARTED.md",
      (CanonicalGetting + CanonicalSameModuleGetting).replace("AUTHORING_MODES_AND_FEATURE_PARITY.md", "EMBEDDED_PRODUCER_AUTHORING.md"),
      "AUTHORING_MODE_GUIDE_NAVIGATION_MISSING"
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
      CanonicalReadme + CanonicalSameModuleReadme + CanonicalExternalPairReadme
    )
    write(root, "docs/SUPPORTED_SCOPE_AND_LIMITATIONS.md", CanonicalSupportedScope + CanonicalExternalPairScope)
    write(root, ParityGuidePath, CanonicalParityGuide + CanonicalExternalPairParity)
    write(root, "docs/GETTING_STARTED.md", CanonicalGetting + CanonicalSameModuleGetting + CanonicalExternalPairGetting)
    write(root, "docs/EMBEDDED_PRODUCER_AUTHORING.md", CanonicalEmbeddedGuide + CanonicalSameModuleGuide)
    write(root, "examples/embedded-producer-starter/README.md", CanonicalEmbeddedStarterReadme)
    write(root, "sbt-integration/README.md", CanonicalIntegration + CanonicalSameModuleIntegration + CanonicalExternalPairRecipe)
    write(root, "docs/EXTERNAL_HANDLER_AUTHORING.md", CanonicalAuthoring)
    write(root, "docs/EXPANSION_MODEL_AND_COMPOSITION.md", CanonicalExpansionModel)
    write(root, "ROADMAP.md", CanonicalRoadmap)
    write(root, "CONTRIBUTING.md", "# Contributing\n\nOrdinary input and review are welcome. See [security](SECURITY.md).\n")
    write(root, "SECURITY.md", "# Security\n\nSee [stability](docs/VERSIONING_AND_STABILITY.md).\n")
    write(root, "SUPPORT.md", "# Support\n\nSee [limitations](docs/SUPPORTED_SCOPE_AND_LIMITATIONS.md).\n")
    write(
      root,
      "examples/external-handler-starter/handler/src/main/scala/starter/handler/GenerateGreetingHandler.scala",
      CanonicalStarter
    )

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
      |
      |## Talks / presentations
      |**Can Scala 3 Have Macro Annotations Again? Rebuilding Macro Paradise**
      |London Scala User Group, 9 September 2026
      |https://github.com/DmytroMitin/macroparadise-talk-09-2026
      |https://github.com/DmytroMitin/macroparadise-talk-09-2026/blob/main/draft/draft_v8.md
      |https://github.com/DmytroMitin/macroparadise-talk-09-2026/blob/main/macroparadise-talk-09-2026-literal-v8.pdf

      |See [roadmap](ROADMAP.md), [getting started](docs/GETTING_STARTED.md), [authoring modes](docs/AUTHORING_MODES_AND_FEATURE_PARITY.md), [external handler authoring](docs/EXTERNAL_HANDLER_AUTHORING.md), and [website](https://example.com).
      |""".stripMargin

  private val CanonicalSameModuleReadme =
    """
      |## Bounded same-module embedded on source-built main
      |Current unreleased source-built `0.2.0-SNAPSHOT` supports one bounded experimental topology.
      |The immutable `0.1.1` release is unchanged; no remote `0.2.0-SNAPSHOT` is published.
      |See the [same-module embedded recipe](sbt-integration/README.md#bounded-same-module-embedded-recipe).
      |""".stripMargin

  private val CanonicalRoadmap =
    """# Roadmap
      |
      |There is no composition switch. Handled annotations use the plugin-owned current-staged-tree scheduler, which rescans after committed stages. Annotation-specific applicability belongs in ExpansionHandler.expand; MacroParadise owns framework-level syntactic target eligibility. No public admission/profile metadata is required.
      |
      |The setup contract is two producer topologies crossed with two wiring styles: four quadrants.
      |
      |See [support](SUPPORT.md#support).
      |""".stripMargin

  private val CanonicalStarter =
    """package starter.handler
      |
      |import scala.meta.*
      |
      |val definition = q"def generatedGreeting: String = \"Hello, Greeter!\""
      |""".stripMargin

  private val CanonicalSupportedScope =
    """# Supported scope and limitations
      |
      |On unreleased source-built `0.2.0-SNAPSHOT`, one explicit `SameModuleEmbeddedBinding` is experimentally supported through `MacroParadiseSameModulePlugin`, `macroParadiseSameModuleEmbeddedBinding`, and `macroParadiseSameModuleEmbedded`.
      |The topology has one physical producer source and consumers in different physical source files.
      |Exact Scala 3.3.8, 3.8.4, and 3.9.0 require JDK 25 and sbt 1.12.15.
      |Annotation arguments remain pre-typer syntax and the generated adapter remains private.
      |The external and embedded bindings are mutually exclusive.
      |The same physical source file, multiple embedded bindings, automatic discovery, cycles, general same-module support, and native JPS are unsupported.
      |The immutable `0.1.1` release is unchanged and `0.2.0-SNAPSHOT` is not remotely published.
      |""".stripMargin

  private val CanonicalParityGuide =
    """# Authoring modes and feature parity
      |
      || Authoring frontend | Precompiled in other module | Same Scala module |
      || --- | --- | --- |
      || @expander | explicit marker/handler | singleton Model A |
      || @embeddedExpander | precompiled generated adapter | bounded embedded same-module |
      |
      |Both frontends reach ExpansionHandler.expand(ExpansionInput): ExpansionOutcome and the shared staged scheduler and transaction.
      |Shared backend parity does not prove all program behavior equivalent.
      |Authoring declaration parity: no. Topology parity: no. General same-module multi-binding support: no.
      |The same-module settings are macroParadiseSameModuleBinding, macroParadiseSameModuleBindings, and macroParadiseSameModuleEmbeddedBinding; they are mutually exclusive.
      |Released 0.1.1 contains external-handler Model A; embeddedExpander and the current handler protocol are source-built 0.2.0-SNAPSHOT only.
      |Native JPS and direct Java gutter Run are not qualified; IntelliJ evidence uses sbt import, sbt-delegated Build, and sbt shell run.
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
      |ExpansionTransforms.placeMemberInPrimary
      |Either.flatMap
      |ExpansionHelpers remains the primitive layer
      |MissingCompanionPolicy.Create
      |import com.example.`macro`.annotations.identity
      |@identity
      |@com.example.`macro`.annotations.identity
      |macroparadise-scala3-plugin-api
      |"org.scala-lang" %% "scala3-compiler"
      |"com.github.dmytromitin" % "macroparadise-scala3-plugin-api" % "0.2.0-SNAPSHOT"
      |
      |## Embedded producer authoring
      |See [embedded producer guide](EMBEDDED_PRODUCER_AUTHORING.md); the external form remains first-class.
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
      |sourceOrderedHandledAnnotationNames: List[String]
      |first visible staged revision
      |scheduler provenance
      |""".stripMargin

  private val CanonicalExpansionModel =
    """# Expansion model and scheduling
      |
      |sourceOrderedHandledAnnotationNames: List[String]
      |first visible staged revision
      |scheduler provenance
      |""".stripMargin

  private val CanonicalGetting =
    """# Getting started
      |
      |See [authoring modes](AUTHORING_MODES_AND_FEATURE_PARITY.md).
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
      |## Embedded-producer setup matrix
      |See [embedded producer authoring](EMBEDDED_PRODUCER_AUTHORING.md) and the [embedded starter](../examples/embedded-producer-starter/README.md).
      |
      |Continue with the [README example](../README.md#a-small-user-authored-example):
      |GenerateGreetingHandler produces generatedGreeting.
      |""".stripMargin

  private val CanonicalSameModuleGetting =
    """
      |## Bounded same-module embedded onboarding
      |This is for unreleased source-built `0.2.0-SNAPSHOT`, not a remote snapshot.
      |Use JDK 25, sbt 1.12.15, and exact Scala 3.3.8, 3.8.4, or 3.9.0.
      |Follow the [bounded same-module embedded recipe](../sbt-integration/README.md#bounded-same-module-embedded-recipe).
      |""".stripMargin

  private val CanonicalEmbeddedGuide =
    """# Embedded producer authoring
      |
      |Current `0.2.0-SNAPSHOT` is source-built/local-development only.
      |Use a precompiled producer on exact Scala 3.3.8, 3.8.4, and 3.9.0.
      |The generated adapter name is not a public contract.
      |The producer derives separate marker and handler roles.
      |The preferred same-build path requires no producer `publishLocal`.
      |The complete manual producer copies `EmbeddedProducerRoles.scala`.
      |The complete manual consumer copies `ExternalArtifactIdentity.scala`.
      |The same-module mode is documented separately.
      |External handler authoring remains supported.
      |Annotation constructor arguments are pre-typer syntax.
      |MARKER_CROSS_POLICY=EXACT_FULL_CROSS_SAFE_DEFAULT
      |## Preferred same-build sbt integration
      |MacroParadiseIntegration.precompiledEmbeddedProject
      |## Published or resolver-installed role modules
      |embeddedMarkerPublicationFacade
      |embeddedHandlerPublicationFacade
      |precompiledEmbeddedModules
      |## Complete manual producer and consumer
      |EmbeddedProducerRoles.packageRoles
      |EmbeddedProducerRoles.completeHandlerClasspath
      |## Manual resolved consumer
      |MacroParadiseIntegration.embeddedModuleIds
      |""".stripMargin

  private val CanonicalSameModuleGuide =
    """
      |## Choose precompiled or bounded same-module embedded
      |There are two separate embedded producer modes. The precompiled source-built producer retains four setup quadrants and manual wiring.
      |## Bounded same-module embedded producer
      |On unreleased source-built `0.2.0-SNAPSHOT`, this mode uses no separate marker or handler role JARs.
      |Configure one physical producer source and consumers in different physical source files.
      |Use `MacroParadiseSameModulePlugin`, `macroParadiseSameModuleEmbeddedBinding`, and `macroParadiseSameModuleEmbedded`.
      |The path is relative to `macroParadiseSameModuleSourceRoot`, default `Compile / scalaSource`.
      |The external `macroParadiseSameModuleBinding` is mutually exclusive.
      |The generated adapter remains private.
      |Consumers use fully qualified spelling or a source-preceding package-level explicit unaliased import.
      |Use JDK 25, sbt 1.12.15, and exact Scala 3.3.8, 3.8.4, and 3.9.0.
      |In IntelliJ use the `sbt shell`; direct Java gutter Run is not qualified.
      |""".stripMargin

  private val CanonicalEmbeddedStarterReadme =
    """# Embedded producer starter
      |
      |This source-built `0.2.0-SNAPSHOT` starter uses a precompiled producer,
      |separate marker and handler roles, and no producer `publishLocal`.
      |The generated adapter name is not a public contract. This precompiled starter does not demonstrate the same-module mode.
      |External handler authoring remains supported.
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

  private val CanonicalSameModuleIntegration =
    """
      |## Bounded same-module embedded recipe
      |This recipe is for unreleased source-built `0.2.0-SNAPSHOT`, not Maven Central.
      |Use JDK 25, sbt 1.12.15, and exact Scala 3.3.8, 3.8.4, or 3.9.0.
      |Run `pluginApi/publishLocal`, `embeddedProducerPlugin/publishLocal`, and `plugin/publishLocal`.
      |Then run `sbt -batch verifyIntegrationPolicy publishLocal` in `sbt-integration`.
      |`project/build.properties`: `sbt.version=1.12.15`
      |`project/plugins.sbt`: `addSbtPlugin("com.github.dmytromitin" % "sbt-macroparadise" % "0.2.0-SNAPSHOT")`
      |Enable `MacroParadiseSameModulePlugin` and configure `macroParadiseSameModuleEmbeddedBinding` with `macroParadiseSameModuleEmbedded`.
      |Use `annotationName = "demo.sameModuleGreeting"` and `macroParadiseLabelledSource("embedded-producer-source", "demo/EmbeddedAnnotations.scala")`.
      |The producer and consumers are separate files. Use `@demo.sameModuleGreeting` or `import demo.sameModuleGreeting` followed by `@sameModuleGreeting`.
      |Run `sbt -batch compile run` and expect `SAME_MODULE_EMBEDDED_RUNTIME:same-module-v1`.
      |Exactly one public binding is configured; users do not name the generated adapter.
      |External same-module Model A remains separate.
      |""".stripMargin

  private val CanonicalExternalPairReadme =
    """
      |## Exactly two independent external same-module bindings
      |Unreleased source-built `0.2.0-SNAPSHOT` experimentally supports one exact pair through CLI/Zinc and persistent sbt BSP 3.8.4.
      |Released `0.1.1` has no pair support and no remote `0.2.0-SNAPSHOT` exists.
      |See the [bounded pair recipe](sbt-integration/README.md#bounded-two-independent-external-same-module-recipe).
      |""".stripMargin

  private val CanonicalExternalPairGetting =
    """
      |## Exactly two independent external same-module bindings
      |Use source-built `0.2.0-SNAPSHOT`, JDK 25, sbt 1.12.15, and exact Scala 3.3.8, 3.8.4, or 3.9.0.
      |Follow the [complete bounded pair recipe](../sbt-integration/README.md#bounded-two-independent-external-same-module-recipe).
      |The pair is qualified through CLI/Zinc and persistent sbt BSP 3.8.4, not live IntelliJ, native JPS, or direct Java gutter Run.
      |""".stripMargin

  private val CanonicalExternalPairParity =
    """
      |The external same-module cell includes singleton Model A and exactly two independent external bindings on unreleased source-built 0.2.0-SNAPSHOT.
      |The pair uses macroParadiseSameModuleBindings := Seq(...) with exactly two macroParadiseSameModuleHandler records.
      |macroParadiseSameModuleBinding, macroParadiseSameModuleBindings, and macroParadiseSameModuleEmbeddedBinding are mutually exclusive.
      |Four producer files and two separate consumer files form six distinct core files; each consumer unit selects one binding.
      |Pair consumers use direct-qualified consumer spelling only. Deterministically sorted repeated sameModuleHandler options share one aggregate source-byte digest.
      |A no-clean A-v1 to A-v2 edit retains B-v1. Producer edges, one-unit A+B ambiguity, and dynamically introduced cross-binding annotations fail closed.
      |CLI/Zinc is qualified on exact 3.3.8, 3.8.4, and 3.9.0; persistent sbt BSP is qualified on 3.8.4; pair IntelliJ, native JPS, and direct Java gutter Run remain unqualified.
      |Released 0.1.1 has no exact-two pair; 0.2.0-SNAPSHOT is source-built only.
      |General same-module multi-binding support: no. Multiple embedded bindings remain unsupported.
      |""".stripMargin

  private val CanonicalExternalPairScope =
    """
      |## Exactly two independent external same-module bindings
      |On unreleased source-built `0.2.0-SNAPSHOT`, exactly two independent external relationships are experimentally supported with `MacroParadiseSameModulePlugin`, `macroParadiseSameModuleBindings`, `macroParadiseSameModuleHandler`, and `Seq`.
      |The old `macroParadiseSameModuleBinding` singleton and `macroParadiseSameModuleEmbeddedBinding` singleton remain mutually exclusive alternatives.
      |One Scala module contains four globally distinct producer files and two distinct consumer files; each physical consumer unit selects one direct-qualified configured annotation.
      |Exact Scala 3.3.8, 3.8.4, and 3.9.0 use JDK 25 and sbt 1.12.15 through CLI/Zinc; persistent sbt BSP 3.8.4 is qualified, while pair IntelliJ, native JPS, and direct Java gutter Run are not qualified.
      |Sorted repeated W1 relationship options use one aggregate exact-source-byte identity; A-v1 to A-v2 without clean retains B-v1.
      |Producer edges, same-unit ambiguity, and dynamically introduced cross-binding annotations fail closed.
      |Released `0.1.1` has no pair support and `0.2.0-SNAPSHOT` is not remotely published.
      |""".stripMargin

  private val CanonicalExternalPairRecipe =
    """
      |## Bounded two independent external same-module recipe
      |This complete recipe uses source-built `0.2.0-SNAPSHOT`, JDK 25, sbt 1.12.15, and exact Scala 3.3.8, 3.8.4, or 3.9.0; it is not Maven Central.
      |Install `pluginApi/publishLocal` and `plugin/publishLocal`, then run `sbt -batch verifyIntegrationPolicy publishLocal` in `sbt-integration`.
      |Use `sbt.version=1.12.15`, `addSbtPlugin("com.github.dmytromitin" % "sbt-macroparadise" % "0.2.0-SNAPSHOT")`, and `MacroParadiseSameModulePlugin`.
      |Configure `macroParadiseSameModuleBindings := Seq(...)` with exactly two `macroParadiseSameModuleHandler` records and four globally distinct producer files.
      |Use `@demo.markerA` and `@demo.markerB` in two distinct consumer files, one selected binding per consumer unit.
      |The handlers implement `ExpansionHandler`, return `ExpansionOutcome`, and generate observable A-v1 and B-v1 members.
      |Inspect two sorted `sameModuleHandler` options, one `sameModuleSourceIdentity=sha256:` option, and the actual machine-local exact plugin path.
      |Edit A-v1 to A-v2 without `clean`, retain B-v1, and run a second no-op witness.
      |The key accepts exactly two members; use `macroParadiseSameModuleBinding` for one and do not infer a manual same-module wiring contract.
      |Producer edges, same-unit ambiguity, dynamic cross-binding, mixed embedded/external settings, three or more bindings, and non-direct spellings fail closed.
      |Pair CLI/Zinc is qualified on all three exact lines and persistent sbt BSP on 3.8.4; live IntelliJ, native JPS, and direct Java gutter Run are not qualified.
      |Released `0.1.1` has no exact-two pair and no remote `0.2.0-SNAPSHOT` exists.
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
