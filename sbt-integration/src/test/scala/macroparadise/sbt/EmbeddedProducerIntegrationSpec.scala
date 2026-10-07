package macroparadise.sbt

import java.io.File
import java.nio.file.Files
import scala.collection.JavaConverters._

import sbt._

class EmbeddedProducerIntegrationSpec extends munit.FunSuite {
  test("embedded published module identities are distinct exact-full-cross roles") {
    val (marker, handler) = MacroParadiseIntegration.embeddedModuleIds(
      "com.example",
      "sample-producer",
      "1.2.3"
    )

    assertEquals(marker.organization, "com.example")
    assertEquals(marker.name, "sample-producer-macro-annotations")
    assertEquals(marker.revision, "1.2.3")
    assertEquals(marker.configurations, Some(Provided.name))
    assertEquals(marker.crossVersion, CrossVersion.full)
    assertEquals(handler.organization, "com.example")
    assertEquals(handler.name, "sample-producer-macro-handlers")
    assertEquals(handler.revision, "1.2.3")
    assertEquals(handler.crossVersion, CrossVersion.full)
    assertNotEquals(marker.name, handler.name)
  }

  test("embedded producer sbt public surface matches the reviewed additive baseline") {
    val baseline = Files.readAllLines(new File("sbt-public-surface.txt").toPath).asScala.filter(_.nonEmpty).toVector
    assertEquals(
      baseline,
      Vector(
        "MacroParadiseEmbeddedProducerPlugin",
        "MacroParadiseEmbeddedProducerPlugin.EmbeddedMarkerConfiguration",
        "macroParadiseEmbeddedCompilerProductVersion",
        "macroParadiseEmbeddedProducerCompilerPluginModule",
        "macroParadiseEmbeddedPluginApiModule",
        "macroParadiseEmbeddedMarkerArtifact",
        "macroParadiseEmbeddedHandlerArtifact",
        "macroParadiseEmbeddedHandlerClasspath",
        "macroParadiseEmbeddedMarkerModuleName",
        "macroParadiseEmbeddedHandlerModuleName",
        "macroParadiseEmbeddedRoleInventory",
        "macroParadiseEmbeddedStrictRoleValidation",
        "macroParadiseEmbeddedValidate",
        "MacroParadiseIntegration.precompiledEmbeddedProject",
        "MacroParadiseIntegration.embeddedModuleIds",
        "MacroParadiseIntegration.precompiledEmbeddedModules",
        "MacroParadiseIntegration.embeddedMarkerPublicationFacade",
        "MacroParadiseIntegration.embeddedHandlerPublicationFacade",
        "EmbeddedProducerRoles.RoleInventory",
        "EmbeddedProducerRoles.packageRoles",
        "EmbeddedProducerRoles.completeHandlerClasspath",
        "SameModuleEmbeddedBinding",
        "macroParadiseSameModuleEmbeddedBinding",
        "macroParadiseSameModuleEmbedded",
        "macroParadiseSameModuleEmbeddedProducerCompilerPluginModule"
      )
    )
  }

  test("embedded producer and consumer integration surfaces remain separate") {
    import MacroParadiseEmbeddedProducerPlugin.autoImport._

    assertEquals(MacroParadiseEmbeddedProducerPlugin.EmbeddedMarkerConfiguration.name, "macroParadiseEmbeddedMarker")
    assertEquals(macroParadiseEmbeddedCompilerProductVersion.key.label, "macroParadiseEmbeddedCompilerProductVersion")
    assertEquals(macroParadiseEmbeddedProducerCompilerPluginModule.key.label, "macroParadiseEmbeddedProducerCompilerPluginModule")
    assertEquals(macroParadiseEmbeddedMarkerArtifact.key.label, "macroParadiseEmbeddedMarkerArtifact")
    assertEquals(macroParadiseEmbeddedHandlerArtifact.key.label, "macroParadiseEmbeddedHandlerArtifact")
    assertEquals(macroParadiseEmbeddedHandlerClasspath.key.label, "macroParadiseEmbeddedHandlerClasspath")
    assertEquals(macroParadiseEmbeddedMarkerModuleName.key.label, "macroParadiseEmbeddedMarkerModuleName")
    assertEquals(macroParadiseEmbeddedHandlerModuleName.key.label, "macroParadiseEmbeddedHandlerModuleName")
    assertEquals(macroParadiseEmbeddedRoleInventory.key.label, "macroParadiseEmbeddedRoleInventory")

    assert(MacroParadiseIntegration.precompiledEmbeddedProject(LocalProject("producer")).nonEmpty)
    assert(MacroParadiseIntegration.precompiledEmbeddedModules("com.example", "sample", "1.0.0").nonEmpty)
  }

  test("same-module embedded surface exposes only the frozen binding, setting, helper, and producer module") {
    import MacroParadiseSameModulePlugin.autoImport._

    val producer = LabelledSource("embedded-producer-source", "demo/EmbeddedAnnotations.scala")
    val binding = macroParadiseSameModuleEmbedded("demo.sameModuleGreeting", producer)

    assertEquals(binding, SameModuleEmbeddedBinding("demo.sameModuleGreeting", producer))
    assertEquals(macroParadiseSameModuleEmbeddedBinding.key.label, "macroParadiseSameModuleEmbeddedBinding")
    assertEquals(
      macroParadiseSameModuleEmbeddedProducerCompilerPluginModule.key.label,
      "macroParadiseSameModuleEmbeddedProducerCompilerPluginModule"
    )
    val baseline = Files.readAllLines(new File("sbt-public-surface.txt").toPath).asScala.filter(_.nonEmpty).toVector
    assertEquals(
      baseline.takeRight(4),
      Vector(
        "SameModuleEmbeddedBinding",
        "macroParadiseSameModuleEmbeddedBinding",
        "macroParadiseSameModuleEmbedded",
        "macroParadiseSameModuleEmbeddedProducerCompilerPluginModule"
      )
    )
  }
}
