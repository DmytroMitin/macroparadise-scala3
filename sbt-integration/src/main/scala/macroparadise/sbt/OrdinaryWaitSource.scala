package macroparadise.sbt

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.util.Base64
import scala.util.control.NonFatal

private[sbt] object OrdinaryWaitSource {
  def options(rootFile: File, sources: Seq[File], configuration: DerivedSameModuleConfiguration,
      designated: Option[String]): Either[String, Vector[String]] = {
    try {
      val result = designated.toVector.map { raw =>
        def check(ok: Boolean, detail: String): Unit =
          require(ok, "same-module dependent source: " + detail)
        check(configuration.compilerOptions.count(_.startsWith("-P:macroparadise:sameModuleEmbedded=")) == 2 &&
          !configuration.compilerOptions.exists(_.startsWith("-P:macroparadise:sameModuleHandler=")),
          "requires the exact-two embedded binding family (I2)")
        val root = rootFile.toPath.toRealPath()
        check(raw != null && raw.nonEmpty && !raw.exists(c => c == '\n' || c == '\r' || c == '\t'), "provide a nonempty relative .scala path")
        val relative = root.getFileSystem.getPath(raw)
        check(!relative.isAbsolute && !raw.replace('\\', '/').split("/").contains(".."), "absolute paths and traversal are forbidden")
        val candidate = root.resolve(relative).normalize()
        check(candidate.startsWith(root) && Files.isRegularFile(candidate), "source is missing, nonregular, or outside the source root: " + raw)
        val real = candidate.toRealPath()
        check(real.startsWith(root) && real.toString.endsWith(".scala"), "real source must be a .scala file inside the source root: " + raw)
        val matches = sources.count(f => f.toPath.toRealPath() == real)
        check(matches == 1, "expected exactly one physical member of Compile / sources; found " + matches + ": " + raw)
        check(!configuration.sourceIdentity.sources.exists(s => root.resolve(s.relativePath).toRealPath() == real), "a producer cannot be the ordinary dependent source: " + raw)
        val normalized = root.relativize(real).toString.replace(File.separatorChar, '/')
        def encode(value: String) = Base64.getUrlEncoder.withoutPadding.encodeToString(value.getBytes(UTF_8))
        "-P:macroparadise:sameModuleOrdinaryWait=v1:" + encode(root.toString) + ":" + encode(normalized)
      }
      Right(result)
    } catch { case NonFatal(error) => Left(Option(error.getMessage).getOrElse(error.getClass.getSimpleName)) }
  }
}
