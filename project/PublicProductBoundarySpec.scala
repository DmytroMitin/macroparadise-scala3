import java.io.File
import java.nio.file.Files

object PublicProductBoundarySpec {
  val CaseCount = 18
  private val Slash = "/"
  private def path(root: String, rest: String): String = root + Slash + rest
  private def controlRepository(root: String): String = root + "-scala3-" + "control"

  def run(): Unit = {
    val root = Files.createTempDirectory("public-product-boundary-spec-").toFile
    try {
      write(root, "build.sbt", "ThisBuild / publish / skip := true\n")
      write(root, "project/build.properties", "sbt.version=1.12.6\n")
      write(root, "plugin/src/main/scala/example/Product.scala", "package example\n")

      val clean = PublicProductBoundary.verify(root)
      assert(clean.errors.isEmpty, clean.errors.mkString("; "))
      assert(clean.includedPaths == Vector(
        "build.sbt",
        "plugin/src/main/scala/example/Product.scala",
        "project/build.properties"
      ))
      assert(clean.manifestSha256.matches("[0-9a-f]{64}"))

      assert(PublicProductBoundary.roleOf("build.sbt").id == "BUILD_RUNTIME")
      assert(PublicProductBoundary.roleOf(".gitignore").id == "BUILD_RUNTIME")
      assert(PublicProductBoundary.roleOf("plugin/src/test/scala/ProductSpec.scala").id == "PRODUCT_CODE")
      assert(PublicProductBoundary.roleOf("README.md").id == "PUBLIC_DOCUMENTATION")
      assert(PublicProductBoundary.roleOf("README.md").candidateEligible)
      assert(PublicProductBoundary.roleOf("CONTRIBUTING.md").candidateEligible)
      assert(PublicProductBoundary.roleOf("docs/GETTING_STARTED.md").candidateEligible)
      assert(PublicProductBoundary.roleOf("docs/private-research-note.md").id == "EXCLUDED_CONTROLLER")
      assert(PublicProductBoundary.roleOf(path("prompts", "108.md")).id == "EXCLUDED_CONTROLLER")
      assert(PublicProductBoundary.roleOf("unknown/private.txt").id == "UNCLASSIFIED")

      val mixedBuild =
        "kept-a\n" +
          "// PUBLIC_PRODUCT_EXCLUDE_BEGIN:private_lane\n" +
          path("reviews", "private.md") + "\n" +
          "// PUBLIC_PRODUCT_EXCLUDE_END:private_lane\n" +
          "kept-b\n"
      assert(PublicProductBoundary.projectBuild(mixedBuild) == Right("kept-a\nkept-b\n"))
      assert(PublicProductBoundary.projectBuild(
        "// PUBLIC_PRODUCT_EXCLUDE_BEGIN:a\n// PUBLIC_PRODUCT_EXCLUDE_END:b\n"
      ).isLeft)
      assert(PublicProductBoundary.projectBuild(
        "// PUBLIC_PRODUCT_EXCLUDE_BEGIN:a\n"
      ).isLeft)

      val forbidden = Vector(
        ("build.sbt", path("reviews", "private.md"), "PRIVATE_HISTORY_PATH"),
        ("plugin/src/main/scala/example/Product.scala", path("input", "20_peer.md"), "PRIVATE_EXCHANGE_PATH"),
        ("plugin/src/main/scala/example/Product.scala", ".." + Slash + "quasiquotes-scala3/target/local.jar", "LOCAL_PEER_PATH"),
        ("plugin/src/test/scala/example/ProductSpec.scala", Slash + "home/alice/private.jar", "LOCAL_ABSOLUTE_PATH"),
        (".github/workflows/test.yml", controlRepository("macroparadise"), "CONTROL_REPOSITORY"),
        ("plugin/src/main/scala/example/Product.scala", "QuasiquotesPhase44Artifact", "PINNED_QUASIQUOTES_PROOF")
      )
      forbidden.zipWithIndex.foreach { case ((path, value, expectedCode), index) =>
        val fixture = Files.createTempDirectory(s"public-product-boundary-forbidden-$index-").toFile
        try {
          write(fixture, "build.sbt", "ThisBuild / publish / skip := true\n")
          write(fixture, "project/build.properties", "sbt.version=1.12.6\n")
          write(fixture, path, value + "\n")
          val result = PublicProductBoundary.verify(fixture)
          assert(
            result.errors.exists(finding => finding.path == path && finding.code == expectedCode),
            result.errors.mkString("; ")
          )
        } finally delete(fixture)
      }

      write(root, "unknown/private.txt", "not classified\n")
      val unclassified = PublicProductBoundary.verify(root)
      assert(unclassified.errors.exists(_.code == "UNCLASSIFIED_PATH"))
    } finally delete(root)
  }

  private def write(root: File, relative: String, content: String): Unit = {
    val file = new File(root, relative)
    Option(file.getParentFile).foreach(_.mkdirs())
    Files.write(file.toPath, content.getBytes("UTF-8"))
  }

  private def delete(file: File): Unit = {
    if (file.isDirectory) Option(file.listFiles()).getOrElse(Array.empty).foreach(delete)
    Files.deleteIfExists(file.toPath)
  }
}
