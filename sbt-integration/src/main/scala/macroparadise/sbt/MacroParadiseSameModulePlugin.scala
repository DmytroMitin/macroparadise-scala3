package macroparadise.sbt

import sbt._
import sbt.Keys._

object MacroParadiseSameModulePlugin extends AutoPlugin {
  private val IntegrationVersion = "0.1.1-SNAPSHOT"
  private val Organization = "com.github.dmytromitin"
  private val PluginModule = "macroparadise-scala3-plugin"
  private val PluginApiModule = "macroparadise-scala3-plugin-api"
  private val SupportedScalaVersions = Set("3.3.8", "3.8.4", "3.9.0")

  object autoImport {
    val macroParadiseSameModuleCompilerProductVersion =
      settingKey[String]("Macro-Paradise compiler product version used by the experimental same-module path")
    val macroParadiseSameModuleCompilerPluginModule =
      settingKey[ModuleID]("Exact-full-cross Macro-Paradise compiler-plugin module for the same-module path")
    val macroParadiseSameModulePluginApiModule =
      settingKey[ModuleID]("Exact-full-cross Macro-Paradise plugin-API module compiled with same-module handlers")
    val macroParadiseSameModuleSourceRoot =
      settingKey[File]("Explicit source root containing every configured same-module source")
    val macroParadiseSameModuleBinding =
      settingKey[Option[SameModuleHandlerBinding]]("One explicit experimental different-file marker/handler binding")
    val macroParadiseSameModuleConfiguration =
      taskKey[DerivedSameModuleConfiguration]("Validated explicit same-module relationship and source-byte identity")
    val macroParadiseSameModuleSourceIdentity =
      taskKey[String]("Derived SHA-256 identity of the explicitly configured marker and handler source bytes")
    val macroParadiseSameModuleCompilerOptions =
      taskKey[Seq[String]]("Inspectable compiler options for the experimental same-module path")

    def macroParadiseLabelledSource(label: String, relativePath: String): LabelledSource =
      LabelledSource(label, relativePath)

    def macroParadiseSameModuleHandler(
        annotationName: String,
        handlerClassName: String,
        markerSource: LabelledSource,
        handlerSource: LabelledSource
    ): SameModuleHandlerBinding =
      SameModuleHandlerBinding(annotationName, handlerClassName, markerSource, handlerSource)
  }

  import autoImport._

  override def trigger: PluginTrigger = noTrigger

  override def projectSettings: Seq[Def.Setting[_]] = Seq(
    macroParadiseSameModuleCompilerProductVersion := IntegrationVersion,
    macroParadiseSameModuleCompilerPluginModule :=
      (Organization % PluginModule % macroParadiseSameModuleCompilerProductVersion.value)
        .cross(CrossVersion.full),
    macroParadiseSameModulePluginApiModule :=
      (Organization % PluginApiModule % macroParadiseSameModuleCompilerProductVersion.value)
        .cross(CrossVersion.full),
    macroParadiseSameModuleSourceRoot := (Compile / scalaSource).value,
    macroParadiseSameModuleBinding := None,
    libraryDependencies += compilerPlugin(macroParadiseSameModuleCompilerPluginModule.value),
    libraryDependencies += macroParadiseSameModulePluginApiModule.value,
    macroParadiseSameModuleConfiguration := {
      val selectedScala = scalaVersion.value
      check(
        SupportedScalaVersions(selectedScala),
        s"unsupported target Scala version $selectedScala; expected 3.3.8, 3.8.4, or 3.9.0"
      )
      val binding = macroParadiseSameModuleBinding.value.getOrElse {
        throw new MessageOnlyException(
          "macroParadiseSameModuleBinding must explicitly name one different-file marker/handler relationship"
        )
      }
      SameModuleConfiguration.derive(
        macroParadiseSameModuleSourceRoot.value,
        (Compile / classDirectory).value,
        binding
      )
    },
    macroParadiseSameModuleSourceIdentity :=
      macroParadiseSameModuleConfiguration.value.sourceIdentity.identity,
    macroParadiseSameModuleCompilerOptions :=
      macroParadiseSameModuleConfiguration.value.compilerOptions,
    Compile / scalacOptions ++= macroParadiseSameModuleCompilerOptions.value
  )

  private def check(condition: Boolean, message: String): Unit =
    if (!condition) throw new MessageOnlyException(message)
}
