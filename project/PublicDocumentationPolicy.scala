import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

object PublicDocumentationPolicy {
  private def assembled(parts: String*): String = parts.mkString
  private val Slash = "/"

  final case class Finding(code: String, path: String, detail: String) {
    override def toString: String = s"$code:$path:$detail"
  }

  final case class Verification(checkedPaths: Vector[String], errors: Vector[Finding])

  val RequiredPaths: Set[String] = Set(
    "LICENSE",
    "README.md",
    "ROADMAP.md",
    "CONTRIBUTING.md",
    "SECURITY.md",
    "SUPPORT.md",
    "docs/GETTING_STARTED.md",
    "docs/ARCHITECTURE.md",
    "docs/QUASIQUOTE_ARCHITECTURE.md",
    "docs/SUPPORTED_SCOPE_AND_LIMITATIONS.md",
    "docs/EXTERNAL_HANDLER_AUTHORING.md",
    "docs/EXPANSION_MODEL_AND_COMPOSITION.md",
    "docs/DIAGNOSTICS.md",
    "docs/COMPATIBILITY.md",
    "docs/VERSIONING_AND_STABILITY.md",
    "sbt-integration/README.md"
  )

  private val PrivateControllerDocuments = Set(
    assembled("codex-git-publication-", "contract.md"),
    assembled("external-api-publication-readiness-", "decision.md"),
    assembled("external-artifact-scope-and-compatibility-", "policy.md"),
    assembled("external-failed-release-rollback-", "policy.md"),
    assembled("external-immutable-release-identity-retention-", "policy.md"),
    assembled("external-production-pom-metadata-implementation-", "readiness.md"),
    assembled("external-public-artifact-naming-and-pom-metadata-", "policy.md"),
    assembled("external-redistribution-source-doc-artifact-", "audit.md"),
    assembled("external-release-signature-authenticity-", "policy.md"),
    assembled("external-reproducibility-provenance-sbom-", "policy.md"),
    assembled("quasiquotes-constructed-term-backend-consumer-", "proof.md"),
    assembled("quasiquotes-generated-origin-position-contract-", "feasibility.md"),
    assembled("quasiquotes-positioned-contextual-method-friend-", "consumer.md"),
    assembled("quasiquotes-scala3-integration-", "plan.md"),
    assembled("task-owned-local-repository-external-sbt-", "consumer.md")
  )

  private val MarkdownLink = """!?\[[^\]]*\]\(([^)\s]+)(?:\s+[\"'][^\"']*[\"'])?\)""".r
  private val ExternalScheme = """^[A-Za-z][A-Za-z0-9+.-]*:.*""".r

  def verify(root: File, includedPaths: Set[String]): Verification = {
    val normalizedIncluded = includedPaths.map(normalize)
    val checked = normalizedIncluded.toVector.sorted.filter { path =>
      path.toLowerCase(java.util.Locale.ROOT).endsWith(".md") && new File(root, path).isFile
    }
    val missing = (RequiredPaths -- normalizedIncluded).toVector.sorted.map { path =>
      Finding("MISSING_PUBLIC_DOCUMENT", path, "required public document is absent from the candidate allowlist")
    }
    val findings = checked.flatMap { path =>
      val file = new File(root, path)
      val text = new String(Files.readAllBytes(file.toPath), StandardCharsets.UTF_8)
      scanResidue(path, text) ++ scanLinks(root, normalizedIncluded, path, text)
    }
    val crossDocumentFindings = scanCrossDocumentAcceptance(root, normalizedIncluded)
    Verification(checked, (missing ++ findings ++ crossDocumentFindings).distinct.sortBy(error => (error.path, error.code, error.detail)))
  }

