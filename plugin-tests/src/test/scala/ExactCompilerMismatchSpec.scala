import dotty.tools.dotc.config.Properties

import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import scala.jdk.CollectionConverters.*

final class ExactCompilerMismatchSpec extends munit.FunSuite:
  private val projectVersion =
    sys.props.getOrElse("macroparadise.testProjectVersion", "0.1.1-SNAPSHOT")

  test("the active compiler rejects the plugin artifact from the other exact line"):
    val active = Properties.versionNumberString
    val other = if active == "3.3.8" then "3.8.4" else "3.3.8"
    val wrongPlugin =
      File(
        s"plugin/target/scala-$other/macroparadise-scala3-plugin_$other-$projectVersion.jar"
      ).getAbsoluteFile
    assume(
      wrongPlugin.isFile,
      s"bidirectional mismatch proof requires the separately packaged $other artifact"
    )

    val temporary = Files.createTempDirectory("macroparadise-exact-mismatch")
    val source = temporary.resolve("MismatchConsumer.scala")
    val output = temporary.resolve("out")
    Files.createDirectories(output)
    Files.writeString(source, "final class MismatchConsumer\n")
    val activeCompilerClasspath =
      loaderUrls(getClass.getClassLoader)
        .flatMap: url =>
          try Some(File(url.toURI).getAbsoluteFile)
          catch case _: Exception => None
        .filter(file => file.isFile && file.getName.endsWith(".jar"))
        .filterNot: file =>
          val path = file.getAbsolutePath.replace(File.separatorChar, '/')
          path.contains("/macroparadise-scala3/plugin/target/") ||
          path.contains("/macroparadise-scala3/plugin-tests/target/")
        .distinct
    assert(
      activeCompilerClasspath.exists(
        _.getName == s"scala3-compiler_3-$active.jar"
      ),
      activeCompilerClasspath.mkString("\n")
    )
    assert(
      !activeCompilerClasspath.exists(
        _.getName == s"scala3-compiler_3-$other.jar"
      ),
      activeCompilerClasspath.mkString("\n")
    )

    val classpath = activeCompilerClasspath.map(_.getAbsolutePath).mkString(File.pathSeparator)
    val process = ProcessBuilder(
      File(File(sys.props("java.home"), "bin"), "java").getAbsolutePath,
      "-cp",
      classpath,
      "dotty.tools.dotc.Main",
      "-classpath",
      classpath,
      "-d",
      output.toString,
      s"-Xplugin:${wrongPlugin.getAbsolutePath}",
      "-Xplugin-require:macroparadise",
      source.toString
    ).redirectErrorStream(true).start()
    val diagnostic = String(process.getInputStream.readAllBytes())
    val exit = process.waitFor()

    assert(exit != 0, diagnostic)
    assert(diagnostic.contains("macroparadise exact compiler mismatch"), diagnostic)
    assert(diagnostic.contains(s"plugin=$other"), diagnostic)
    assert(diagnostic.contains(s"compiler=$active"), diagnostic)
    assertEquals(regularFiles(output), Nil)

  private def loaderUrls(loader: ClassLoader): List[java.net.URL] =
    if loader == null then Nil
    else
      loader match
        case value: URLClassLoader => value.getURLs.toList ::: loaderUrls(value.getParent)
        case _ => loaderUrls(loader.getParent)

  private def regularFiles(root: java.nio.file.Path): List[String] =
    val stream = Files.walk(root)
    try
      stream.iterator.asScala
        .filter(path => Files.isRegularFile(path))
        .map(path => root.relativize(path).toString)
        .toList
        .sorted
    finally stream.close()
