import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

object PublicDocumentationPolicySpec {
  val CaseCount = 13
  private val Slash = "/"
  private def path(root: String, rest: String): String = root + Slash + rest
  private def controlRepository(root: String): String = root + "-scala3-" + "control"

  def run(): Unit = {
    assert(PublicDocumentationPolicy.RequiredPaths.contains("docs/QUASIQUOTE_ARCHITECTURE.md"))
    val clean = fixture()
    try {
      val result = PublicDocumentationPolicy.verify(clean, PublicDocumentationPolicy.RequiredPaths)
      assert(result.errors.isEmpty, result.errors.mkString("; "))
      assert(
        result.checkedPaths == PublicDocumentationPolicy.RequiredPaths.toVector.sorted.filter(_.endsWith(".md"))
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
    assertFinding("README.md", "See [missing](docs/MISSING.md).\n", "BROKEN_RELATIVE_LINK")

    val missing = fixture()
    try {
      Files.delete(new File(missing, "SUPPORT.md").toPath)
      val included = PublicDocumentationPolicy.RequiredPaths - "SUPPORT.md"
      val result = PublicDocumentationPolicy.verify(missing, included)
      assert(result.errors.exists(error => error.code == "MISSING_PUBLIC_DOCUMENT" && error.path == "SUPPORT.md"))
    } finally delete(missing)
  }

  private def assertFinding(path: String, content: String, code: String): Unit = {
    val root = fixture()
    try {
      write(root, path, content)
      val result = PublicDocumentationPolicy.verify(root, PublicDocumentationPolicy.RequiredPaths)
      assert(
        result.errors.exists(error => error.path == path && error.code == code),
        result.errors.mkString("; ")
      )
    } finally delete(root)
  }

  private def fixture(): File = {
    val root = Files.createTempDirectory("public-documentation-policy-spec-").toFile
    PublicDocumentationPolicy.RequiredPaths.foreach(path => write(root, path, s"# ${path.replace('/', ' ')}\n"))
    write(
      root,
      "README.md",
      "# Project\n\nSee [roadmap](ROADMAP.md), [getting started](docs/GETTING_STARTED.md), and [website](https://example.com).\n"
    )
    write(root, "ROADMAP.md", "# Roadmap\n\nSee [support](SUPPORT.md#support).\n")
    write(root, "CONTRIBUTING.md", "# Contributing\n\nOrdinary input and review are welcome. See [security](SECURITY.md).\n")
    write(root, "SECURITY.md", "# Security\n\nSee [stability](docs/VERSIONING_AND_STABILITY.md).\n")
    write(root, "SUPPORT.md", "# Support\n\nSee [limitations](docs/SUPPORTED_SCOPE_AND_LIMITATIONS.md).\n")
    root
  }

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
