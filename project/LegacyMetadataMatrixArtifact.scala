import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.jar.JarFile

object LegacyMetadataMatrixArtifact {
  final case class Evidence(
      compilerVersion: String,
      artifact: File,
      size: Long,
      sha256: String,
      markerClassSize: Long,
      markerTastySize: Long
  ) {
    def render: String =
      s"legacy producer Scala $compilerVersion: artifact=${artifact.getAbsolutePath}, " +
        s"size=$size, sha256=$sha256, markerClassSize=$markerClassSize, " +
        s"markerTastySize=$markerTastySize"
  }

  def verify(compilerVersion: String, artifact: File): Evidence = {
    require(artifact.isFile, s"legacy producer artifact does not exist: $artifact")

    val markerClass = "paradise3/legacyExternalDebug.class"
    val markerTasty = "paradise3/legacyExternalDebug.tasty"
    val forbidden = Set(
      "paradise3/api/expander.class",
      "paradise3/api/expander.tasty"
    )

    val jar = new JarFile(artifact)
    val (classSize, tastySize) =
      try {
        val names = scala.collection.mutable.Set.empty[String]
        val entries = jar.entries()
        while (entries.hasMoreElements) {
          names += entries.nextElement().getName
        }

        require(names.contains(markerClass), s"$compilerVersion artifact is missing $markerClass")
        require(names.contains(markerTasty), s"$compilerVersion artifact is missing $markerTasty")
        require(
          forbidden.intersect(names.toSet).isEmpty,
          s"$compilerVersion artifact exposes obsolete carrier: ${forbidden.intersect(names.toSet).toList.sorted.mkString(", ")}"
        )

        val tastyStream = jar.getInputStream(jar.getJarEntry(markerTasty))
        val tastyBytes =
          try tastyStream.readAllBytes()
          finally tastyStream.close()
        val tastyText =
          new String(tastyBytes, StandardCharsets.ISO_8859_1)
        require(
          tastyText.contains("demo.LegacyExternalDebugExpander"),
          s"$compilerVersion TASTy does not contain the expected handler name"
        )

        (jar.getJarEntry(markerClass).getSize, jar.getJarEntry(markerTasty).getSize)
      } finally jar.close()

    val javap =
      new ProcessBuilder(
        "javap",
        "-v",
        "-classpath",
        artifact.getAbsolutePath,
        "paradise3.legacyExternalDebug"
      ).redirectErrorStream(true).start()
    val javapOutput =
      new String(javap.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    val javapExit = javap.waitFor()
    require(javapExit == 0, s"javap failed for Scala $compilerVersion:\n$javapOutput")
    require(
      javapOutput.contains("TASTY:"),
      s"Scala $compilerVersion marker has no TASTY classfile attribute"
    )
    require(
      !javapOutput.contains("RuntimeVisibleAnnotations"),
      s"Scala $compilerVersion marker unexpectedly has runtime-visible metadata"
    )
    require(
      !javapOutput.contains("paradise3.api.expander"),
      s"Scala $compilerVersion marker classfile references the obsolete carrier"
    )

    Evidence(
      compilerVersion = compilerVersion,
      artifact = artifact,
      size = artifact.length(),
      sha256 = sha256(artifact),
      markerClassSize = classSize,
      markerTastySize = tastySize
    )
  }

  private def sha256(file: File): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    val stream = Files.newInputStream(file.toPath)
    try {
      val buffer = new Array[Byte](8192)
      var read = stream.read(buffer)
      while (read >= 0) {
        if (read > 0) digest.update(buffer, 0, read)
        read = stream.read(buffer)
      }
    } finally stream.close()

    digest.digest().map(byte => f"${byte & 0xff}%02x").mkString
  }
}
