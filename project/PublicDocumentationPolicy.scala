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
    "docs/SUPPORTED_SCOPE_AND_LIMITATIONS.md",
    "docs/EXTERNAL_HANDLER_AUTHORING.md",
    "docs/DIAGNOSTICS.md",
    "docs/COMPATIBILITY.md",
    "docs/VERSIONING_AND_STABILITY.md"
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
    Verification(checked, (missing ++ findings).distinct.sortBy(error => (error.path, error.code, error.detail)))
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
    PrivateControllerDocuments.toVector.sorted.foreach { name =>
      if (lower.contains(name))
        findings += Finding("PRIVATE_CONTROLLER_DOCUMENT", path, s"references private controller document `$name`")
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
