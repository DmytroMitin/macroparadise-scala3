import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, LinkOption}
import java.security.MessageDigest

object PublicProductBoundary {
  sealed trait PathRole { def id: String; def candidateEligible: Boolean }
  case object BuildRuntime extends PathRole {
    val id = "BUILD_RUNTIME"
    val candidateEligible = true
  }
  case object ProductCode extends PathRole {
    val id = "PRODUCT_CODE"
    val candidateEligible = true
  }
  case object PublicDocumentation extends PathRole {
    val id = "PUBLIC_DOCUMENTATION"
    val candidateEligible = true
  }
  case object ExcludedController extends PathRole {
    val id = "EXCLUDED_CONTROLLER"
    val candidateEligible = false
  }
  case object Unclassified extends PathRole {
    val id = "UNCLASSIFIED"
    val candidateEligible = false
  }

  final case class Finding(code: String, path: String, detail: String) {
    override def toString: String = s"$code:$path:$detail"
  }
  final case class Verification(
      includedPaths: Vector[String],
      manifestSha256: String,
      errors: Vector[Finding]
  )

  private val ProductRoots = Set(
    "examples",
    "experimental-structured-metadata-consumer",
    "legacy-metadata-consumers",
    "legacy-metadata-marker-fixture",
    "legacy-metadata-matrix-consumer",
    "legacy-metadata-producers",
    "macro-suspension-spike",
    "packaged-structured-tasty-consumers",
    "plugin",
    "plugin-api",
    "plugin-api-handler-contract-probe",
    "plugin-api-surface-probe",
    "plugin-test-handlers",
    "plugin-test-markers",
    "plugin-tests",
    "same-module-handler-cycle-spike",
    "same-module-handler-same-file-spike",
    "same-module-handler-spike",
    "scripts"
  )

  private val PublicProjectFiles = Set(
    "project/Apache2LicensePolicy.scala",
    "project/Apache2LicensePolicySpec.scala",
    "project/BuildDependencyCoordinatePolicy.scala",
    "project/BuildDependencyCoordinatePolicySpec.scala",
    "project/ExperimentalHandlerContractArtifact.scala",
    "project/ExperimentalHandlerContractArtifactSpec.scala",
    "project/ExperimentalPluginApiSurface.scala",
    "project/ExperimentalPluginApiSurfaceSpec.scala",
    "project/ExternalHandlerAuthoringStarter.scala",
    "project/ExternalHandlerAuthoringStarterSpec.scala",
    "project/IndependentExternalSbtConsumer.scala",
    "project/IndependentExternalSbtConsumerSpec.scala",
    "project/IndependentPrecompiledHandlerPackagedConsumer.scala",
    "project/IndependentPrecompiledHandlerPackagedConsumerSpec.scala",
    "project/JdkVersionEnforcement.scala",
    "project/JdkVersionEnforcementSpec.scala",
    "project/LegacyMetadataMatrixArtifact.scala",
    "project/PluginApiCleanResolution.scala",
    "project/PluginApiSourceProjectSplitPolicy.scala",
    "project/PublicDocumentationPolicy.scala",
    "project/PublicDocumentationPolicySpec.scala",
    "project/PublicProductBoundary.scala",
    "project/PublicProductBoundarySpec.scala",
    "project/build.properties",
    "project/experimental-plugin-api-surface-baseline.txt"
  )

  private def assembled(parts: String*): String = parts.mkString
  private val Slash = "/"

