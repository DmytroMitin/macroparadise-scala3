import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest

import scala.collection.mutable

object ExternalArtifactIdentity {
  def combined(
      markerArtifacts: Seq[(String, File)],
      handlerClasspath: Seq[(String, File)]
  ): String = {
    require(markerArtifacts.nonEmpty, "marker role is empty")
    require(handlerClasspath.nonEmpty, "handler role is empty")
    val labels = mutable.LinkedHashMap.empty[String, Path]

    def normalize(inputs: Seq[(String, File)]): Vector[(String, Path)] = {
      val seen = mutable.LinkedHashSet.empty[Path]
      inputs.iterator.flatMap { case (label, file) =>
        require(label.nonEmpty && !label.exists(ch => ch == '\t' || ch == '\n' || ch == '\r'), "invalid logical label")
        require(file.exists(), s"artifact does not exist: $label")
        val real = file.toPath.toRealPath()
        require(Files.isRegularFile(real) && real.getFileName.toString.toLowerCase(java.util.Locale.ROOT).endsWith(".jar"), s"artifact must be a regular .jar file: $label")
        labels.get(label).foreach(existing => require(existing == real, s"logical label resolves to conflicting files: $label"))
        labels.update(label, real)
        if (seen.add(real)) Some(label -> real) else None
      }.toVector
    }

    val markers = normalize(markerArtifacts)
    val handlers = normalize(handlerClasspath)
    require(
      markers.map(_._2).toSet.intersect(handlers.map(_._2).toSet).isEmpty,
      "artifact is reused across marker and handler roles"
    )
    val manifest =
      markers.map { case (label, path) => s"marker\t$label\t${sha256(path)}\n" }.mkString +
        handlers.zipWithIndex.map { case ((label, path), index) =>
          f"handler\t$index%04d:$label\t${sha256(path)}\n"
        }.mkString
    sha256(manifest.getBytes(StandardCharsets.UTF_8))
  }

  private def sha256(path: Path): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    val in = Files.newInputStream(path)
    try {
      val buffer = new Array[Byte](8192)
      var read = in.read(buffer)
      while (read >= 0) {
        if (read > 0) digest.update(buffer, 0, read)
        read = in.read(buffer)
      }
    } finally in.close()
    hex(digest.digest())
  }

  private def sha256(bytes: Array[Byte]): String =
    hex(MessageDigest.getInstance("SHA-256").digest(bytes))

  private def hex(bytes: Array[Byte]): String =
    bytes.map(value => f"${value & 0xff}%02x").mkString
}
