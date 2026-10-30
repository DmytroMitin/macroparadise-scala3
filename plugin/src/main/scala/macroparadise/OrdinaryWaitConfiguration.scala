package macroparadise

import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import java.nio.file.{Files, Path}
import java.util.Base64
import scala.util.control.NonFatal

/** Private, tracked build declaration; never an annotation relationship. */
private[macroparadise] object OrdinaryWaitConfiguration:
  final case class Configuration(root: Path, source: Path)
  private val Prefix = "sameModuleOrdinaryWait="

  def parse(options: List[String]): Either[String, Option[Configuration]] =
    val records = options.filter(_.startsWith(Prefix))
    if records.size > 1 then Left("SAME_MODULE_ORDINARY_WAIT_DUPLICATE: configure at most one ordinary source")
    else records.headOption match
      case None => Right(None)
      case Some(record) =>
        try
          val parts = record.stripPrefix(Prefix).split(":", -1)
          require(parts.length == 3 && parts(0) == "v1", "expected v1:<root-base64url>:<relative-base64url>")
          def decode(value: String): String =
            val bytes = Base64.getUrlDecoder.decode(value)
            require(Base64.getUrlEncoder.withoutPadding.encodeToString(bytes) == value, "encoding must be canonical unpadded Base64url")
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString
          val rootText = decode(parts(1))
          val relativeText = decode(parts(2))
          val root = Path.of(rootText)
          val relative = Path.of(relativeText)
          require(root.isAbsolute && root.toRealPath().toString == rootText, "source root must be a canonical real absolute directory")
          require(Files.isDirectory(root), "source root is not a directory")
          require(relativeText.nonEmpty && !relative.isAbsolute && !relativeText.contains('\\') &&
            !relativeText.split("/", -1).exists(s => s.isEmpty || s == "." || s == ".."), "source must be a normalized relative path without traversal")
          val source = root.resolve(relative)
          require(source.startsWith(root) && Files.isRegularFile(source) && source.toRealPath() == source &&
            source.toString.endsWith(".scala"), "source must be an existing real .scala file inside the declared root")
          Right(Some(Configuration(root, source)))
        catch case NonFatal(error) =>
          Left(s"SAME_MODULE_ORDINARY_WAIT_INVALID: ${Option(error.getMessage).getOrElse(error.getClass.getSimpleName)}")
