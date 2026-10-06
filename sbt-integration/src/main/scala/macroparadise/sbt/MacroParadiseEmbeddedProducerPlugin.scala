package macroparadise.sbt

import java.io.File

import sbt._
import sbt.Keys._

/** Producer-only integration for the retained embedded declaration compiler plugin. */
object MacroParadiseEmbeddedProducerPlugin extends AutoPlugin {
  private val IntegrationVersion = "0.2.0-SNAPSHOT"
  private val Organization = "com.github.dmytromitin"
  private val ProducerPluginModule = "macroparadise-scala3-embedded-producer-plugin"
  private val PluginApiModule = "macroparadise-scala3-plugin-api"
  private val SupportedScalaVersions = Set("3.3.8", "3.8.4", "3.9.0")

  val EmbeddedMarkerConfiguration: Configuration =
    config("macroParadiseEmbeddedMarker").hide

  object autoImport {
    val macroParadiseEmbeddedCompilerProductVersion =
      settingKey[String]("Macro-Paradise embedded producer compiler product version")
    val macroParadiseEmbeddedProducerCompilerPluginModule =
      settingKey[ModuleID]("Exact-full-cross embedded producer compiler-plugin module")
    val macroParadiseEmbeddedPluginApiModule =
      settingKey[ModuleID]("Exact-full-cross plugin-API compile module for embedded producer source")
    val macroParadiseEmbeddedMarkerArtifact =
      taskKey[File]("Deterministic derived embedded marker-role JAR")
    val macroParadiseEmbeddedHandlerArtifact =
      taskKey[File]("Deterministic derived embedded handler-role JAR")
    val macroParadiseEmbeddedHandlerClasspath =
      taskKey[Seq[File]]("Complete ordered embedded handler expansion classpath")
    val macroParadiseEmbeddedMarkerModuleName =
      settingKey[String]("Derived marker publication-facade module name")
    val macroParadiseEmbeddedHandlerModuleName =
      settingKey[String]("Derived handler publication-facade module name")
    val macroParadiseEmbeddedRoleInventory =
      taskKey[EmbeddedProducerRoles.RoleInventory]("Inspectable derived embedded marker/handler role inventory")
    val macroParadiseEmbeddedStrictRoleValidation =
      settingKey[Boolean]("Require at least one valid embedded declaration in the producer output")
    val macroParadiseEmbeddedValidate =
      taskKey[Unit]("Validate the embedded producer compiler selection and derived roles")
  }

  import autoImport._

  override def trigger: PluginTrigger = noTrigger
  override def projectConfigurations: Seq[Configuration] = Seq(EmbeddedMarkerConfiguration)

