import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.jar.JarFile

import scala.collection.JavaConverters._

object ExperimentalHandlerContractArtifactSpec {
  val CaseCount = 17

  def run(): Unit = {
    var completed = 0
    def check(name: String)(body: => Unit): Unit = {
      try {
        body
        completed += 1
      } catch {
        case error: Throwable =>
          throw new AssertionError(s"handler-contract artifact spec failed: $name", error)
      }
    }
    def fails(fragment: String)(body: => Unit): Unit = {
      val error = try {
        body
        throw new AssertionError("expected failure")
      } catch {
        case expected: IllegalArgumentException => expected
      }
      assert(error.getMessage.contains(fragment), error.getMessage)
    }

    val validBody = Vector(
      "format-version=1",
      "scala-compiler=exact",
      "artifact-role=synthetic",
      "CLASS|paradise3/api/Handler.class|HANDLER_CONTRACT|public class Handler",
      "CLASS|paradise3/api/Nested$.class|HANDLER_CONTRACT|public class Nested$",
      "CLASS|paradise3/api/expander.class|METADATA_CARRIER|public annotation expander",
      "CLASS|paradise3/Fixture.class|INTEGRATION_FIXTURE_MARKER|public class Fixture",
      "CLASS|paradise3/Support.class|INTEGRATION_FIXTURE_SUPPORT|public class Support",
      ExperimentalHandlerContractArtifact.ExpectedMetadataRecord,
      "RESOURCE|META-INF/MANIFEST.MF|STANDARD_JAR_METADATA",
      "RESOURCE|paradise3/api/Handler.tasty|SCALA_TASTY",
      "RESOURCE|paradise3/api/Nested.tasty|SCALA_TASTY"
    )
    def manifest(body: Vector[String]): Vector[String] =
      ExperimentalPluginApiSurface.withIntegrity(body)
    val plan = ExperimentalHandlerContractArtifact.parsePlan(manifest(validBody))

    check("valid full-manifest filtering") {
      assert(plan.handlerClasses.size == 2)
      assert(plan.requiredTasty == Vector(
        "paradise3/api/Handler.tasty",
        "paradise3/api/Nested.tasty"
      ))
      assert(plan.metadataCarrier == "paradise3/api/expander.class")
    }
    check("malformed class record") {
      fails("malformed class/category record") {
        ExperimentalHandlerContractArtifact.parsePlan(
          manifest(validBody :+ "CLASS|broken")
        )
      }
    }
    check("duplicate class record") {
      fails("duplicate") {
        ExperimentalHandlerContractArtifact.parsePlan(
          manifest(validBody :+ validBody.find(_.contains("Handler.class")).get)
        )
      }
    }
    check("unknown category") {
      fails("unknown surface category") {
        ExperimentalHandlerContractArtifact.parsePlan(
          manifest(validBody :+ "CLASS|paradise3/api/Unknown.class|UNKNOWN|public class Unknown")
        )
      }
    }
    check("missing metadata carrier") {
      fails("exactly one METADATA_CARRIER") {
        ExperimentalHandlerContractArtifact.parsePlan(
          manifest(validBody.filterNot(_.contains("expander.class|METADATA_CARRIER")))
        )
      }
    }
    check("multiple metadata carriers") {
      fails("exactly one METADATA_CARRIER") {
        ExperimentalHandlerContractArtifact.parsePlan(
          manifest(validBody :+ "CLASS|paradise3/api/other.class|METADATA_CARRIER|public annotation other")
        )
      }
    }
    check("missing handler-contract category") {
      fails("no HANDLER_CONTRACT") {
        ExperimentalHandlerContractArtifact.parsePlan(
          manifest(validBody.filterNot(_.contains("|HANDLER_CONTRACT|")))
        )
      }
    }
    check("missing handler-contract class") {
      val errors = ExperimentalHandlerContractArtifact.validateCandidateEntries(
        plan,
        plan.allowedEntries.filterNot(_ == "paradise3/api/Handler.class")
      )
      assert(errors.exists(_.contains("missing required entries")))
    }
    check("accidentally retained fixture marker") {
      val errors = ExperimentalHandlerContractArtifact.validateCandidateEntries(
        plan,
        plan.allowedEntries :+ plan.fixtureMarkers.head
      )
      assert(errors.exists(_.contains("accidentally retained fixture marker")))
    }
    check("accidentally retained fixture support") {
      val errors = ExperimentalHandlerContractArtifact.validateCandidateEntries(
        plan,
        plan.allowedEntries :+ plan.fixtureSupport.head
      )
      assert(errors.exists(_.contains("accidentally retained fixture support")))
    }
    check("forbidden implementation package") {
      val errors = ExperimentalHandlerContractArtifact.validateCandidateEntries(
        plan,
        plan.allowedEntries :+ "macroparadise/HelloWorldPlugin.class"
      )
      assert(errors.exists(_.contains("forbidden implementation/dependency package")))
    }
    check("missing required TASTy") {
      val errors = ExperimentalHandlerContractArtifact.validateCandidateEntries(
        plan,
        plan.allowedEntries.filterNot(_ == "paradise3/api/Handler.tasty")
      )
      assert(errors.exists(_.contains("missing required TASTy")))
    }
    check("orphan TASTy") {
      val errors = ExperimentalHandlerContractArtifact.validateCandidateEntries(
        plan,
        plan.allowedEntries :+ "paradise3/api/Orphan.tasty"
      )
      assert(errors.exists(_.contains("orphan TASTy")))
    }
    check("unexpected resource") {
      val errors = ExperimentalHandlerContractArtifact.validateCandidateEntries(
        plan,
        plan.allowedEntries :+ "META-INF/services/example.Service"
      )
      assert(errors.exists(_.contains("unexpected entries")))
    }
    check("nondeterministic input order is normalized") {
      withTemporaryDirectory { directory =>
        val jar = directory.resolve("ordered.jar").toFile
        ExperimentalHandlerContractArtifact.writeDeterministicJar(
          jar,
          Map(
            "z.class" -> Array[Byte](2),
            "META-INF/MANIFEST.MF" -> "manifest".getBytes(StandardCharsets.UTF_8),
            "a.class" -> Array[Byte](1)
          )
        )
        val opened = new JarFile(jar)
        try assert(opened.entries().asScala.map(_.getName).toVector == Vector(
          "META-INF/MANIFEST.MF",
          "a.class",
          "z.class"
        ))
        finally opened.close()
      }
    }
    check("deterministic double rendering") {
      withTemporaryDirectory { directory =>
        val entries = Map(
          "META-INF/MANIFEST.MF" -> "manifest".getBytes(StandardCharsets.UTF_8),
          "paradise3/api/A.class" -> Array[Byte](1, 2, 3)
        )
        val first = directory.resolve("first.jar").toFile
        val second = directory.resolve("second.jar").toFile
        ExperimentalHandlerContractArtifact.writeDeterministicJar(first, entries)
        ExperimentalHandlerContractArtifact.writeDeterministicJar(second, entries)
        assert(java.util.Arrays.equals(
          Files.readAllBytes(first.toPath),
          Files.readAllBytes(second.toPath)
        ))
      }
    }
    check("focused added and removed entry diagnostics") {
      val diff = ExperimentalHandlerContractArtifact.entryDiff(
        Vector("a.class", "b.class"),
        Vector("b.class", "c.class")
      )
      assert(diff.render == "REMOVED a.class\nADDED c.class")
    }

    assert(completed == CaseCount, s"completed $completed/$CaseCount cases")
  }

  private def withTemporaryDirectory(body: java.nio.file.Path => Unit): Unit = {
    val directory = Files.createTempDirectory("handler-contract-artifact-spec-")
    try body(directory)
    finally {
      val stream = Files.walk(directory)
      try stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.delete)
      finally stream.close()
    }
  }
}
