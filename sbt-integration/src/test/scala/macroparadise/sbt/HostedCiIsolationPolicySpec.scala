package macroparadise.sbt

import java.nio.file.{Files, Paths}
import scala.collection.JavaConverters._

import munit.FunSuite

final class HostedCiIsolationPolicySpec extends FunSuite {
  private lazy val workflowLines =
    Files.readAllLines(Paths.get("..", ".github", "workflows", "test.yml")).asScala.toVector

  test("hosted CI preserves opposite-line preparation and bootstraps every current-line product module before integration") {
    val opposite = step("Prepare opposite exact plugin artifact")
    val bootstrap = step("Prepare current exact source-built product artifacts")
    val integration = step("Verify source-built sbt integration")

    assert(stepIndex("Prepare opposite exact plugin artifact") < stepIndex("Prepare current exact source-built product artifacts"))
    assert(stepIndex("Prepare current exact source-built product artifacts") < stepIndex("Verify source-built sbt integration"))
    assert(opposite.contains("matrix.opposite-scala-version"))
    assert(opposite.contains("plugin/packageBin"))
    assert(bootstrap.contains("-Dmacroparadise.exactScalaVersion=${{ matrix.scala-version }}"))
    assert(bootstrap.contains("'++${{ matrix.scala-version }}!'"))
    assert(bootstrap.contains("'pluginApi/publishLocal'"))
    assert(bootstrap.contains("'embeddedProducerPlugin/publishLocal'"))
    assert(bootstrap.contains("'plugin/publishLocal'"))
    assert(integration.nonEmpty)
  }

  test("hosted source-built integration propagates the matrix Scala line into scripted") {
    val integration = step("Verify source-built sbt integration")

    assert(integration.contains("-Dtest.scala.version=${{ matrix.scala-version }}"))
    assert(integration.contains("verifyIntegrationPolicy test scripted packageSrc packageDoc"))
  }

  test("every same-module embedded fixture that selects Scala honors the hosted matrix line") {
    val fixtureRoot = Paths.get("src", "sbt-test", "same-module")
    val entries = Files.list(fixtureRoot)
    try {
      entries.iterator.asScala
        .filter(path => path.getFileName.toString.startsWith("embedded"))
        .map(_.resolve("build.sbt"))
        .filter(path => Files.isRegularFile(path))
        .foreach { build =>
          val contents = Files.readAllLines(build).asScala.mkString("\n")
          if (contents.contains("scalaVersion :=")) {
            assert(
              contents.contains("scalaVersion := sys.props.getOrElse(\"test.scala.version\", \"3.8.4\")"),
              build + " must use the hosted matrix Scala line"
            )
          }
        }
    } finally entries.close()
  }

  private def stepIndex(name: String): Int = {
    val index = workflowLines.indexOf("      - name: " + name)
    assert(index >= 0, "missing hosted CI step: " + name)
    index
  }

  private def step(name: String): String = {
    val start = stepIndex(name)
    val end = workflowLines.indexWhere(_.startsWith("      - name: "), start + 1) match {
      case -1 => workflowLines.length
      case value => value
    }
    workflowLines.slice(start, end).mkString("\n")
  }
}
