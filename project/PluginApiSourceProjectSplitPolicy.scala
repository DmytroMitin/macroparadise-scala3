import java.io.File
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.jar.JarFile

import scala.collection.JavaConverters._

object PluginApiSourceProjectSplitPolicy {
  val AdoptedClassification =
    "EXPERIMENTAL_PLUGIN_API_CONTRACT_PROJECT_SPLIT_ADOPTED"
  val IsolatedClassification =
    "REPOSITORY_TEST_MARKERS_ISOLATED_FROM_PLUGIN_API_ARTIFACT"

  final case class Result(
      contractSources: Int,
      markerSources: Int,
      contractClasses: Int,
      markerClasses: Int,
      contractTasty: Int,
      markerTasty: Int,
      metadataMarkers: Int,
      initializationSuppressed: Boolean,
      typedMarkerGenericShapePreserved: Boolean,
      contractSha256: String,
      markerSha256: String
  ) {
    def render: String =
      s"classification=$AdoptedClassification isolation=$IsolatedClassification " +
        s"contractSources=$contractSources markerSources=$markerSources " +
        s"contractClasses=$contractClasses markerClasses=$markerClasses " +
        s"contractTasty=$contractTasty markerTasty=$markerTasty " +
        s"metadataMarkers=$metadataMarkers initializationSuppressed=$initializationSuppressed " +
        s"typedMarkerGenericShapePreserved=$typedMarkerGenericShapePreserved " +
        s"contractSha256=$contractSha256 markerSha256=$markerSha256"
  }

