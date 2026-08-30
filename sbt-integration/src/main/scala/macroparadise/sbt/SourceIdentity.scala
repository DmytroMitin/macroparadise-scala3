package macroparadise.sbt

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest

final case class LabelledSource(label: String, relativePath: String)

final case class DerivedSourceIdentity(
    identity: String,
    manifest: String,
    sources: Vector[LabelledSource]
)

object SourceIdentity {
  def derive(sourceRoot: File, configuredSources: Seq[LabelledSource]): DerivedSourceIdentity = {
    val root = sourceRoot.toPath.toRealPath()
    configuredSources.foreach { configured =>
      check(
        configured.label != null &&
          configured.label.nonEmpty &&
          !configured.label.exists(character => character == '\t' || character == '\n' || character == '\r'),
        "same-module source label must be nonempty and contain no tabs or line breaks"
      )
    }
    val duplicateLabels =
      configuredSources.groupBy(_.label).collect { case (label, entries) if entries.size > 1 => label }.toVector.sorted
    check(
      duplicateLabels.isEmpty,
      s"duplicate same-module source labels: ${duplicateLabels.mkString(", ")}"
    )
    val sources = configuredSources.map { configured =>
      check(
        configured.relativePath.trim.nonEmpty,
        "configured same-module source path must not be empty"
      )
      val configuredPath = sourceRoot.toPath.getFileSystem.getPath(configured.relativePath)
      check(
        !configuredPath.isAbsolute,
        "configured same-module source path must be relative"
      )
      val candidate = root.resolve(configuredPath).normalize()
      check(candidate.startsWith(root), "configured same-module source escapes the source root")
      check(
        Files.exists(candidate),
        s"configured same-module source is missing: ${configured.relativePath}"
      )
      val source = candidate.toRealPath()
      check(source.startsWith(root), "configured same-module source escapes the source root")
      check(Files.isRegularFile(source), "configured same-module source is not a regular file")
      LabelledSource(configured.label, relative(root, source))
    }.sortBy(_.label).toVector
    val duplicatePaths =
      sources.groupBy(_.relativePath).collect { case (path, entries) if entries.size > 1 => path }.toVector.sorted
    check(
      duplicatePaths.isEmpty,
      s"duplicate normalized same-module source paths: ${duplicatePaths.mkString(", ")}"
    )

    val manifest = sources.map { source =>
      val path = root.resolve(source.relativePath)
      s"${source.label}\t${source.relativePath}\t${sha256(path)}\n"
    }.mkString
    DerivedSourceIdentity(sha256(manifest.getBytes(StandardCharsets.UTF_8)), manifest, sources)
  }

  private def relative(root: Path, source: Path): String =
    root.relativize(source).toString.replace(File.separatorChar, '/')

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