  override def projectSettings: Seq[Def.Setting[_]] = Seq(
    macroParadiseEmbeddedCompilerProductVersion := IntegrationVersion,
    macroParadiseEmbeddedProducerCompilerPluginModule :=
      (Organization % ProducerPluginModule % macroParadiseEmbeddedCompilerProductVersion.value)
        .cross(CrossVersion.full),
    macroParadiseEmbeddedPluginApiModule :=
      (Organization % PluginApiModule % macroParadiseEmbeddedCompilerProductVersion.value)
        .cross(CrossVersion.full),
    macroParadiseEmbeddedMarkerModuleName := moduleName.value + "-macro-annotations",
    macroParadiseEmbeddedHandlerModuleName := moduleName.value + "-macro-handlers",
    macroParadiseEmbeddedStrictRoleValidation := true,
    libraryDependencies ++= Seq(
      compilerPlugin(macroParadiseEmbeddedProducerCompilerPluginModule.value),
      macroParadiseEmbeddedPluginApiModule.value
    ),
    Compile / scalacOptions += "-Xplugin-require:macroparadise-embedded-producer",
    macroParadiseEmbeddedRoleInventory := {
      (Compile / compile).value
      val marker = target.value /
        s"${macroParadiseEmbeddedMarkerModuleName.value}_${scalaVersion.value}-${version.value}.jar"
      val handler = target.value /
        s"${macroParadiseEmbeddedHandlerModuleName.value}_${scalaVersion.value}-${version.value}.jar"
      EmbeddedProducerRoles.packageRoles(
        (Compile / classDirectory).value,
        marker,
        handler,
        macroParadiseEmbeddedStrictRoleValidation.value
      )
    },
    macroParadiseEmbeddedMarkerArtifact := {
      macroParadiseEmbeddedRoleInventory.value
      target.value /
        s"${macroParadiseEmbeddedMarkerModuleName.value}_${scalaVersion.value}-${version.value}.jar"
    },
    macroParadiseEmbeddedHandlerArtifact := {
      macroParadiseEmbeddedRoleInventory.value
      target.value /
        s"${macroParadiseEmbeddedHandlerModuleName.value}_${scalaVersion.value}-${version.value}.jar"
    },
    macroParadiseEmbeddedHandlerClasspath := {
      val generator = macroParadiseEmbeddedProducerCompilerPluginModule.value
      val providedToolArtifacts = (Compile / dependencyClasspath).value.collect {
        case attributed if attributed.get(moduleID.key).exists { module =>
          sameModule(module, generator) || isCompilerProvidedModule(module)
        } => attributed.data
      }
      EmbeddedProducerRoles.completeHandlerClasspath(
        macroParadiseEmbeddedHandlerArtifact.value,
        (Compile / classDirectory).value,
        (Runtime / dependencyClasspath).value.files,
        providedToolArtifacts :+ macroParadiseEmbeddedMarkerArtifact.value,
        target.value / "macroparadise-embedded-handler-runtime"
      )
    },
    macroParadiseEmbeddedValidate := {
      require(
        SupportedScalaVersions(scalaVersion.value),
        s"unsupported embedded producer Scala version ${scalaVersion.value}; expected 3.3.8, 3.8.4, or 3.9.0"
      )
      validateModule(
        macroParadiseEmbeddedProducerCompilerPluginModule.value,
        ProducerPluginModule,
        macroParadiseEmbeddedCompilerProductVersion.value,
        "embedded producer compiler plugin"
      )
      validateModule(
        macroParadiseEmbeddedPluginApiModule.value,
        PluginApiModule,
        macroParadiseEmbeddedCompilerProductVersion.value,
        "embedded producer plugin API"
      )
      require(
        macroParadiseEmbeddedMarkerModuleName.value != macroParadiseEmbeddedHandlerModuleName.value,
        "embedded marker and handler publication module names must be distinct"
      )
      val inventory = macroParadiseEmbeddedRoleInventory.value
      require(inventory.markerEntries.toSet.intersect(inventory.handlerEntries.toSet).isEmpty,
        "embedded marker and handler role inventories overlap")
      val closure = macroParadiseEmbeddedHandlerClasspath.value.map(_.getCanonicalFile)
      require(closure.headOption.contains(macroParadiseEmbeddedHandlerArtifact.value.getCanonicalFile),
        "derived handler role must be first on the embedded handler classpath")
      require(!closure.contains((Compile / classDirectory).value.getCanonicalFile),
        "producer class directory duplicates the packaged handler role")
    },
    EmbeddedMarkerConfiguration / products := Seq(macroParadiseEmbeddedMarkerArtifact.value),
    EmbeddedMarkerConfiguration / exportedProducts :=
      Seq(Attributed.blank(macroParadiseEmbeddedMarkerArtifact.value))
  )

  private def sameModule(left: ModuleID, right: ModuleID): Boolean =
    left.organization == right.organization &&
      (left.name == right.name || left.name.startsWith(right.name + "_")) &&
      left.revision == right.revision

  private def isCompilerProvidedModule(module: ModuleID): Boolean =
    module.organization == "org.scala-lang" && Set(
      "scala3-compiler",
      "scala3-interfaces",
      "tasty-core",
      "scala-asm"
    ).exists(name => module.name == name || module.name.startsWith(name + "_")) ||
      module.organization == "org.scala-sbt" && Set(
        "compiler-interface",
        "util-interface"
      ).contains(module.name)

  private def validateModule(
      module: ModuleID,
      expectedName: String,
      expectedVersion: String,
      role: String
  ): Unit = {
    require(module.organization == Organization, s"$role organization mismatch")
    require(module.name == expectedName, s"$role module mismatch")
    require(module.revision == expectedVersion, s"$role/product version mismatch")
    require(module.crossVersion == CrossVersion.full, s"$role must use CrossVersion.full")
  }
}