  def verify(
      repositoryRoot: File,
      contractArtifact: File,
      markerArtifact: File,
      dependencyClasspath: Seq[File],
      baselineFile: File
  ): Result = {
    val body = ExperimentalPluginApiSurface.parseManifest(
      Files.readAllLines(baselineFile.toPath, StandardCharsets.UTF_8).asScala.toVector
    )
    val classified = body.filter(_.startsWith("CLASS|")).map { line =>
      val fields = line.split("\\|", 4)
      fields(1) -> fields(2)
    }.toMap
    val expectedContract = classified.collect {
      case (entry, "HANDLER_CONTRACT") => entry
      case (entry, "METADATA_CARRIER") => entry
    }.toSet
    val expectedMarkers = classified.collect {
      case (entry, "INTEGRATION_FIXTURE_MARKER") => entry
      case (entry, "INTEGRATION_FIXTURE_SUPPORT") => entry
    }.toSet

    val contractEntries = entries(contractArtifact)
    val markerEntries = entries(markerArtifact)
    val ownershipErrors =
      ExperimentalPluginApiSurface.splitOwnershipErrors(contractEntries, markerEntries)
    require(ownershipErrors.isEmpty, ownershipErrors.mkString("; "))
    require(
      contractEntries.filter(_.endsWith(".class")).toSet == expectedContract,
      "pluginApi class inventory does not equal experimental API contract categories"
    )
    require(
      markerEntries.filter(_.endsWith(".class")).toSet == expectedMarkers,
      "pluginTestMarkers class inventory does not equal experimental API fixture categories"
    )

    val contractSources = sources(repositoryRoot, "plugin-api")
    val markerSources = sources(repositoryRoot, "plugin-test-markers")
    require(contractSources.size == 9, s"expected nine contract sources, found ${contractSources.size}")
    require(markerSources.size == 20, s"expected twenty marker sources, found ${markerSources.size}")
    require(
      contractSources.forall(_.replace(File.separatorChar, '/').contains("/paradise3/api/")),
      s"contract source escaped paradise3/api: ${contractSources.mkString(", ")}"
    )
    require(
      markerSources.forall { path =>
        val normalized = path.replace(File.separatorChar, '/')
        !normalized.contains("/paradise3/api/")
      },
      s"marker source entered pluginApi contract ownership: ${markerSources.mkString(", ")}"
    )

    val urls =
      (Vector(contractArtifact, markerArtifact) ++ dependencyClasspath.filter(_.isFile))
        .map(_.toURI.toURL).distinct.toArray
    val loader = new URLClassLoader(urls, null)
    val property = "macroparadise.metadataInitializationProbe"
    System.clearProperty(property)
    try {
      val carrier = Class.forName("paradise3.api.expander", false, loader)
      val metadata = Vector(
        "paradise3.MetadataInitializationProbe" -> "demo.ExternalDebugExpander",
        "paradise3.externalCompanionDebug" -> "demo.ExternalCompanionDebugExpander",
        "paradise3.externalDebug" -> "demo.ExternalDebugExpander",
        "paradise3.externalLabel" -> "demo.ExternalLabelExpander",
        "paradise3.externalQuasiquotesTerm" ->
          "quasiquotes.macroparadise.QuasiquotesConstructedTermExpander",
        "paradise3.externalRestrictedTraitApply" ->
          "demo.ExternalRestrictedTraitApplyExpander",
        "paradise3.externalSiblingDebug" -> "demo.ExternalSiblingDebugExpander",
        "paradise3.externalTypedLabel" -> "demo.ExternalTypedLabelExpander",
        "paradise3.metadataEmpty" -> "",
        "paradise3.metadataMissing" -> "missing.DoesNotExist"
      )
      metadata.foreach {
        case (name, expected) =>
          val marker = Class.forName(name, false, loader)
          val annotation = marker.getDeclaredAnnotations.find(_.annotationType() == carrier)
            .getOrElse(throw new IllegalStateException(s"$name has no runtime expander metadata"))
          val actual = carrier.getMethod("value").invoke(annotation).toString
          require(actual == expected, s"$name metadata was `$actual`, expected `$expected`")
      }
      val initializationSuppressed = Option(System.getProperty(property)).isEmpty
      require(initializationSuppressed, "metadata inspection initialized MetadataInitializationProbe")

      val typed = Class.forName("paradise3.externalTypedLabel", false, loader)
      val typedShape =
        typed.getTypeParameters.length == 1 &&
          typed.getDeclaredConstructors.exists { constructor =>
            constructor.getParameterTypes.toVector == Vector(classOf[String])
          }
      require(typedShape, "externalTypedLabel generic/constructor shape changed")

      val preserved = Class.forName("paradise3.PreservedRuntimeMarker", false, loader)
      require(preserved.isAnnotation, "PreservedRuntimeMarker is no longer an annotation")
      require(
        preserved.getDeclaredMethods.toVector.map(method => method.getName -> method.getReturnType) ==
          Vector("value" -> classOf[String]),
        "PreservedRuntimeMarker member shape changed"
      )

      Result(
        contractSources.size,
        markerSources.size,
        expectedContract.size,
        expectedMarkers.size,
        contractEntries.count(_.endsWith(".tasty")),
        markerEntries.count(_.endsWith(".tasty")),
        metadata.size,
        initializationSuppressed,
        typedShape,
        sha256(contractArtifact),
        sha256(markerArtifact)
      )
    } finally {
      System.clearProperty(property)
      loader.close()
    }
  }

  private def sources(root: File, project: String): Vector[String] = {
    val sourceRoot = root.toPath.resolve(project).resolve("src/main")
    val stream = Files.walk(sourceRoot)
    try stream.iterator().asScala
      .filter(Files.isRegularFile(_))
      .filter(path => {
        val name = path.getFileName.toString
        name.endsWith(".scala") || name.endsWith(".java")
      })
      .map(_.toString)
      .toVector.sorted
    finally stream.close()
  }

  private def entries(file: File): Vector[String] = {
    val jar = new JarFile(file)
    try jar.entries().asScala.map(_.getName).toVector
    finally jar.close()
  }

  private def sha256(file: File): String =
    MessageDigest.getInstance("SHA-256")
      .digest(Files.readAllBytes(file.toPath))
      .map(byte => f"${byte & 0xff}%02x").mkString
}
