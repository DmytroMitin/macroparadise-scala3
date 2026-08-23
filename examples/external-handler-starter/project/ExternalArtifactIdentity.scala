import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest

object ExternalArtifactIdentity {
  def combined(marker: File, handler: File): String = {
    val markerHash = sha256(marker)
    val handlerHash = sha256(handler)
    sha256Text(s"marker=$markerHash\nhandler=$handlerHash\n")
  }

  private def sha256(file: File): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    val in = Files.newInputStream(file.toPath)
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

  private def sha256Text(value: String): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(value.getBytes(StandardCharsets.UTF_8))
    hex(digest.digest())
  }

  private def hex(bytes: Array[Byte]): String =
    bytes.map(value => f"${value & 0xff}%02x").mkString
}
