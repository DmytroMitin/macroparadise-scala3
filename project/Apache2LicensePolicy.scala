import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest

object Apache2LicensePolicy {
  final case class Finding(code: String, path: String, detail: String) {
    override def toString: String = s"$code:$path:$detail"
  }

  final case class Verification(licenseSha256: String, errors: Vector[Finding])

  val ExpectedLicenseSha256 = "c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4"

  private val Metadata =
    "ThisBuild / licenses := List(\"Apache-2.0\" -> url(\"https://www.apache.org/licenses/LICENSE-2.0\"))"
  private val RequiredDocuments = Vector(
    "README.md",
    "CONTRIBUTING.md",
    "ROADMAP.md",
    "docs/VERSIONING_AND_STABILITY.md"
  )
  private val ExperimentalDocuments = Set("README.md", "docs/VERSIONING_AND_STABILITY.md")
  private val StaleLicenseClaims = Vector(
    "no license has been selected",
    "a repository license has not yet been selected",
    "select and add an explicit license",
    "selecting a repository license"
  )

  def verify(root: File): Verification = {
    val license = new File(root, "LICENSE")
    val digest = if (license.isFile) sha256(Files.readAllBytes(license.toPath)) else "<missing>"
    val build = read(new File(root, "build.sbt"))
    val documents = RequiredDocuments.map(path => path -> read(new File(root, path))).toMap
    Verification(digest, verifyFacts(digest, build, documents))
  }

  def verifyFacts(
      licenseSha256: String,
      buildText: String,
      documents: Map[String, String]
  ): Vector[Finding] = {
    val findings = Vector.newBuilder[Finding]
    if (licenseSha256 != ExpectedLicenseSha256)
      findings += Finding(
        "APACHE2_LICENSE_TEXT",
        "LICENSE",
        s"expectedSha256=$ExpectedLicenseSha256 actual=$licenseSha256"
      )
    if (!buildText.contains(Metadata))
      findings += Finding("APACHE2_POM_METADATA", "build.sbt", "exact Apache-2.0 license metadata is absent")
    RequiredDocuments.foreach { path =>
      val lower = documents.getOrElse(path, "").toLowerCase(java.util.Locale.ROOT)
      if (!lower.contains("apache") || !lower.contains("license 2.0"))
        findings += Finding("APACHE2_PUBLIC_WORDING", path, "Apache License 2.0 source terms are not explicit")
      if (StaleLicenseClaims.exists(lower.contains))
        findings += Finding("APACHE2_PUBLIC_WORDING", path, "stale no-license wording remains")
      if (ExperimentalDocuments.contains(path) && !lower.contains("experimental"))
        findings += Finding("EXPERIMENTAL_API_WORDING", path, "experimental API status is not explicit")
    }
    findings.result().distinct.sortBy(finding => (finding.path, finding.code, finding.detail))
  }

  private def read(file: File): String =
    if (file.isFile) new String(Files.readAllBytes(file.toPath), StandardCharsets.UTF_8) else ""

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(value => f"${value & 0xff}%02x").mkString
}
