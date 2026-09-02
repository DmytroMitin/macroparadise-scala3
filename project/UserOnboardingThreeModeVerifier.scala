import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, StandardCopyOption}
import java.security.MessageDigest

import scala.collection.mutable.ArrayBuffer
import scala.sys.process.{Process, ProcessLogger}

object UserOnboardingThreeModeVerifier {
  final case class Config(scalaVersion: String, sbtVersion: String, projectVersion: String)

  final case class Result(
      scalaVersion: String,
      manual: Boolean,
      localProjects: Boolean,
      publishedModules: Boolean,
      brokenImplicitDirectoryNegative: Boolean,
      manualHelperSelfContained: Boolean,
      pluginInstalledFromSource: Boolean,
      overridePreserved: Boolean,
      derivedIdentityProtected: Boolean,
      evidenceDirectory: File
  ) {
    def render: String =
      s"scala=$scalaVersion MANUAL_SETUP=${pass(manual)} " +
        s"SBT_PLUGIN_LOCAL_PROJECTS_NO_PRODUCER_PUBLISHLOCAL=${pass(localProjects)} " +
        s"SBT_PLUGIN_PUBLISHED_MODULES=${pass(publishedModules)} " +
        s"HYPHENATED_PROJECT_DIRECTORY_REPRO_FIXED=${pass(brokenImplicitDirectoryNegative)} " +
        s"MANUAL_IDENTITY_HELPER_SELF_CONTAINED=$manualHelperSelfContained " +
        s"SBT_PLUGIN_SOURCE_INSTALL=$pluginInstalledFromSource " +
        s"EXPLICIT_OVERRIDE_PRESERVED=$overridePreserved DERIVED_IDENTITY_PROTECTED=$derivedIdentityProtected"

    private def pass(value: Boolean): String = if (value) "PASS" else "FAIL"
  }

