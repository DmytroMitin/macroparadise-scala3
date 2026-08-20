import java.io.{File, InputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{FileVisitResult, Files, Path, SimpleFileVisitor, StandardCopyOption}
import java.security.MessageDigest
import java.util.jar.JarFile

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer
import scala.sys.process.{Process, ProcessLogger}
import scala.util.control.NonFatal

object PluginApiCleanResolution {
  val ProvenClassification = "PLUGIN_API_CLEAN_RESOLUTION_AND_PACKAGE_PROVEN"
  val BlockedClassification =
    "PLUGIN_API_CLEAN_RESOLUTION_PROOF_BLOCKED_BY_ENVIRONMENT"
  val FailedClassification = "PLUGIN_API_CLEAN_RESOLUTION_PROOF_FAILED"

  final case class StepResult(id: String, exitCode: Int, logFile: File) {
    def render: String = s"$id=$exitCode"
  }

  final case class Result(
      classification: String,
      steps: List[StepResult],
      scalaVersion: String,
      compilerCoordinate: String,
      artifactPath: String,
      artifactBytes: Long,
      artifactEntries: Int,
      artifactSha256: String,
      freshCacheFiles: Long,
      freshCacheBytes: Long,
      negativePolicyPassed: Boolean,
      disposableRepositoryDeleted: Boolean,
      taskOwnedCacheDeleted: Boolean,
      failure: Option[String],
      evidenceDirectory: File
  ) {
    def isProven: Boolean = classification == ProvenClassification
    def isBlocked: Boolean = classification == BlockedClassification

    def render: String =
      s"classification=$classification steps=${steps.map(_.render).mkString(",")} " +
        s"scalaVersion=$scalaVersion compiler=$compilerCoordinate " +
        s"artifact=$artifactPath artifactBytes=$artifactBytes " +
        s"artifactEntries=$artifactEntries artifactSha256=$artifactSha256 " +
        s"freshCacheFiles=$freshCacheFiles freshCacheBytes=$freshCacheBytes " +
        s"negativePolicyPassed=$negativePolicyPassed " +
        s"disposableRepositoryDeleted=$disposableRepositoryDeleted " +
        s"taskOwnedCacheDeleted=$taskOwnedCacheDeleted" +
        failure.map(value => s" failure=$value").getOrElse("")
  }

  def run(
      repositoryRoot: File,
      evidenceDirectory: File,
      pluginApiDependencies: Seq[BuildDependencyCoordinatePolicy.Dependency],
      allBuildDependencies: Seq[BuildDependencyCoordinatePolicy.Dependency],
      buildShape: BuildDependencyCoordinatePolicy.BuildShape
  ): Result = {
    recreateDirectory(evidenceDirectory.toPath)
    val disposableParent =
      Files.createTempDirectory("macroparadise-plugin-api-clean-")
    val disposableRoot = disposableParent.resolve("repository")
    val taskCacheRoot = disposableParent.resolve("dependency-cache")
    val coursierCache = taskCacheRoot.resolve("coursier")
    val ivyCache = taskCacheRoot.resolve("ivy")
    val globalBase = taskCacheRoot.resolve("sbt-global")
    Files.createDirectories(coursierCache)
    Files.createDirectories(ivyCache)
    Files.createDirectories(globalBase)
    copyRepository(repositoryRoot.toPath, disposableRoot)

    val stepResults = ListBuffer.empty[StepResult]
    var outcome: Result = null
    var negativePolicyPassed = false

    try {
      val badPluginDependencies = pluginApiDependencies.map { dependency =>
        if (dependency.artifactBase == "scala3-compiler")
          dependency.copy(organization = "orgP069.scala-lang")
        else dependency
      }
      val badAllDependencies = allBuildDependencies ++ badPluginDependencies
      val badResult = BuildDependencyCoordinatePolicy.verify(
        badPluginDependencies,
        badAllDependencies,
        buildShape
      )
      negativePolicyPassed =
        badResult.errors.exists(_.contains("organization must be org.scala-lang")) &&
          badResult.errors.exists(_.contains("prompt-number contamination")) &&
          packagedJars(disposableRoot, buildShape.scalaVersion).isEmpty
      if (!negativePolicyPassed)
        throw new IllegalStateException(
          s"resolver-free bad-coordinate probe did not fail safely: ${badResult.errors.mkString("; ")}"
        )
      write(
        evidenceDirectory.toPath.resolve("negative-coordinate-policy.log"),
        (List(
          "replacement=orgP069.scala-lang",
          "packageArtifactsBeforePositive=0",
          "result=REJECTED_BEFORE_RESOLUTION"
        ) ++ badResult.errors.map(error => s"error=$error")).mkString("", "\n", "\n")
      )

      val steps = List(
        "settings" -> List(
          "show pluginApi / scalaVersion",
          "show pluginApi / libraryDependencies",
          "show pluginTestMarkers / scalaVersion",
          "show pluginTestMarkers / libraryDependencies"
        ),
        "clean" -> List("clean"),
        "plugin-api-update" -> List("pluginApi / update"),
        "plugin-api-compile" -> List("pluginApi / compile"),
        "plugin-api-package" -> List("pluginApi / packageBin"),
        "marker-update" -> List("pluginTestMarkers / update"),
        "marker-compile" -> List("pluginTestMarkers / compile"),
        "marker-package" -> List("pluginTestMarkers / packageBin"),
        "handlers-package" -> List("pluginTestHandlers / packageBin"),
        "plugin-package" -> List("plugin / packageBin"),
        "plugin-tests-clean" -> List("pluginTests / clean"),
        "plugin-tests-test" -> List("pluginTests / test")
      )

      var continue = true
      steps.foreach {
        case (id, arguments) if continue =>
          val logFile = new File(evidenceDirectory, s"$id.log")
          val result = runSbt(
            disposableRoot.toFile,
            taskCacheRoot,
            coursierCache,
            ivyCache,
            globalBase,
            arguments,
            logFile
          )
          stepResults += StepResult(id, result, logFile)
          if (result != 0) continue = false
        case _ => ()
      }

      val failedStep = stepResults.find(_.exitCode != 0)
      failedStep match {
        case Some(step) =>
          val log = read(step.logFile.toPath)
          val blocked = step.id.endsWith("update") && looksLikeResolutionEnvironmentFailure(log)
          outcome = emptyResult(
            if (blocked) BlockedClassification else FailedClassification,
            stepResults.toList,
            negativePolicyPassed,
            Some(s"${step.id} exit ${step.exitCode}"),
            evidenceDirectory
          )
        case None =>
          val expectedVersion = BuildDependencyCoordinatePolicy.ExpectedScalaVersion
          val expectedCoordinate =
            s"org.scala-lang:scala3-compiler_3:$expectedVersion"
          val settingsLog = read(new File(evidenceDirectory, "settings.log").toPath)
          require(settingsLog.contains(expectedVersion), "child settings did not report the pinned Scala version")
          require(
            settingsLog.contains(s"org.scala-lang:scala3-compiler:$expectedVersion"),
            "child settings did not report the corrected direct compiler coordinate"
          )
          require(!settingsLog.contains("orgP"), "child settings retained a contaminated organization")

          val reports = updateReports(disposableRoot)
          require(reports.nonEmpty, "clean update produced no update report")
          val reportText = reports.map(read).mkString("\n")
          val exactReportIdentity =
            s""""organization":"org.scala-lang","name":"scala3-compiler_3","revision":"$expectedVersion""""
          require(
            reportText.contains(exactReportIdentity),
            s"clean update report did not contain $expectedCoordinate"
          )
          require(!reportText.contains("orgP"), "clean update report retained a contaminated organization")

          val compilerJarName = s"scala3-compiler_3-$expectedVersion.jar"
          val freshCompilerJars =
            regularFiles(coursierCache).filter(_.getFileName.toString == compilerJarName)
          require(
            freshCompilerJars.nonEmpty,
            s"exact compiler artifact was not acquired through fresh Coursier cache $coursierCache"
          )
          val canonicalFreshCache = coursierCache.toFile.getCanonicalPath + File.separator
          require(
            freshCompilerJars.forall(_.toFile.getCanonicalPath.startsWith(canonicalFreshCache)),
            "exact compiler artifact escaped the task-owned Coursier cache"
          )

          val artifacts = packagedJars(disposableRoot, expectedVersion)
          require(artifacts.size == 1, s"expected one pluginApi package, found ${artifacts.mkString(", ")}")
          val artifact = artifacts.head
          val entries = jarEntries(artifact.toFile)
          val requiredEntries = Set(
            "paradise3/api/ParadiseAnnotationExpander.class",
            "paradise3/api/ExpansionInput.class",
            "paradise3/api/ExpansionOutcome.class",
            "paradise3/api/expander.class"
          )
          require(
            requiredEntries.subsetOf(entries.toSet),
            s"clean package is missing expected API entries: ${(requiredEntries -- entries).toList.sorted.mkString(", ")}"
          )
          val forbiddenPrefixes = Vector(
            "macroparadise/",
            "demo/",
            "quasiquotes/",
            "dotty/",
            "scala/",
            "scala3/",
            "tasty/"
          )
          val forbidden = entries.filter(entry => forbiddenPrefixes.exists(entry.startsWith))
          require(forbidden.isEmpty, s"clean package contains forbidden implementation/dependency entries: ${forbidden.mkString(", ")}")

          val markerArtifacts = packagedMarkerJars(disposableRoot)
          require(
            markerArtifacts.size == 1,
            s"expected one pluginTestMarkers package, found ${markerArtifacts.mkString(", ")}"
          )
          val markerEntries = jarEntries(markerArtifacts.head.toFile)
          require(
            markerEntries.count(_.endsWith(".class")) == 39,
            s"clean marker package must contain 39 fixture/support classes, found ${markerEntries.count(_.endsWith(".class"))}"
          )
          require(
            !markerEntries.exists(_.startsWith("paradise3/api/")),
            "clean marker package copied plugin API entries"
          )

          val cacheStats = treeStats(taskCacheRoot)
          outcome = Result(
            ProvenClassification,
            stepResults.toList,
            expectedVersion,
            expectedCoordinate,
            disposableRoot.relativize(artifact).toString,
            Files.size(artifact),
            entries.size,
            sha256(artifact),
            cacheStats._1,
            cacheStats._2,
            negativePolicyPassed,
            disposableRepositoryDeleted = false,
            taskOwnedCacheDeleted = false,
            None,
            evidenceDirectory
          )
      }
    } catch {
      case NonFatal(error) =>
        outcome = emptyResult(
          FailedClassification,
          stepResults.toList,
          negativePolicyPassed,
          Some(s"${error.getClass.getSimpleName}: ${Option(error.getMessage).getOrElse("")}"),
          evidenceDirectory
        )
    } finally {
      deleteRecursively(disposableParent)
    }

    val result = outcome.copy(
      disposableRepositoryDeleted = !Files.exists(disposableRoot),
      taskOwnedCacheDeleted = !Files.exists(taskCacheRoot)
    )
    write(
      evidenceDirectory.toPath.resolve("summary.txt"),
      result.render + "\n"
    )
    result
  }

  private def emptyResult(
      classification: String,
      steps: List[StepResult],
      negativePolicyPassed: Boolean,
      failure: Option[String],
      evidenceDirectory: File
  ): Result =
    Result(
      classification,
      steps,
      BuildDependencyCoordinatePolicy.ExpectedScalaVersion,
      s"org.scala-lang:scala3-compiler_3:${BuildDependencyCoordinatePolicy.ExpectedScalaVersion}",
      "unavailable",
      0L,
      0,
      "unavailable",
      0L,
      0L,
      negativePolicyPassed,
      disposableRepositoryDeleted = false,
      taskOwnedCacheDeleted = false,
      failure,
      evidenceDirectory
    )

  private def runSbt(
      repositoryRoot: File,
      taskCacheRoot: Path,
      coursierCache: Path,
      ivyCache: Path,
      globalBase: Path,
      arguments: List[String],
      logFile: File
  ): Int = {
    val command =
      "sbt" ::
        s"-Dsbt.ivy.home=${ivyCache.toAbsolutePath}" ::
        s"-Dsbt.global.base=${globalBase.toAbsolutePath}" ::
        "-batch" ::
        arguments
    val writer = Files.newBufferedWriter(logFile.toPath, StandardCharsets.UTF_8)
    try {
      writer.write("COMMAND\n")
      command.foreach { argument =>
        writer.write(argument)
        writer.newLine()
      }
      writer.write("ENVIRONMENT\n")
      writer.write(s"COURSIER_CACHE=${coursierCache.toAbsolutePath}\n")
      writer.write(s"TASK_CACHE_ROOT=${taskCacheRoot.toAbsolutePath}\n")
      writer.write("OUTPUT\n")
      writer.flush()
      Process(
        command,
        repositoryRoot,
        "COURSIER_CACHE" -> coursierCache.toAbsolutePath.toString,
        "IVY_HOME" -> ivyCache.toAbsolutePath.toString,
        "SBT_OPTS" -> "",
        "JAVA_TOOL_OPTIONS" -> "",
        "_JAVA_OPTIONS" -> ""
      ).!(
        ProcessLogger(
          line => writeLine(writer, line),
          line => writeLine(writer, line)
        )
      )
    } catch {
      case NonFatal(error) =>
        writeLine(
          writer,
          s"${error.getClass.getName}: ${Option(error.getMessage).getOrElse("")}"
        )
        127
    } finally writer.close()
  }

  private def copyRepository(sourceRoot: Path, destinationRoot: Path): Unit = {
    val excludedDirectories = Set(
      ".git",
      "target",
      ".bsp",
      ".idea",
      ".metals",
      ".scala-build",
      ".bloop",
      ".cache",
      ".codex",
      ".agents"
    )
    Files.walkFileTree(
      sourceRoot,
      new SimpleFileVisitor[Path] {
        override def preVisitDirectory(
            directory: Path,
            attributes: BasicFileAttributes
        ): FileVisitResult = {
          val relative = sourceRoot.relativize(directory)
          if (
            relative.getNameCount > 0 &&
            excludedDirectories.contains(directory.getFileName.toString)
          ) FileVisitResult.SKIP_SUBTREE
          else {
            Files.createDirectories(destinationRoot.resolve(relative))
            FileVisitResult.CONTINUE
          }
        }

        override def visitFile(
            path: Path,
            attributes: BasicFileAttributes
        ): FileVisitResult = {
          val relative = sourceRoot.relativize(path)
          Files.copy(
            path,
            destinationRoot.resolve(relative),
            StandardCopyOption.COPY_ATTRIBUTES,
            StandardCopyOption.REPLACE_EXISTING
          )
          FileVisitResult.CONTINUE
        }
      }
    )
  }

  private def packagedJars(repositoryRoot: Path, compilerVersion: String): List[Path] =
    regularFiles(repositoryRoot.resolve("plugin-api").resolve("target"))
      .filter(
        _.getFileName.toString ==
          s"macroparadise-scala3-plugin-api_$compilerVersion-0.1.0.jar"
      )

  private def packagedMarkerJars(repositoryRoot: Path): List[Path] =
    regularFiles(repositoryRoot.resolve("plugin-test-markers").resolve("target"))
      .filter(
        _.getFileName.toString ==
          "macroparadise-scala3-plugin-test-markers_3-0.1.0.jar"
      )

  private def updateReports(repositoryRoot: Path): List[Path] =
    regularFiles(repositoryRoot.resolve("plugin-api").resolve("target"))
      .filter(path => path.getFileName.toString == "output")
      .filter(path => path.toString.contains("update_cache_3"))

  private def regularFiles(root: Path): List[Path] =
    if (!Files.exists(root)) Nil
    else {
      val stream = Files.walk(root)
      try stream.iterator().asScala.filter(Files.isRegularFile(_)).toList
      finally stream.close()
    }

  private def jarEntries(file: File): Vector[String] = {
    val jar = new JarFile(file)
    try jar.entries().asScala.map(_.getName).toVector.sorted
    finally jar.close()
  }

  private def treeStats(root: Path): (Long, Long) = {
    val files = regularFiles(root)
    files.size.toLong -> files.map(Files.size).sum
  }

  private def looksLikeResolutionEnvironmentFailure(log: String): Boolean = {
    val lower = log.toLowerCase
    List(
      "unknownhostexception",
      "connection refused",
      "connection reset",
      "network is unreachable",
      "temporary failure in name resolution",
      "error downloading",
      "download error",
      "operation timed out",
      "read timed out"
    ).exists(lower.contains)
  }

  private def writeLine(writer: java.io.BufferedWriter, line: String): Unit =
    writer.synchronized {
      writer.write(line)
      writer.newLine()
      writer.flush()
    }

  private def recreateDirectory(path: Path): Unit = {
    deleteRecursively(path)
    Files.createDirectories(path)
  }

  private def deleteRecursively(path: Path): Unit =
    if (Files.exists(path))
      Files.walkFileTree(
        path,
        new SimpleFileVisitor[Path] {
          override def visitFile(
              file: Path,
              attributes: BasicFileAttributes
          ): FileVisitResult = {
            Files.deleteIfExists(file)
            FileVisitResult.CONTINUE
          }

          override def postVisitDirectory(
              directory: Path,
              error: java.io.IOException
          ): FileVisitResult = {
            if (error != null) throw error
            Files.deleteIfExists(directory)
            FileVisitResult.CONTINUE
          }
        }
      )

  private def sha256(path: Path): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    val input: InputStream = Files.newInputStream(path)
    val buffer = new Array[Byte](8192)
    try {
      var count = input.read(buffer)
      while (count >= 0) {
        if (count > 0) digest.update(buffer, 0, count)
        count = input.read(buffer)
      }
    } finally input.close()
    digest.digest().map(byte => f"${byte & 0xff}%02x").mkString
  }

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def write(path: Path, text: String): Unit = {
    Files.createDirectories(path.getParent)
    Files.write(path, text.getBytes(StandardCharsets.UTF_8))
  }
}