  private def scanResidue(path: String, text: String): Vector[Finding] = {
    val lower = text.toLowerCase(java.util.Locale.ROOT)
    val findings = Vector.newBuilder[Finding]
    if (assembled("(?i)(?:^|[\\s(\"'`])(?:prompts|reviews)", Slash).r.findFirstIn(text).nonEmpty)
      findings += Finding("PRIVATE_HISTORY_PATH", path, "references a private prompt or review path")
    if (assembled("(?i)(?:^|[\\s(\"'`])input", Slash, "[0-9]").r.findFirstIn(text).nonEmpty)
      findings += Finding("PRIVATE_EXCHANGE_PATH", path, "references a private numbered input path")
    if (assembled("(?i)\\b(?:macroparadise|quasiquotes|auxify)-scala3-", "control\\b").r.findFirstIn(text).nonEmpty)
      findings += Finding("CONTROL_REPOSITORY", path, "references a private control repository")
    if (assembled("(?i)\\b(?:bootstrap-", "prompt\\.md|agents\\.md)\\b").r.findFirstIn(text).nonEmpty)
      findings += Finding("PRIVATE_INSTRUCTION_FILE", path, "presents a private reconstruction or agent file as public guidance")
    if ("""(?i)\b(?:prompt|phase)\s*#?\s*[0-9]+\b""".r.findFirstIn(text).nonEmpty)
      findings += Finding("PRIVATE_PROCESS_CHRONOLOGY", path, "contains private numbered process chronology")
    if (assembled(
      "(?i)(?:^|[\\s\"'=])(?:", Slash, "home", Slash,
      "|", Slash, "users", Slash, "|[a-z]:\\\\users\\\\)"
    ).r.findFirstIn(text).nonEmpty)
      findings += Finding("LOCAL_ABSOLUTE_PATH", path, "contains a producer-local absolute path")
    if (assembled(
      "(?i)\\b(?:private ", "handoff|readiness ", "ledger|migration ", "evidence)\\b"
    ).r.findFirstIn(text).nonEmpty)
      findings += Finding("PRIVATE_PROCESS_ARTIFACT", path, "references a private process artifact")
    if ("""\b([PCM])[0-9]+\s*[-–]\s*\1[0-9]+\b""".r.findFirstIn(text).nonEmpty)
      findings += Finding("PRIVATE_LANE_NOTATION", path, "contains private implementation-lane shorthand")
    if ("""\bE[0-9]{3}\b""".r.findFirstIn(text).nonEmpty)
      findings += Finding("PRIVATE_DIAGNOSTIC_ID", path, "contains a private diagnostic fixture identifier")
    PrivateControllerDocuments.toVector.sorted.foreach { name =>
      if (lower.contains(name))
        findings += Finding("PRIVATE_CONTROLLER_DOCUMENT", path, s"references private controller document `$name`")
    }
    scanDocumentationAcceptance(path, text).foreach(findings += _)
    findings.result()
  }

