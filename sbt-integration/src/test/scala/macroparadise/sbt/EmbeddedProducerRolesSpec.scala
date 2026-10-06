package macroparadise.sbt

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.jar.JarFile

import javax.tools.ToolProvider

import scala.collection.JavaConverters._

import sbt._

class EmbeddedProducerRolesSpec extends munit.FunSuite {
  test("derived roles retain marker dependencies and keep handler implementation disjoint") {
    withCompiledFixture { fixture =>
      val marker = new File(fixture.root, "marker.jar")
      val handler = new File(fixture.root, "handler.jar")

      val inventory = EmbeddedProducerRoles.packageRoles(
        fixture.classes,
        marker,
        handler,
        strict = true
      )

      assertEquals(
        entries(marker),
        Vector("demo/Marker$.class", "demo/Marker.class", "demo/MarkerDependency.class")
      )
      assertEquals(
        entries(handler),
        Vector(
          "demo/Helper.class",
          "demo/Marker__MacroParadiseEmbeddedExpansionHandler.class",
          "demo/Marker__MacroParadiseEmbeddedTransform.class",
          "demo/Unrelated.class"
        )
      )
      assertEquals(inventory.markerEntries, entries(marker))
      assertEquals(inventory.handlerEntries, entries(handler))
      assertEquals(inventory.embeddedMarkers, Vector("demo.Marker"))
      assertEquals(
        inventory.generatedHandlers,
        Vector("demo.Marker__MacroParadiseEmbeddedExpansionHandler")
      )
      assert(entries(marker).toSet.intersect(entries(handler).toSet).isEmpty)
    }
  }

  test("derived role jars are byte deterministic") {
    withCompiledFixture { fixture =>
      val markerA = new File(fixture.root, "marker-a.jar")
      val handlerA = new File(fixture.root, "handler-a.jar")
      val markerB = new File(fixture.root, "marker-b.jar")
      val handlerB = new File(fixture.root, "handler-b.jar")

      EmbeddedProducerRoles.packageRoles(fixture.classes, markerA, handlerA, strict = true)
      EmbeddedProducerRoles.packageRoles(fixture.classes, markerB, handlerB, strict = true)

      assertEquals(Files.readAllBytes(markerA.toPath).toVector, Files.readAllBytes(markerB.toPath).toVector)
      assertEquals(Files.readAllBytes(handlerA.toPath).toVector, Files.readAllBytes(handlerB.toPath).toVector)
    }
  }

  test("handler closure packages local project directories and excludes producer classes") {
    withCompiledFixture { fixture =>
      val marker = new File(fixture.root, "marker.jar")
      val handler = new File(fixture.root, "handler.jar")
      EmbeddedProducerRoles.packageRoles(fixture.classes, marker, handler, strict = true)
      val dependency = new File(fixture.root, "runtime-classes")
      write(dependency, "runtime/token.txt", "dependency-v1")

      val closure = EmbeddedProducerRoles.completeHandlerClasspath(
        handler,
        fixture.classes,
        Seq(fixture.classes, dependency),
        Seq(marker),
        new File(fixture.root, "materialized-runtime")
      )

      assertEquals(closure.head.getCanonicalFile, handler.getCanonicalFile)
      assertEquals(closure.size, 2)
      assertEquals(entries(closure(1)), Vector("runtime/token.txt"))
      assert(!closure.contains(fixture.classes.getCanonicalFile))
    }
  }

  test("strict role packaging rejects a producer without an embedded declaration") {
    val root = Files.createTempDirectory("embedded-producer-empty").toFile
    val classes = new File(root, "classes")
    classes.mkdirs()
    val error = intercept[IllegalArgumentException] {
      EmbeddedProducerRoles.packageRoles(
        classes,
        new File(root, "marker.jar"),
        new File(root, "handler.jar"),
        strict = true
      )
    }
    assert(error.getMessage.contains("no valid embedded declaration"))
  }

  test("role packaging rejects colliding output paths") {
    withCompiledFixture { fixture =>
      val output = new File(fixture.root, "roles.jar")
      val error = intercept[IllegalArgumentException] {
        EmbeddedProducerRoles.packageRoles(fixture.classes, output, output, strict = true)
      }
      assert(error.getMessage.contains("distinct output JARs"))
    }
  }

  test("role packaging rejects marker output inside producer classes") {
    withCompiledFixture { fixture =>
      val error = intercept[IllegalArgumentException] {
        EmbeddedProducerRoles.packageRoles(
          fixture.classes,
          new File(fixture.classes, "marker.jar"),
          new File(fixture.root, "handler.jar"),
          strict = true
        )
      }
      assert(error.getMessage.contains("marker role output must be outside"))
    }
  }

  test("role packaging rejects handler output inside producer classes") {
    withCompiledFixture { fixture =>
      val error = intercept[IllegalArgumentException] {
        EmbeddedProducerRoles.packageRoles(
          fixture.classes,
          new File(fixture.root, "marker.jar"),
          new File(fixture.classes, "handler.jar"),
          strict = true
        )
      }
      assert(error.getMessage.contains("handler role output must be outside"))
    }
  }

  test("handler closure rejects a missing runtime dependency") {
    withCompiledFixture { fixture =>
      val marker = new File(fixture.root, "marker.jar")
      val handler = new File(fixture.root, "handler.jar")
      EmbeddedProducerRoles.packageRoles(fixture.classes, marker, handler, strict = true)
      val error = intercept[IllegalArgumentException] {
        EmbeddedProducerRoles.completeHandlerClasspath(
          handler,
          fixture.classes,
          Seq(new File(fixture.root, "missing.jar")),
          Seq(marker),
          new File(fixture.root, "runtime")
        )
      }
      assert(error.getMessage.contains("does not exist"))
    }
  }