  private val PrivateDocumentNames = Vector(
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

  private val ForbiddenText = Vector(
    "PRIVATE_HISTORY_PATH" -> assembled("prompts", Slash),
    "PRIVATE_HISTORY_PATH" -> assembled("reviews", Slash),
    "PRIVATE_EXCHANGE_PATH" -> assembled("input", Slash),
    "CONTROL_REPOSITORY" -> assembled("macroparadise-scala3-", "control"),
    "CONTROL_REPOSITORY" -> assembled("quasiquotes-scala3-", "control"),
    "CONTROL_REPOSITORY" -> assembled("auxify-scala3-", "control"),
    "LOCAL_PEER_PATH" -> assembled("..", Slash, "quasiquotes-scala3"),
    "LOCAL_PEER_PATH" -> assembled("..", Slash, "auxify-scala3"),
    "LOCAL_PEER_PATH" -> assembled(Slash, "quasiquotes-scala3", Slash),
    "LOCAL_PEER_PATH" -> assembled(Slash, "auxify-scala3", Slash),
    "PINNED_QUASIQUOTES_PROOF" -> assembled("quasiquotesphase44", "artifact"),
    "PINNED_QUASIQUOTES_PROOF" -> assembled("quasiquotespositionedcontextualmethodfriend", "consumer"),
    "PINNED_QUASIQUOTES_PROOF" -> assembled("quasiquotes-", "backend-"),
    "PINNED_QUASIQUOTES_PROOF" -> assembled("macroparadise.quasiquotesphase44", "artifact")
  )

  private val ScannerDefinitionFiles = Set(
    "project/PublicDocumentationPolicy.scala",
    "project/PublicDocumentationPolicySpec.scala",
    "project/PublicProductBoundary.scala",
    "project/PublicProductBoundarySpec.scala"
  )

  private val ExcludeBegin = "// PUBLIC_PRODUCT_EXCLUDE_BEGIN:"
  private val ExcludeEnd = "// PUBLIC_PRODUCT_EXCLUDE_END:"

  def roleOf(rawPath: String): PathRole = {
    val path = normalize(rawPath)
    val first = path.takeWhile(_ != '/')
    if (
      path == "controller.sbt" || path == assembled("AGENTS", ".md") ||
      path == assembled("bootstrap-", "prompt.md") || path.startsWith(assembled("prompts", Slash)) ||
      path.startsWith(assembled("reviews", Slash)) || path.startsWith(assembled("input", Slash)) ||
      path.startsWith("quasiquotes-backend-consumer/") ||
      path.startsWith("quasiquotes-backend-handler/") ||
      (path.startsWith("docs/") && PrivateDocumentNames.exists(path.endsWith)) ||
      path == "project/PublicProductCandidate.scala" ||
      path == "project/PublicProductCandidateSpec.scala" ||
      path == "project/newer-exact-nightly.properties"
    ) ExcludedController
    else if (PublicDocumentationPolicy.RequiredPaths.contains(path)) PublicDocumentation
    else if (path.startsWith("docs/")) ExcludedController
    else if (
      path == "build.sbt" ||
      path == ".gitignore" ||
      path == ".github/workflows/test.yml" ||
      path == ".public-product-source-manifest.tsv" ||
      PublicProjectFiles.contains(path)
    ) BuildRuntime
    else if (ProductRoots.contains(first) && !path.split('/').contains("target"))
      ProductCode
    else Unclassified
  }

  def selectedFiles(root: File): Vector[(String, File)] =
    regularFiles(root).flatMap { file =>
      val path = relative(root, file)
      if (roleOf(path).candidateEligible) Some(path -> file) else None
    }.sortBy(_._1)

  def selectedContent(root: File): Either[Vector[Finding], Vector[(String, Array[Byte])]] = {
    val rendered = selectedFiles(root).map { case (path, file) =>
      val bytes = Files.readAllBytes(file.toPath)
      if (path == "build.sbt") {
        projectBuild(new String(bytes, StandardCharsets.UTF_8)) match {
          case Right(value) => Right(path -> value.getBytes(StandardCharsets.UTF_8))
          case Left(error) => Left(Finding("INVALID_BUILD_BOUNDARY_MARKERS", path, error))
        }
      } else Right(path -> bytes)
    }
    val errors = rendered.collect { case Left(error) => error }
    if (errors.nonEmpty) Left(errors) else Right(rendered.collect { case Right(value) => value })
  }

  def projectBuild(text: String): Either[String, String] = {
    val lines = text.split("\\n", -1).toVector
    val output = new StringBuilder
    var excluded: Option[String] = None
    var error: Option[String] = None
    lines.zipWithIndex.foreach { case (line, index) =>
      if (error.isEmpty) {
        if (line.startsWith(ExcludeBegin)) {
          val name = line.stripPrefix(ExcludeBegin).trim
          if (name.isEmpty) error = Some(s"empty exclusion name at line ${index + 1}")
          else if (excluded.nonEmpty) error = Some(s"nested exclusion `$name` inside `${excluded.get}` at line ${index + 1}")
          else excluded = Some(name)
        } else if (line.startsWith(ExcludeEnd)) {
          val name = line.stripPrefix(ExcludeEnd).trim
          excluded match {
            case None => error = Some(s"unmatched exclusion end `$name` at line ${index + 1}")
            case Some(open) if open != name => error = Some(s"exclusion end `$name` does not match `$open` at line ${index + 1}")
            case Some(_) => excluded = None
          }
        } else if (excluded.isEmpty) {
          output.append(line)
          if (index < lines.size - 1) output.append('\n')
        }
      }
    }
    error.orElse(excluded.map(name => s"unclosed exclusion `$name`")) match {
      case Some(message) => Left(message)
      case None => Right(output.result())
    }
  }

  def verifySelectedSource(root: File): Verification = {
    selectedContent(root) match {
      case Left(errors) => Verification(Vector.empty, sha256(Array.emptyByteArray), errors)
      case Right(selected) => verifyContent(selected)
    }
  }

  def verifyContent(selected: Vector[(String, Array[Byte])]): Verification = {
    val findings = selected.flatMap { case (path, bytes) => scanBytes(path, bytes) }
    verificationBytes(selected, findings)
  }

  def verify(root: File): Verification = {
    val files = regularFiles(root).map(file => relative(root, file) -> file).sortBy(_._1)
    val pathFindings = files.flatMap { case (path, _) =>
      roleOf(path) match {
        case role if role.candidateEligible => Vector.empty
        case PublicDocumentation =>
          Vector(Finding("NON_BUILD_PROJECTION_PATH", path, "public documentation is outside this build-only projection"))
        case ExcludedController =>
          Vector(Finding("EXCLUDED_PATH_PRESENT", path, "controller or peer-proof path is forbidden in the product projection"))
        case Unclassified =>
          Vector(Finding("UNCLASSIFIED_PATH", path, "path has no explicit public-product ownership"))
      }
    }
    val included = files.filter { case (path, _) => roleOf(path).candidateEligible }
    val contentFindings = included.flatMap { case (path, file) => scan(path, file) }
    verification(included, pathFindings ++ contentFindings)
  }

  def manifest(root: File): String =
    selectedContent(root).fold(
      errors => throw new IllegalArgumentException(errors.mkString("; ")),
      _.map { case (path, bytes) => s"$path\t${sha256(bytes)}" }.mkString("", "\n", "\n")
    )

  private def verification(
      files: Vector[(String, File)],
      findings: Vector[Finding]
  ): Verification = {
    val rendered = files.map { case (path, file) => s"$path\t${sha256(Files.readAllBytes(file.toPath))}" }.mkString("", "\n", "\n")
    Verification(files.map(_._1), sha256(rendered.getBytes(StandardCharsets.UTF_8)), findings.distinct.sortBy(f => (f.path, f.code, f.detail)))
  }

  private def verificationBytes(
      files: Vector[(String, Array[Byte])],
      findings: Vector[Finding]
  ): Verification = {
    val rendered = files.map { case (path, bytes) => s"$path\t${sha256(bytes)}" }.mkString("", "\n", "\n")
    Verification(files.map(_._1), sha256(rendered.getBytes(StandardCharsets.UTF_8)), findings.distinct.sortBy(f => (f.path, f.code, f.detail)))
  }

  private def scan(path: String, file: File): Vector[Finding] = {
    if (ScannerDefinitionFiles.contains(path)) return Vector.empty
    scanBytes(path, Files.readAllBytes(file.toPath))
  }

  private def scanBytes(path: String, bytes: Array[Byte]): Vector[Finding] = {
    if (ScannerDefinitionFiles.contains(path)) return Vector.empty
    if (bytes.contains(0.toByte)) Vector.empty
    else {
      val text = new String(bytes, StandardCharsets.UTF_8)
      val lower = text.toLowerCase(java.util.Locale.ROOT)
      val tokens = ForbiddenText.collect {
        case (code, token) if lower.contains(token) =>
          Finding(code, path, s"contains forbidden dependency token `$token`")
      }
      val privateDocs = PrivateDocumentNames.collect {
        case name if lower.contains(name) => Finding("PRIVATE_CONTROLLER_DOCUMENT", path, s"references private controller document `$name`")
      }
      val absolute = assembled(
        "(?i)(?:^|[\\s\"'=])(?:", Slash, "home", Slash,
        "|", Slash, "users", Slash, "|[a-z]:\\\\users\\\\)"
      ).r.findFirstIn(text).toVector.map { _ =>
        Finding("LOCAL_ABSOLUTE_PATH", path, "contains a producer-local absolute path")
      }
      tokens ++ privateDocs ++ absolute
    }
  }

  private def regularFiles(root: File): Vector[File] = {
    def loop(file: File): Vector[File] = {
      if (Files.isSymbolicLink(file.toPath)) Vector.empty
      else if (file.isFile) Vector(file)
      else if (file.isDirectory && !Set(".git", ".bsp", ".idea", ".codex", ".agents", "target").contains(file.getName))
        Option(file.listFiles()).getOrElse(Array.empty).toVector.flatMap(loop)
      else Vector.empty
    }
    loop(root)
  }

  private def relative(root: File, file: File): String =
    normalize(root.toPath.toAbsolutePath.normalize.relativize(file.toPath.toAbsolutePath.normalize).toString)

  private def normalize(path: String): String = path.replace('\\', '/').stripPrefix("./")

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map("%02x".format(_)).mkString
}