  def verify(
      repositoryRoot: File,
      pluginApiJar: File,
      pluginJar: File,
      pluginApiPom: File,
      pluginPom: File,
      taskRoot: File,
      config: Config
  ): Result = {
    require(Set("3.3.8", "3.8.4", "3.9.0")(config.scalaVersion), "unsupported exact Scala line")
    require(config.sbtVersion == "1.12.15", "unsupported sbt version")
    require(config.projectVersion == "0.1.1-SNAPSHOT", "unexpected product version")

    sbt.IO.delete(taskRoot)
    val evidence = new File(taskRoot, "evidence")
    val productRepository = new File(taskRoot, "product-repository")
    val producerRepository = new File(taskRoot, "producer-repository")
    sbt.IO.createDirectory(evidence)
    sbt.IO.createDirectory(producerRepository)
    stageProduct(productRepository, pluginApiJar, pluginApiPom, "macroparadise-scala3-plugin-api", config)
    stageProduct(productRepository, pluginJar, pluginPom, "macroparadise-scala3-plugin", config)

    val installLog = new File(evidence, "00-sbt-plugin-source-install.log")
    val integrationDirectory = new File(repositoryRoot, "sbt-integration")
    val pluginInstalledFromSource = run(
      integrationDirectory,
      Vector("sbt", "-batch", "verifyIntegrationPolicy", "publishLocal"),
      installLog
    ) == 0
    require(pluginInstalledFromSource, "source-built sbt plugin installation failed")
    require(
      read(installLog).contains("published sbt-macroparadise to"),
      "source installation log did not record the local sbt-plugin artifact"
    )

    val template = new File(repositoryRoot, "examples/user-onboarding-three-mode-fixture")
    val helperSource = new File(repositoryRoot, "examples/external-handler-starter/project/ExternalArtifactIdentity.scala")

    val manualBuild = prepareMode(template, "manual", new File(taskRoot, "manual"))
    val copiedHelper = new File(manualBuild, "project/ExternalArtifactIdentity.scala")
    Files.copy(helperSource.toPath, copiedHelper.toPath, StandardCopyOption.REPLACE_EXISTING)
    val manualHelperSelfContained = sha256(helperSource) == sha256(copiedHelper)
    require(manualHelperSelfContained, "manual identity helper copy differs from the public helper")
    val manual = runMode(
      manualBuild,
      config,
      productRepository,
      producerRepository,
      Vector("clean", "verifyFixture"),
      new File(evidence, "10-manual.log")
    ) == 0
    require(manual, "exact manual fixture failed")

    val localBuild = prepareMode(template, "local-project", new File(taskRoot, "local-project"))
    val localProjects = runMode(
      localBuild,
      config,
      productRepository,
      producerRepository,
      Vector("clean", "verifyFixture"),
      new File(evidence, "20-local-project.log")
    ) == 0
    require(localProjects, "exact sbt-plugin local-project fixture failed")

    val localOverride = runMode(
      localBuild,
      config,
      productRepository,
      producerRepository,
      Vector("set core / macroParadisePrecheckEnabled := false", "core/compile"),
      new File(evidence, "21-local-project-explicit-override.log")
    ) == 0
    require(localOverride, "documented sbt-plugin setting override failed")
    val localIdentityProtected = runMode(
      localBuild,
      config,
      productRepository,
      producerRepository,
      Vector(
        "set core / macroParadiseExternalArtifactIdentity := \"user-replacement\"",
        "core/macroParadiseValidate"
      ),
      new File(evidence, "22-local-project-derived-identity-negative.log")
    ) != 0
    require(localIdentityProtected, "derived AutoPlugin identity was replaceable")

    val publishedBuild = prepareMode(template, "published-module", new File(taskRoot, "published-module"))
    val publishedModules = runMode(
      publishedBuild,
      config,
      productRepository,
      producerRepository,
      Vector(
        "clean",
        "macroAnnotations/publish",
        "macroHandlers/publish",
        "core/clean",
        "verifyFixture"
      ),
      new File(evidence, "30-published-module.log")
    ) == 0
    require(publishedModules, "exact sbt-plugin published-module fixture failed")
    val publishedOverride = runMode(
      publishedBuild,
      config,
      productRepository,
      producerRepository,
      Vector("set core / macroParadisePrecheckEnabled := false", "core/compile"),
      new File(evidence, "31-published-module-explicit-override.log")
    ) == 0
    require(publishedOverride, "published-module setting override failed")
    val publishedIdentityProtected = runMode(
      publishedBuild,
      config,
      productRepository,
      producerRepository,
      Vector(
        "set core / macroParadiseExternalArtifactIdentity := \"user-replacement\"",
        "core/macroParadiseValidate"
      ),
      new File(evidence, "32-published-module-derived-identity-negative.log")
    ) != 0
    require(publishedIdentityProtected, "published-module derived identity was replaceable")

    val brokenBuild = prepareBrokenImplicitDirectoryBuild(template, new File(taskRoot, "broken-implicit"), config)
    val brokenLog = new File(evidence, "40-implicit-directory-negative.log")
    val brokenExit = runMode(
      brokenBuild,
      config,
      productRepository,
      producerRepository,
      Vector(
        "show macroAnnotations/baseDirectory",
        "show macroHandlers/baseDirectory",
        "show core/baseDirectory",
        "core/compile"
      ),
      brokenLog
    )
    val brokenText = read(brokenLog)
    val brokenImplicitDirectoryNegative =
      brokenExit != 0 &&
        brokenText.contains(new File(brokenBuild, "macroAnnotations").getAbsolutePath) &&
        brokenText.contains(new File(brokenBuild, "macroHandlers").getAbsolutePath) &&
        !brokenText.contains(new File(brokenBuild, "macro-annotations").getAbsolutePath + "\n[info] *")
    require(brokenImplicitDirectoryNegative, "implicit project-directory negative did not reproduce mechanically")

    val result = Result(
      config.scalaVersion,
      manual,
      localProjects,
      publishedModules,
      brokenImplicitDirectoryNegative,
      manualHelperSelfContained,
      pluginInstalledFromSource,
      localOverride && publishedOverride,
      localIdentityProtected && publishedIdentityProtected,
      evidence
    )
    write(new File(evidence, "summary.txt"), result.render + "\n")
    result
  }

