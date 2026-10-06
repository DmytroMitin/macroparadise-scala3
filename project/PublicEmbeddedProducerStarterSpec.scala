import java.io.File
import java.nio.file.Files

object PublicEmbeddedProducerStarterSpec {
  val CaseCount = 8

  def run(): Unit = {
    val clean = fixture()
    try {
      val findings = PublicEmbeddedProducerStarter.validateLayout(clean)
      assert(findings.isEmpty, findings.mkString("; "))
    } finally delete(clean)

    assertMissing("project/plugins.sbt", "MISSING_STARTER_FILE")
    assertReplacement(
      "project/plugins.sbt",
      "\"0.2.0-SNAPSHOT\"",
      "\"0.1.1\"",
      "STARTER_PLUGIN_VERSION"
    )
    assertReplacement(
      "build.sbt",
      "\"provided->macroParadiseEmbeddedMarker\"",
      "\"compile\"",
      "STARTER_STATIC_MARKER_EDGE"
    )
    assertReplacement(
      "build.sbt",
      "Runtime / fullClasspath",
      "Compile / fullClasspath",
      "STARTER_RUNTIME_ISOLATION"
    )
    assertReplacement(
      "embedded-producer/src/main/scala/starter/embedded/EmbeddedAnnotations.scala",
      "AnnotationApplication.fromInput(input)",
      "input.currentAnnotation",
      "STARTER_ARGUMENT_DECODING"
    )
    assertReplacement(
      "core/src/main/scala/starter/core/Main.scala",
      "prefix = \"Welcome\"",
      "\"Welcome\"",
      "STARTER_NAMED_ARGUMENT"
    )
    assertReplacement(
      "README.md",
      "no producer `publishLocal`",
      "producer publication",
      "STARTER_NO_PUBLISHLOCAL"
    )
  }

  private def assertMissing(relative: String, code: String): Unit = {
    val root = fixture()
    try {
      Files.delete(new File(root, relative).toPath)
      val findings = PublicEmbeddedProducerStarter.validateLayout(root)
      assert(findings.exists(_.code == code), findings.mkString("; "))
    } finally delete(root)
  }

  private def assertReplacement(
      relative: String,
      before: String,
      after: String,
      code: String
  ): Unit = {
    val root = fixture()
    try {
      val file = new File(root, relative)
      val original = new String(Files.readAllBytes(file.toPath), "UTF-8")
      assert(original.contains(before), s"fixture token missing: $before")
      Files.write(file.toPath, original.replace(before, after).getBytes("UTF-8"))
      val findings = PublicEmbeddedProducerStarter.validateLayout(root)
      assert(findings.exists(_.code == code), findings.mkString("; "))
    } finally delete(root)
  }

  private def fixture(): File = {
    val root = Files.createTempDirectory("public-embedded-producer-starter-spec-").toFile
    write(
      root,
      "project/plugins.sbt",
      """addSbtPlugin("com.github.dmytromitin" % "sbt-macroparadise" % "0.2.0-SNAPSHOT")\n"""
    )
    write(root, "project/build.properties", "sbt.version=1.12.15\n")
    write(
      root,
      "build.sbt",
      """lazy val embeddedProducer = project
        |  .enablePlugins(MacroParadiseEmbeddedProducerPlugin)
        |lazy val core = project
        |  .dependsOn(embeddedProducer % "provided->macroParadiseEmbeddedMarker")
        |  .enablePlugins(MacroParadisePrecompiledPlugin)
        |  .settings(
        |    MacroParadiseIntegration.precompiledEmbeddedProject(embeddedProducer),
        |    verifyEmbeddedStarter := {
        |      val runtimeClasspath = (Runtime / fullClasspath).value.files
        |      require(true, "derived handler is not first in its closure")
        |      require(true, "handler implementation leaked onto runtime")
        |      require(!runtimeClasspath.exists(_.getName.contains("scala3-compiler")), "Scala compiler leaked onto runtime")
        |      require(!runtimeClasspath.exists(_.getName.contains("embedded-producer-plugin")), "producer compiler plugin leaked onto runtime")
        |    }
        |  )
        |""".stripMargin
    )
    write(
      root,
      "embedded-producer/src/main/scala/starter/embedded/EmbeddedAnnotations.scala",
      """@embeddedExpander
        |final class identityEmbedded extends StaticAnnotation
        |object identityEmbedded:
        |  def transform(input: ExpansionInput)(using Context): ExpansionOutcome =
        |    ExpansionEdit.finish(ExpansionEdit.start(input))
        |@embeddedExpander
        |final class addGreeting(prefix: String) extends StaticAnnotation
        |object addGreeting:
        |  def transform(input: ExpansionInput)(using Context): ExpansionOutcome =
        |    AnnotationApplication.fromInput(input)
        |    requireSingleStringLiteralArgument("prefix")
        |    ExpansionTransforms.placeMemberInPrimary(greetingMethod("Hello"))
        |""".stripMargin
    )
    write(
      root,
      "core/src/main/scala/starter/core/Main.scala",
      """@identityEmbedded
        |final class IdentityUser
        |@addGreeting("Hello")
        |final class PositionalGreeter
        |@addGreeting(prefix = "Welcome")
        |final class NamedGreeter
        |println("PUBLIC_EMBEDDED_STARTER_PASS")
        |""".stripMargin
    )
    write(
      root,
      "README.md",
      """# Embedded producer starter
        |
        |This source-built `0.2.0-SNAPSHOT` example uses a precompiled producer.
        |The preferred same-build route requires no producer `publishLocal`.
        |The integration derives marker and handler roles.
        |The generated adapter name is not a public contract.
        |The same-module use is unsupported.
        |External handler authoring remains supported.
        |""".stripMargin
    )
    root
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
