package macroparadise.sbt

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest

import scala.collection.mutable

final case class LabelledArtifact(label: String, file: File)

final case class DerivedArtifactIdentity(
    identity: String,
    manifest: String,
    markerArtifacts: Vector[LabelledArtifact],
    handlerClasspath: Vector[LabelledArtifact]
)

object ArtifactIdentity {
  def derive(
      markerArtifacts: Seq[LabelledArtifact],
      handlerClasspath: Seq[LabelledArtifact]
  ): DerivedArtifactIdentity = {
    check(markerArtifacts.nonEmpty, "marker role is empty")
    check(handlerClasspath.nonEmpty, "handler role is empty")

    val labels = mutable.LinkedHashMap.empty[String, Path]
    def registerLabel(artifact: LabelledArtifact, path: Path): Unit = {
      check(
        artifact.label.nonEmpty && !artifact.label.exists(ch => ch == '\t' || ch == '\n' || ch == '\r'),
        "logical label must be nonempty and contain no tabs or line breaks"
      )
      labels.get(artifact.label) match {
        case Some(existing) if existing != path =>
          throw new IllegalArgumentException(
            s"logical label '${artifact.label}' resolves to conflicting files"
          )
        case _ => labels.update(artifact.label, path)
      }
    }

    def normalize(inputs: Seq[LabelledArtifact]): Vector[LabelledArtifact] = {
      val seen = mutable.LinkedHashSet.empty[Path]
      inputs.iterator.flatMap { artifact =>
        val path = normalizePath(artifact.file)
        registerLabel(artifact, path)
        if (seen.add(path)) Some(LabelledArtifact(artifact.label, path.toFile)) else None
      }.toVector
    }

    val markers = normalize(markerArtifacts)
    val handlers = normalize(handlerClasspath)
    val markerPaths = markers.iterator.map(_.file.toPath).toSet
    val handlerPaths = handlers.iterator.map(_.file.toPath).toSet
    check(
      markerPaths.intersect(handlerPaths).isEmpty,
      "artifact is reused across marker and handler roles"
    )

    val markerRecords = markers.map { artifact =>
      s"marker\t${artifact.label}\t${sha256(artifact.file.toPath)}\n"
    }
    val handlerRecords = handlers.zipWithIndex.map { case (artifact, index) =>
      f"handler\t$index%04d:${artifact.label}\t${sha256(artifact.file.toPath)}\n"
    }
    val manifest = (markerRecords ++ handlerRecords).mkString
    DerivedArtifactIdentity(
      sha256(manifest.getBytes(StandardCharsets.UTF_8)),
      manifest,
      markers,
      handlers
    )
  }

  private def normalizePath(file: File): Path = {
    val path = file.toPath
    check(Files.exists(path), "artifact does not exist")
    val real = path.toRealPath()
    check(
      Files.isRegularFile(real) && real.getFileName.toString.toLowerCase(java.util.Locale.ROOT).endsWith(".jar"),
      "artifact must be a regular .jar file"
    )
    real
  }

  private def sha256(path: Path): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    val input = Files.newInputStream(path)
    try {
      val buffer = new Array[Byte](8192)
      var read = input.read(buffer)
      while (read >= 0) {
        if (read > 0) digest.update(buffer, 0, read)
        read = input.read(buffer)
      }
    } finally input.close()
    hex(digest.digest())
  }

  private def sha256(bytes: Array[Byte]): String =
    hex(MessageDigest.getInstance("SHA-256").digest(bytes))

  private def hex(bytes: Array[Byte]): String =
    bytes.map(value => f"${value & 0xff}%02x").mkString

  private def check(condition: Boolean, message: String): Unit =
    if (!condition) throw new IllegalArgumentException(message)
}