  private def scanDocumentationAcceptance(path: String, text: String): Vector[Finding] = {
    val findings = Vector.newBuilder[Finding]
    val lower = text.toLowerCase(java.util.Locale.ROOT)
    def requireAll(code: String, detail: String, required: String*): Unit =
      if (required.exists(token => !text.contains(token)))
        findings += Finding(code, path, detail)

    if (path == "docs/EXTERNAL_HANDLER_AUTHORING.md") {
      requireAll(
        "IDENTITY_TUTORIAL_INCOMPLETE",
        "canonical guide must contain the complete marker, handler, imported-short consumer, qualified control, producer dependencies, and wiring entry point",
        "## Minimal `@identity` first use",
        "### Project roles",
        "@expander(\"com.example.macro.handlers.IdentityHandler\")",
        "final class IdentityHandler extends ExpansionHandler",
        "def annotationName: String",
        "def expand(input: ExpansionInput)(using Context): ExpansionOutcome",
        "ExpansionEdit.finish(ExpansionEdit.start(input))",
        "import com.example.`macro`.annotations.identity",
        "@identity",
        "@com.example.`macro`.annotations.identity",
        "\"com.github.dmytromitin\" % \"macroparadise-scala3-plugin-api\" % \"0.2.0-SNAPSHOT\"",
        "\"org.scala-lang\" %% \"scala3-compiler\""
      )
      requireAll(
        "EDIT_FIRST_TRANSFORMS_MISSING",
        "canonical guide must present edit-first transform composition, retain primitive helper layering, and keep missing-companion policy explicit",
        "ExpansionTransforms.placeMemberInPrimary",
        "Either.flatMap",
        "ExpansionHelpers remains the primitive layer",
        "MissingCompanionPolicy.Create"
      )
      requireAll(
        "MANUAL_LOCAL_RECIPE_INCOMPLETE",
        "canonical guide must show the complete same-build manual translation inline",
        "## Manual same-build local projects",
        ".dependsOn(macroAnnotations % \"provided->compile\")",
        "compilerPlugin(mpPlugin)",
        "(macroAnnotations / Compile / packageBin).value",
        "(macroHandlers / Runtime / dependencyClasspath).value",
        "ExternalArtifactIdentity.combined(",
        "-Xplugin-require:macroparadise",
        "-P:macroparadise:handlerClasspath=",
        "-P:macroparadise:externalArtifactIdentity=sha256:",
        "manual translation of `MacroParadiseIntegration.precompiledProjects(macroAnnotations, macroHandlers)`"
      )
      requireAll(
        "MANUAL_PUBLISHED_RECIPE_INCOMPLETE",
        "canonical guide must show the complete published-module manual translation inline",
        "## Manual published marker and handler modules",
        "config(\"macroParadiseHandler\").hide",
        "libraryDependencies ++= markerModules",
        "libraryDependencies ++= handlerModules.map(_ % MacroParadiseHandler.name)",
        "configured $role module did not resolve",
        "val direct = resolveConfigured(modules, classpath, \"handler\")",
        "direct ++ transitive",
        "manual translation of `macroParadiseMarkerModules := markerModules` and `macroParadiseHandlerModules := handlerModules`"
      )
      requireAll(
        "AUTOPLUGIN_MANUAL_TRANSLATION_MISSING",
        "canonical guide must map each integration convenience to its manual responsibility",
        "## AutoPlugin to manual translation",
        "MacroParadiseIntegration.precompiledProjects",
        "macroParadiseMarkerModules",
        "macroParadiseHandlerModules",
        "ExternalArtifactIdentity.combined",
        "externalArtifactIdentity"
      )
      val snapshotVersionOccurrences =
        text.split(java.util.regex.Pattern.quote("val mpVersion = \"0.2.0-SNAPSHOT\""), -1).length - 1
      if (snapshotVersionOccurrences < 2)
        findings += Finding(
          "DOCUMENTED_VERSION_IDENTITY_BLURRED",
          path,
          "current ExpansionHandler tutorial and both manual recipes must select 0.2.0-SNAPSHOT explicitly"
        )
    }

    if (path == "README.md") {
      val handler = text.indexOf("final class IdentityHandler extends ExpansionHandler")
      val consumer = text.indexOf("import com.example.`macro`.annotations.identity", handler + 1)
      val generated = text.indexOf("The next step is source-like member generation", handler + 1)
      if (handler < 0 || consumer < 0 || generated < 0 || !(handler < consumer && consumer < generated))
        findings += Finding(
          "README_IDENTITY_CONSUMER_MISSING",
          path,
          "README must show the imported-short identity consumer after the handler and before generated-member authoring"
        )
    }


    if (path == "README.md") {
      requireAll(
        "TALK_PRESENTATION_REFERENCE_MISSING",
        "README must retain the public London Scala User Group talk repository, text, and slide references",
        "## Talks / presentations",
        "Can Scala 3 Have Macro Annotations Again? Rebuilding Macro Paradise",
        "London Scala User Group",
        "9 September 2026",
        "https://github.com/DmytroMitin/macroparadise-talk-09-2026",
        "https://github.com/DmytroMitin/macroparadise-talk-09-2026/blob/main/draft/draft_v8.md",
        "https://github.com/DmytroMitin/macroparadise-talk-09-2026/blob/main/macroparadise-talk-09-2026-literal-v8.pdf"
      )
    }

    if (path == "ROADMAP.md") {
      val staleComposition =
        """(?is)(?:source-ordered\s+composition.{0,80}opt-in|composition\s+opt-in)""".r
          .findFirstIn(text)
          .nonEmpty
      val currentComposition =
        lower.contains("no composition switch") &&
          lower.contains("current-staged-tree") &&
          lower.contains("rescan") &&
          text.contains("ExpansionHandler.expand") &&
          lower.contains("framework-level") &&
          lower.contains("target eligibility") &&
          lower.contains("no public admission/profile metadata")
      if (staleComposition || !currentComposition)
        findings += Finding(
          "STALE_COMPOSITION_OPT_IN",
          path,
          "roadmap must retain plugin-owned current-staged-tree scheduling with handler applicability and no public composition switch"
        )

      val staleSetup =
        """(?i)three(?:\s+qualified)?\s+setup\s+(?:modes|lanes)""".r
          .findFirstIn(text)
          .nonEmpty
      val fourQuadrants =
        """(?is)two\s+producer\s+topologies""".r.findFirstIn(text).nonEmpty &&
          """(?is)two\s+wiring\s+styles""".r.findFirstIn(text).nonEmpty &&
          (
            lower.contains("four quadrants") ||
              lower.contains("4 quadrants") ||
              lower.contains("2 x 2")
          )
      if (staleSetup || !fourQuadrants)
        findings += Finding(
          "FOUR_SETUP_QUADRANTS_UNDISCOVERABLE",
          path,
          "roadmap must retain two producer topologies crossed with two wiring styles as four setup quadrants"
        )
    }

    if (
      (path == "README.md" || path == "docs/EXTERNAL_HANDLER_AUTHORING.md") &&
      text.contains(".asInstanceOf[Defn.Def]")
    )
      findings += Finding(
        "CANONICAL_GENERATED_DEFINITION_CAST",
        path,
        "canonical generated-definition examples must retain the mechanically qualified cast-free Scalameta quasiquote form"
      )

    if (
      path == "docs/EXPANSION_MODEL_AND_COMPOSITION.md" ||
      path == "docs/EXTERNAL_HANDLER_AUTHORING.md"
    ) {
      requireAll(
        "PARTICIPANT_PROVENANCE_CONTRACT_MISSING",
        "scheduler documentation must retain the public getter and generated-cohort ordering boundary",
        "sourceOrderedHandledAnnotationNames: List[String]",
        "first visible staged revision",
        "scheduler provenance"
      )
    }


    val structuredIdentity = "ExpansionEdit.finish(ExpansionEdit.start(input))"
    if (
      (path == "README.md" || path == "docs/EXTERNAL_HANDLER_AUTHORING.md") &&
      !text.contains(structuredIdentity)
    )
      findings += Finding(
        "IDENTITY_CANONICAL_BODY_DIVERGED",
        path,
        "README and the canonical identity tutorial must use the structured ExpansionEdit pass-through"
      )

    if (path == "docs/GETTING_STARTED.md") {
      requireAll(
        "FOUR_SETUP_QUADRANTS_UNDISCOVERABLE",
        "Getting Started must link the two producer topologies across both wiring styles and preserve their semantic distinctions",
        "## External-handler setup matrix",
        "| Producer topology | `sbt-macroparadise` | Manual / no sbt plugin |",
        "../sbt-integration/README.md#local-marker-and-handler-projects",
        "../sbt-integration/README.md#published-marker-and-handler-modules",
        "EXTERNAL_HANDLER_AUTHORING.md#manual-same-build-local-projects",
        "EXTERNAL_HANDLER_AUTHORING.md#manual-published-marker-and-handler-modules",
        "two producer topologies",
        "two wiring styles",
        "not four",
        "different MacroParadise semantic modes",
        "Same-build local projects",
        "`publishLocal`-installed",
        "Published marker/handler modules",
        "publication of MacroParadise itself",
        "`CrossVersion.full`"
      )
    }

    if (path == "sbt-integration/README.md") {
      requireAll(
        "FOUR_SETUP_QUADRANTS_UNDISCOVERABLE",
        "sbt integration guide must retain both accepted producer topologies and their essential wiring surfaces",
        "## Local marker and handler projects",
        "MacroParadiseIntegration.precompiledProjects(",
        ".dependsOn(macroAnnotations % \"provided->compile\")",
        ".enablePlugins(macroparadise.sbt.MacroParadisePrecompiledPlugin)",
        "## Published marker and handler modules",
        "macroParadiseMarkerModules",
        "macroParadiseHandlerModules",
        "% Provided",
        "hidden"
      )
    }

    if (path == "docs/EXTERNAL_HANDLER_AUTHORING.md") {
      requireAll(
        "FOUR_SETUP_QUADRANTS_UNDISCOVERABLE",
        "canonical authoring guide must retain both accepted manual producer topologies and their essential wiring surfaces",
        "## Manual same-build local projects",
        "-Xplugin-require:macroparadise",
        "ExternalArtifactIdentity.combined(",
        "## Manual published marker and handler modules",
        "config(\"macroParadiseHandler\").hide",
        "val direct = resolveConfigured(modules, classpath, \"handler\")",
        "direct ++ transitive"
      )
    }
    findings.result()
  }

