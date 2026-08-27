import java.nio.charset.StandardCharsets
import java.nio.file.Files

object ExternalArtifactIdentitySpec {
  def run(target: java.io.File): Unit = {
    val root = new java.io.File(target, "external-artifact-identity-spec")
    sbt.IO.delete(root)
    sbt.IO.createDirectory(root)
    def artifact(name: String, value: String): java.io.File = {
      val file = new java.io.File(root, name)
      Files.write(file.toPath, value.getBytes(StandardCharsets.UTF_8))
      file
    }
    val marker = artifact("marker.jar", "marker")
    val handler = artifact("handler.jar", "handler")
    val dependency = artifact("dependency.jar", "dependency-v1")
    val before = ExternalArtifactIdentity.combined(
      Seq("marker" -> marker),
      Seq("handler" -> handler, "runtime" -> dependency)
    )
    Files.write(dependency.toPath, "dependency-v2".getBytes(StandardCharsets.UTF_8))
    val after = ExternalArtifactIdentity.combined(
      Seq("marker" -> marker),
      Seq("handler" -> handler, "runtime" -> dependency)
    )
    assert(before != after)
    assert(before.matches("[0-9a-f]{64}") && after.matches("[0-9a-f]{64}"))
  }
}
