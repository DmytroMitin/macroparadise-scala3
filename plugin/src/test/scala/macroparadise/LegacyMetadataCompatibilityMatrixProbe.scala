package macroparadise

import java.io.File
import java.net.URLClassLoader

object LegacyMetadataCompatibilityMatrixProbe:
  private val ExpectedHandler = "demo.LegacyExternalDebugExpander"

  def main(args: Array[String]): Unit =
    require(
      args.length == 5,
      "expected <scala-version> <artifact> <scala-version> <artifact> <plugin-api-jar>"
    )

    val pluginApiJar = args(4)
    verifyLane(args(0), args(1), pluginApiJar)
    verifyLane(args(2), args(3), pluginApiJar)

  private def verifyLane(
      compilerVersion: String,
      artifactPath: String,
      pluginApiJar: String
  ): Unit =
    val artifact = File(artifactPath)
    require(artifact.isFile, s"missing Scala $compilerVersion artifact: $artifact")

    val loader =
      URLClassLoader(Array(artifact.toURI.toURL), getClass.getClassLoader)
    try
      val runtime =
        RuntimeAnnotationMetadataReader(loader)
          .findExpanderClass("legacyExternalDebug")
      val structured =
        TastyInspectorAnnotationMetadataReader(
          List(artifact.getAbsolutePath, pluginApiJar)
        ).findExpanderClass("legacyExternalDebug")
      val string =
        TastyStringAnnotationMetadataReader(loader)
          .findExpanderClass("legacyExternalDebug")

      assert(runtime == MetadataLookupResult.NotFound)
      assert(structured == MetadataLookupResult.Found(ExpectedHandler))
      assert(string == MetadataLookupResult.Found(ExpectedHandler))

      val marker =
        Class.forName("paradise3.legacyExternalDebug", false, loader)
      val carrierFromLegacyLoader =
        Class.forName("paradise3.api.expander", false, loader)
      val currentCarrier =
        Class.forName(
          "paradise3.api.expander",
          false,
          getClass.getClassLoader
        )

      assert(marker.getClassLoader eq loader)
      assert(carrierFromLegacyLoader eq currentCarrier)

      println(
        s"MATRIX scala=$compilerVersion runtime=$runtime structured=$structured string=$string carrier=current-parent"
      )
    finally loader.close()