  private def scanCrossDocumentAcceptance(
      root: File,
      includedPaths: Set[String]
  ): Vector[Finding] = {
    val findings = Vector.newBuilder[Finding]
    val gettingStartedPath = "docs/GETTING_STARTED.md"
    val readmePath = "README.md"
    val canReadBoth =
      includedPaths.contains(gettingStartedPath) &&
        includedPaths.contains(readmePath) &&
        new File(root, gettingStartedPath).isFile &&
        new File(root, readmePath).isFile
    if (canReadBoth) {
      val gettingStarted =
        new String(Files.readAllBytes(new File(root, gettingStartedPath).toPath), StandardCharsets.UTF_8)
      val readme =
        new String(Files.readAllBytes(new File(root, readmePath).toPath), StandardCharsets.UTF_8)
      val staleClaim =
        gettingStarted.contains("`@gen` marker and `GenHandler`") ||
          gettingStarted.contains("GenHandler")
      val currentReference =
        gettingStarted.contains("[README example](../README.md#a-small-user-authored-example)") &&
          gettingStarted.contains("GenerateGreetingHandler") &&
          gettingStarted.contains("generatedGreeting") &&
          readme.contains("GenerateGreetingHandler") &&
          readme.contains("generatedGreeting")
      if (staleClaim || !currentReference)
        findings += Finding(
          "STALE_GENERATED_EXAMPLE_CROSS_REFERENCE",
          gettingStartedPath,
          "Getting Started must name the current README GenerateGreetingHandler/generatedGreeting example and must not claim README contains @gen/GenHandler"
        )
    }

    val starterPath =
      "examples/external-handler-starter/handler/src/main/scala/starter/handler/GenerateGreetingHandler.scala"
    val starter = new File(root, starterPath)
    if (starter.isFile) {
      val text =
        new String(Files.readAllBytes(starter.toPath), StandardCharsets.UTF_8)
      if (text.contains(".asInstanceOf[Defn.Def]"))
        findings += Finding(
          "CANONICAL_GENERATED_DEFINITION_CAST",
          starterPath,
          "executable generated-definition starter must retain the mechanically qualified cast-free Scalameta quasiquote form"
        )
    }

    findings.result()
  }

