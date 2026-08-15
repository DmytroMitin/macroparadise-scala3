object Apache2LicensePolicySpec {
  val CaseCount = 5

  private val ValidDigest = "c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4"
  private val ValidBuild =
    """ThisBuild / licenses := List("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0"))
      |ThisBuild / publish / skip := true
      |""".stripMargin
  private val ValidDocuments = Map(
    "README.md" -> "Source code is licensed under the Apache License 2.0. The API remains experimental.",
    "CONTRIBUTING.md" -> "Contributions are submitted under Apache License 2.0. Publishing remains disabled.",
    "ROADMAP.md" -> "The source uses the Apache License 2.0; visibility, API stability, and artifact publication remain separate.",
    "docs/VERSIONING_AND_STABILITY.md" -> "Source uses the Apache License 2.0. The API remains experimental."
  )

  def run(): Unit = {
    val valid = Apache2LicensePolicy.verifyFacts(ValidDigest, ValidBuild, ValidDocuments)
    assert(valid.isEmpty, valid.mkString("; "))

    assertCode(
      Apache2LicensePolicy.verifyFacts("0" * 64, ValidBuild, ValidDocuments),
      "APACHE2_LICENSE_TEXT"
    )
    assertCode(
      Apache2LicensePolicy.verifyFacts(ValidDigest, "ThisBuild / publish / skip := true\n", ValidDocuments),
      "APACHE2_POM_METADATA"
    )
    assertCode(
      Apache2LicensePolicy.verifyFacts(
        ValidDigest,
        ValidBuild,
        ValidDocuments.updated("README.md", "No license has been selected or added.")
      ),
      "APACHE2_PUBLIC_WORDING"
    )
    assertCode(
      Apache2LicensePolicy.verifyFacts(
        ValidDigest,
        ValidBuild,
        ValidDocuments.updated("README.md", "Source code is licensed under the Apache License 2.0.")
      ),
      "EXPERIMENTAL_API_WORDING"
    )
  }

  private def assertCode(findings: Vector[Apache2LicensePolicy.Finding], code: String): Unit =
    assert(findings.exists(_.code == code), findings.mkString("; "))
}