  test("handler closure rejects a primary path that is not a regular jar") {
    withCompiledFixture { fixture =>
      val error = intercept[IllegalArgumentException] {
        EmbeddedProducerRoles.completeHandlerClasspath(
          fixture.classes,
          fixture.classes,
          Seq.empty,
          Seq.empty,
          new File(fixture.root, "runtime")
        )
      }
      assert(error.getMessage.contains("not a regular JAR"))
    }
  }

  test("handler closure rejects a non-jar runtime file") {
    withCompiledFixture { fixture =>
      val marker = new File(fixture.root, "marker.jar")
      val handler = new File(fixture.root, "handler.jar")
      EmbeddedProducerRoles.packageRoles(fixture.classes, marker, handler, strict = true)
      write(fixture.root, "runtime.txt", "not a jar")
      val error = intercept[IllegalArgumentException] {
        EmbeddedProducerRoles.completeHandlerClasspath(
          handler,
          fixture.classes,
          Seq(new File(fixture.root, "runtime.txt")),
          Seq(marker),
          new File(fixture.root, "runtime")
        )
      }
      assert(error.getMessage.contains("not a regular JAR"))
    }
  }

  test("identity rejects one physical artifact reused across roles") {
    withCompiledFixture { fixture =>
      val marker = new File(fixture.root, "marker.jar")
      val handler = new File(fixture.root, "handler.jar")
      EmbeddedProducerRoles.packageRoles(fixture.classes, marker, handler, strict = true)
      val error = intercept[IllegalArgumentException] {
        ArtifactIdentity.derive(
          Seq(LabelledArtifact("marker", marker)),
          Seq(LabelledArtifact("handler", marker))
        )
      }
      assert(error.getMessage.contains("reused across marker and handler roles"))
    }
  }

  test("identity rejects one logical label resolving to conflicting files") {
    withCompiledFixture { fixture =>
      val marker = new File(fixture.root, "marker.jar")
      val handler = new File(fixture.root, "handler.jar")
      EmbeddedProducerRoles.packageRoles(fixture.classes, marker, handler, strict = true)
      val error = intercept[IllegalArgumentException] {
        ArtifactIdentity.derive(
          Seq(LabelledArtifact("role", marker), LabelledArtifact("role", handler)),
          Seq(LabelledArtifact("handler", handler))
        )
      }
      assert(error.getMessage.contains("conflicting files"))
    }
  }

  private final class CompiledFixture(val root: File, val classes: File)

  private def withCompiledFixture(operation: CompiledFixture => Unit): Unit = {
    val root = Files.createTempDirectory("embedded-producer-roles").toFile
    try {
      val source = new File(root, "src")
      val classes = new File(root, "classes")
      classes.mkdirs()
      write(source, "paradise3/api/expander.java",
        """package paradise3.api;
          |import java.lang.annotation.Retention;
          |import java.lang.annotation.RetentionPolicy;
          |@Retention(RetentionPolicy.RUNTIME)
          |public @interface expander { String value(); }
          |""".stripMargin)
      write(source, "paradise3/api/ExpansionHandler.java",
        """package paradise3.api;
          |public interface ExpansionHandler {}
          |""".stripMargin)
      write(source, "demo/MarkerDependency.java",
        """package demo;
          |public final class MarkerDependency {}
          |""".stripMargin)
      write(source, "demo/Marker$.java",
        """package demo;
          |public final class Marker$ {}
          |""".stripMargin)
      write(source, "demo/Marker.java",
        """package demo;
          |@paradise3.api.expander("demo.Marker__MacroParadiseEmbeddedExpansionHandler")
          |public final class Marker {
          |  public demo.MarkerDependency dependency;
          |  public demo.Marker$ defaults;
          |}
          |""".stripMargin)
      write(source, "demo/Helper.java",
        """package demo;
          |public final class Helper { public static String value() { return "ok"; } }
          |""".stripMargin)
      write(source, "demo/Marker__MacroParadiseEmbeddedTransform.java",
        """package demo;
          |public final class Marker__MacroParadiseEmbeddedTransform {
          |  public static String run() { return Helper.value(); }
          |}
          |""".stripMargin)
      write(source, "demo/Marker__MacroParadiseEmbeddedExpansionHandler.java",
        """package demo;
          |public final class Marker__MacroParadiseEmbeddedExpansionHandler
          |    implements paradise3.api.ExpansionHandler {
          |  public String run() { return Marker__MacroParadiseEmbeddedTransform.run(); }
          |}
          |""".stripMargin)
      write(source, "demo/Unrelated.java",
        """package demo;
          |public final class Unrelated {}
          |""".stripMargin)

      val sources = (source ** "*.java").get.map(_.getAbsolutePath)
      val compiler = ToolProvider.getSystemJavaCompiler
      assert(compiler != null, "tests require a JDK compiler")
      val exit = compiler.run(null, null, null, (Seq("-d", classes.getAbsolutePath) ++ sources): _*)
      assertEquals(exit, 0)
      operation(new CompiledFixture(root, classes))
    } finally sbt.IO.delete(root)
  }

  private def write(root: File, relative: String, contents: String): Unit = {
    val file = new File(root, relative)
    file.getParentFile.mkdirs()
    Files.write(file.toPath, contents.getBytes(StandardCharsets.UTF_8))
  }

  private def entries(file: File): Vector[String] = {
    val jar = new JarFile(file)
    try jar.entries().asScala.map(_.getName).filterNot(_.endsWith("/")).toVector
    finally jar.close()
  }
}