  private def scanLinks(
      root: File,
      includedPaths: Set[String],
      sourcePath: String,
      text: String
  ): Vector[Finding] = {
    val rootPath = root.toPath.toAbsolutePath.normalize
    val sourceParent = rootPath.resolve(sourcePath).normalize.getParent
    MarkdownLink.findAllMatchIn(text).toVector.flatMap { matched =>
      val raw = matched.group(1).stripPrefix("<").stripSuffix(">")
      if (raw.startsWith("#") || raw.startsWith("//") || ExternalScheme.pattern.matcher(raw).matches()) Vector.empty
      else {
        val withoutFragment = raw.takeWhile(character => character != '#' && character != '?')
        if (withoutFragment.isEmpty) Vector.empty
        else {
          val resolved = sourceParent.resolve(withoutFragment).normalize
          val relative = if (resolved.startsWith(rootPath)) normalize(rootPath.relativize(resolved).toString) else ""
          if (relative.nonEmpty && includedPaths.contains(relative) && Files.isRegularFile(resolved)) Vector.empty
          else Vector(Finding("BROKEN_RELATIVE_LINK", sourcePath, s"relative link `$raw` is outside the public candidate or missing"))
        }
      }
    }
  }

  private def normalize(path: String): String = path.replace('\\', '/').stripPrefix("./")
}