  private def prepareMode(template: File, mode: String, destination: File): File = {
    sbt.IO.copyDirectory(new File(template, mode), destination)
    Vector("macro-annotations", "macro-handlers", "core").foreach { name =>
      sbt.IO.copyDirectory(new File(template, name), new File(destination, name))
    }
    destination
  }

  private def prepareBrokenImplicitDirectoryBuild(
      template: File,
      destination: File,
      config: Config
  ): File = {
    Vector("macro-annotations", "macro-handlers", "core").foreach { name =>
      sbt.IO.copyDirectory(new File(template, name), new File(destination, name))
    }
    sbt.IO.createDirectory(new File(destination, "project"))
    write(new File(destination, "project/build.properties"), "sbt.version=" + config.sbtVersion + "\n")
    write(
      new File(destination, "build.sbt"),
      s"""ThisBuild / scalaVersion := sys.props("macroparadise.exactScalaVersion")
         |ThisBuild / resolvers := Seq(
         |  "task-product-repository" at file(sys.props("macroparadise.productRepository")).toURI.toString,
         |  Resolver.mavenCentral
         |)
         |val mpApi =
         |  ("com.github.dmytromitin" % "macroparadise-scala3-plugin-api" % "${config.projectVersion}")
         |    .cross(CrossVersion.full)
         |lazy val macroAnnotations = project.settings(libraryDependencies += mpApi)
         |lazy val macroHandlers = project.settings(libraryDependencies += mpApi)
         |lazy val core = project.dependsOn(macroAnnotations)
         |""".stripMargin
    )
    destination
  }

  private def runMode(
      directory: File,
      config: Config,
      productRepository: File,
      producerRepository: File,
      commands: Vector[String],
      log: File
  ): Int = run(
    directory,
    Vector(
      "sbt",
      "-batch",
      "-Dmacroparadise.exactScalaVersion=" + config.scalaVersion,
      "-Dmacroparadise.productRepository=" + productRepository.getAbsolutePath,
      "-Dmacroparadise.producerRepository=" + producerRepository.getAbsolutePath
    ) ++ commands,
    log
  )

  private def run(directory: File, command: Vector[String], log: File): Int = {
    val lines = ArrayBuffer.empty[String]
    val exit = Process(command, directory).!(ProcessLogger(
      line => lines += line,
      line => lines += line
    ))
    write(log, lines.mkString("", "\n", "\n"))
    exit
  }

  private def stageProduct(
      repository: File,
      jar: File,
      pom: File,
      baseModule: String,
      config: Config
  ): Unit = {
    val module = baseModule + "_" + config.scalaVersion
    val directory = new File(repository, "com/github/dmytromitin/" + module + "/" + config.projectVersion)
    sbt.IO.createDirectory(directory)
    Files.copy(
      jar.toPath,
      new File(directory, module + "-" + config.projectVersion + ".jar").toPath,
      StandardCopyOption.REPLACE_EXISTING
    )
    Files.copy(
      pom.toPath,
      new File(directory, module + "-" + config.projectVersion + ".pom").toPath,
      StandardCopyOption.REPLACE_EXISTING
    )
  }

  private def sha256(file: File): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    val input = Files.newInputStream(file.toPath)
    try {
      val buffer = new Array[Byte](8192)
      var count = input.read(buffer)
      while (count >= 0) {
        if (count > 0) digest.update(buffer, 0, count)
        count = input.read(buffer)
      }
    } finally input.close()
    digest.digest().map(value => f"${value & 0xff}%02x").mkString
  }

  private def read(file: File): String =
    new String(Files.readAllBytes(file.toPath), StandardCharsets.UTF_8)

  private def write(file: File, value: String): Unit = {
    Option(file.getParentFile).foreach(sbt.IO.createDirectory)
    Files.write(file.toPath, value.getBytes(StandardCharsets.UTF_8))
  }
}
